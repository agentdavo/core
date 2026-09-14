package axiom

import spinal.core._
import spinal.core.sim._

/** The outcome of running a program on the RTL. */
case class RunResult(
    halted: Boolean,
    trapped: Boolean,
    cause: Int,
    trapPc: Long,
    cycles: Long,
    retired: Long,
    regs: Array[Long],
    predicates: Int,
    memory: Map[Int, Long],
    retireTrace: Seq[Long]
) {
  def reg(i: Int): Long = regs(i)
  def predicate(i: Int): Boolean = ((predicates >> i) & 1) != 0

  def word(index: Int): Long =
    memory.getOrElse(index, throw new NoSuchElementException(
      s"doubleword $index was not read back; widen the readRange passed to AxiomSim.run"))

  def causeName: String = Isa.Cause.NAMES.getOrElse(cause, s"UNKNOWN($cause)")

  override def toString: String =
    f"RunResult(halted=$halted, trapped=$trapped, cause=$causeName, trapPc=0x$trapPc%016x, " +
      f"cycles=$cycles, retired=$retired)"
}

/** Verilator-backed simulation of the Axiom-64 core.
  *
  * Compiled once for the whole test run and reused, because Verilator
  * compilation dominates the cost of a suite this size. Programs are pushed in
  * through the debug memory port while the core is held in reset, so nothing
  * here depends on reaching inside the model.
  */
object AxiomSim {

  val MemWords: Int = 4096

  private val Mask64 = (BigInt(1) << 64) - 1

  private def unsigned(value: Long): BigInt = BigInt(value) & Mask64

  private lazy val config = {
    val base = SimConfig.withVerilator.workspacePath("simWorkspace")
    if (sys.env.get("AXIOM_WAVE").contains("1")) base.withFstWave else base
  }

  lazy val soc: SimCompiled[AxiomSoc] =
    config.workspaceName("AxiomSoc").compile(new AxiomSoc(memWords = MemWords))

  lazy val socWithoutMultiplier: SimCompiled[AxiomSoc] =
    config.workspaceName("AxiomSocNoMul")
      .compile(new AxiomSoc(memWords = MemWords, withMultiplier = false))

  lazy val socWithoutAtomics: SimCompiled[AxiomSoc] =
    config.workspaceName("AxiomSocNoAtomic")
      .compile(new AxiomSoc(memWords = MemWords, withAtomics = false))

  /** Base register updates waited for rather than forwarded.
    *
    * The same programs must produce the same answers with a narrower bypass
    * network, only taking more cycles, so this is the design the correctness
    * suite runs against to prove the interlock covers what the forwarding
    * stopped covering.
    */
  lazy val socWithoutBaseForward: SimCompiled[AxiomSoc] =
    config.workspaceName("AxiomSocNoBaseFwd")
      .compile(new AxiomSoc(memWords = MemWords, forwardBase = false))

  /** A memory that refuses commands about a third of the time.
    *
    * The whole point of the stallable protocol is that the core waits on a
    * signal rather than on a count, and the only way to know it does is to run
    * the same programs through a memory that makes it wait. Answers must be
    * identical; only the cycle count may differ.
    */
  lazy val socStalling: SimCompiled[AxiomSoc] =
    config.workspaceName("AxiomSocStalling")
      .compile(new AxiomSoc(memWords = MemWords, memoryStall = 5))

  /** Reset vector at 0x400 rather than 0. */
  val AltResetVector: Int = 0x400
  lazy val socAltReset: SimCompiled[AxiomSoc] =
    config.workspaceName("AxiomSocAltReset")
      .compile(new AxiomSoc(memWords = MemWords, resetVector = AltResetVector))

  /** Pack 32-bit instruction words into little-endian doublewords. */
  def packProgram(program: Array[Int]): Array[Long] =
    program.grouped(2).map { pair =>
      val low = pair(0).toLong & 0xffffffffL
      val high = if (pair.length > 1) pair(1).toLong & 0xffffffffL else 0L
      low | (high << 32)
    }.toArray

  /** Load `program` at address 0, run until the core halts, and report.
    *
    * @param data      extra memory contents, keyed by doubleword index
    * @param readRange doubleword indices to read back afterwards
    */
  def run(
      program: Array[Int],
      data: Map[Int, Long] = Map.empty,
      maxCycles: Int = 100000,
      readRange: Range = 0 until 0,
      design: SimCompiled[AxiomSoc] = soc,
      traceLimit: Int = 4096,
      loadAtWord: Int = 0
  ): RunResult = {
    val packed = packProgram(program)
    require(loadAtWord + packed.length <= MemWords,
      s"program of ${program.length} words does not fit at doubleword $loadAtWord")

    var result: RunResult = null

    design.doSim { dut =>
      dut.io.dbgMemEnable #= false
      dut.io.dbgMemWrite #= false
      dut.io.dbgMemAddr #= 0
      dut.io.dbgMemWData #= 0
      dut.io.dbgRegAddr #= 0

      dut.clockDomain.forkStimulus(period = 10)
      SimTimeout(10L * (maxCycles + packed.length + data.size + 4 * MemWords + 1000))

      // Let forkStimulus finish its own reset pulse before taking reset over.
      // The core runs for a few cycles here against a zeroed memory, which
      // decodes as an add into x0 and changes nothing.
      dut.clockDomain.waitRisingEdge(4)

      // Waits inside the reset window have to be on the raw clock edge:
      // waitSampling only fires when the domain is out of reset.
      dut.clockDomain.assertReset()
      dut.clockDomain.waitRisingEdge(2)

      dut.io.dbgMemEnable #= true
      dut.io.dbgMemWrite #= true
      for ((word, index) <- packed.zipWithIndex) {
        dut.io.dbgMemAddr #= loadAtWord + index
        dut.io.dbgMemWData #= unsigned(word)
        dut.clockDomain.waitRisingEdge()
      }
      for ((index, value) <- data) {
        require(index >= 0 && index < MemWords, s"data index $index out of range")
        dut.io.dbgMemAddr #= index
        dut.io.dbgMemWData #= unsigned(value)
        dut.clockDomain.waitRisingEdge()
      }
      dut.io.dbgMemWrite #= false
      dut.io.dbgMemEnable #= false
      dut.clockDomain.waitRisingEdge(2)

      dut.clockDomain.deassertReset()
      dut.clockDomain.waitRisingEdge()

      val trace = scala.collection.mutable.ArrayBuffer[Long]()
      var elapsed = 0
      while (!dut.io.halted.toBoolean && elapsed < maxCycles) {
        if (dut.io.dbgRetireValid.toBoolean && trace.length < traceLimit) {
          trace += dut.io.dbgRetirePc.toBigInt.toLong
        }
        dut.clockDomain.waitSampling()
        elapsed += 1
      }
      val didHalt = dut.io.halted.toBoolean

      // `halted` rises the cycle after execute sees the stop, while the two
      // instructions ahead of it are still draining. Let them land before
      // sampling the retire counter.
      for (_ <- 0 until 4) {
        if (dut.io.dbgRetireValid.toBoolean && trace.length < traceLimit) {
          trace += dut.io.dbgRetirePc.toBigInt.toLong
        }
        dut.clockDomain.waitSampling()
      }

      val trapped = dut.io.trapped.toBoolean
      val cause = dut.io.cause.toInt
      val trapPc = dut.io.trapPc.toBigInt.toLong
      val cycles = dut.io.cycleCount.toLong
      val retired = dut.io.retireCount.toLong
      val predicates = dut.io.dbgPredicates.toInt

      val regs = new Array[Long](Isa.REG_COUNT)
      for (i <- 0 until Isa.REG_COUNT) {
        dut.io.dbgRegAddr #= i
        dut.clockDomain.waitSampling()
        regs(i) = dut.io.dbgRegData.toBigInt.toLong
      }
      dut.io.dbgRegAddr #= 0

      val memory = scala.collection.mutable.LinkedHashMap[Int, Long]()
      if (readRange.nonEmpty) {
        dut.io.dbgMemEnable #= true
        dut.io.dbgMemWrite #= false
        for (index <- readRange) {
          require(index >= 0 && index < MemWords, s"read index $index out of range")
          dut.io.dbgMemAddr #= index
          dut.clockDomain.waitSampling()
          dut.clockDomain.waitSampling()
          memory(index) = dut.io.dbgMemRData.toBigInt.toLong
        }
        dut.io.dbgMemEnable #= false
      }

      result = RunResult(didHalt, trapped, cause, trapPc, cycles, retired, regs, predicates,
        memory.toMap, trace.toSeq)
    }

    result
  }

  /** Run the same program on the reference model. */
  def reference(
      program: Array[Int],
      data: Map[Int, Long] = Map.empty,
      maxSteps: Int = 1000000,
      resetVector: Long = 0,
      loadAtByte: Long = 0
  ): AxiomEmu = {
    val emu = new AxiomEmu(MemWords, resetVector)
    emu.loadProgram(program, loadAtByte)
    emu.loadData(data)
    emu.run(maxSteps)
    emu
  }
}
