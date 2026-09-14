package core

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
    regs: Array[Int],
    memory: Map[Int, Int]
) {
  def reg(i: Int): Int = regs(i)

  def word(index: Int): Int =
    memory.getOrElse(index, throw new NoSuchElementException(
      s"word $index was not read back; widen the readRange passed to Sim.run"))

  def causeName: String = cause match {
    case Isa.Cause.NONE             => "NONE"
    case Isa.Cause.ILLEGAL          => "ILLEGAL"
    case Isa.Cause.MISALIGNED_FETCH => "MISALIGNED_FETCH"
    case Isa.Cause.MISALIGNED_LOAD  => "MISALIGNED_LOAD"
    case Isa.Cause.MISALIGNED_STORE => "MISALIGNED_STORE"
    case other                      => s"UNKNOWN($other)"
  }

  override def toString: String =
    f"RunResult(halted=$halted, trapped=$trapped, cause=$causeName, trapPc=0x$trapPc%08x, " +
      f"cycles=$cycles, retired=$retired)"
}

/** Verilator-backed simulation of the C1 core.
  *
  * The design is elaborated and verilated once for the whole test run and then
  * reused, because Verilator compilation dominates the cost of a test suite
  * this size. Programs are pushed in through the SoC's debug memory port while
  * the CPU is held in reset, so nothing here depends on being able to reach
  * inside the model.
  */
object Sim {

  val MemWords: Int = 4096

  private lazy val config = {
    val base = SimConfig.withVerilator.workspacePath("simWorkspace")
    if (sys.env.get("CORE_WAVE").contains("1")) base.withFstWave else base
  }

  /** The default core: 32-bit addresses, a multiplier, reset vector zero. */
  lazy val soc: SimCompiled[CoreSoc] =
    config.workspaceName("CoreSoc").compile(new CoreSoc(CoreConfig(), MemWords))

  /** Built with `hasMultiplier = false`, so MUL/MULH/MULHU decode as illegal. */
  lazy val socWithoutMultiplier: SimCompiled[CoreSoc] =
    config.workspaceName("CoreSocNoMul")
      .compile(new CoreSoc(CoreConfig(hasMultiplier = false), MemWords))

  /** Reset vector at 0x100 rather than 0. */
  val AltResetVector: Int = 0x100
  lazy val socAltReset: SimCompiled[CoreSoc] =
    config.workspaceName("CoreSocAltReset")
      .compile(new CoreSoc(CoreConfig(resetVector = AltResetVector), MemWords))

  /** Load `program` at address 0, run until the core halts, and report.
    *
    * @param data      extra memory contents, keyed by word index
    * @param readRange word indices to read back after the run
    */
  def run(
      program: Array[Int],
      data: Map[Int, Int] = Map.empty,
      maxCycles: Int = 100000,
      readRange: Range = 0 until 0,
      design: SimCompiled[CoreSoc] = soc,
      loadAtWord: Int = 0
  ): RunResult = {
    require(loadAtWord + program.length <= MemWords,
      s"program of ${program.length} words does not fit at word $loadAtWord")

    var result: RunResult = null

    design.doSim { dut =>
      dut.io.dbgMemEnable #= false
      dut.io.dbgMemWrite #= false
      dut.io.dbgMemAddr #= 0
      dut.io.dbgMemWData #= 0
      dut.io.dbgRegAddr #= 0

      dut.clockDomain.forkStimulus(period = 10)

      // Hard stop, so a testbench or pipeline deadlock fails the test instead
      // of spinning. Generous: the load phase alone costs one cycle per word.
      SimTimeout(10L * (maxCycles + program.length + data.size + 4 * MemWords + 1000))

      // Let forkStimulus finish its own reset pulse before taking reset over.
      // The CPU runs for a few cycles here, fetching from a zeroed RAM, which
      // decodes as NOPs and changes nothing.
      dut.clockDomain.waitRisingEdge(4)

      // Hold the CPU in reset and fill memory through the debug port. Waits in
      // this window have to be on the raw clock edge: `waitSampling` only fires
      // when the clock domain is out of reset, so it would deadlock here.
      dut.clockDomain.assertReset()
      dut.clockDomain.waitRisingEdge(2)

      dut.io.dbgMemEnable #= true
      dut.io.dbgMemWrite #= true
      for ((word, index) <- program.zipWithIndex) {
        dut.io.dbgMemAddr #= loadAtWord + index
        dut.io.dbgMemWData #= word.toLong & 0xffffffffL
        dut.clockDomain.waitRisingEdge()
      }
      for ((index, value) <- data) {
        require(index >= 0 && index < MemWords, s"data word index $index out of range")
        dut.io.dbgMemAddr #= index
        dut.io.dbgMemWData #= value.toLong & 0xffffffffL
        dut.clockDomain.waitRisingEdge()
      }
      dut.io.dbgMemWrite #= false
      dut.io.dbgMemEnable #= false
      dut.clockDomain.waitRisingEdge(2)

      dut.clockDomain.deassertReset()
      dut.clockDomain.waitRisingEdge()

      // ---- run ----------------------------------------------------------
      var elapsed = 0
      while (!dut.io.halted.toBoolean && elapsed < maxCycles) {
        dut.clockDomain.waitSampling()
        elapsed += 1
      }
      val didHalt = dut.io.halted.toBoolean

      // `halted` rises the cycle after execute sees the HALT, while the two
      // instructions ahead of it are still draining through memory and
      // writeback. Give them time to land before sampling the retire counter.
      dut.clockDomain.waitSampling(4)

      val trapped = dut.io.trapped.toBoolean
      val cause   = dut.io.cause.toInt
      val trapPc  = dut.io.trapPc.toLong
      val cycles  = dut.io.cycleCount.toLong
      val retired = dut.io.retireCount.toLong

      // ---- read the register file back ------------------------------------
      val regs = new Array[Int](Isa.REG_COUNT)
      for (i <- 0 until Isa.REG_COUNT) {
        dut.io.dbgRegAddr #= i
        dut.clockDomain.waitSampling()
        regs(i) = dut.io.dbgRegData.toLong.toInt
      }
      dut.io.dbgRegAddr #= 0

      // ---- read memory back -----------------------------------------------
      val memory = scala.collection.mutable.LinkedHashMap[Int, Int]()
      if (readRange.nonEmpty) {
        dut.io.dbgMemEnable #= true
        dut.io.dbgMemWrite #= false
        for (index <- readRange) {
          require(index >= 0 && index < MemWords, s"read index $index out of range")
          dut.io.dbgMemAddr #= index
          dut.clockDomain.waitSampling()
          // The read port is synchronous, so the value for this address lands
          // after the next edge.
          dut.clockDomain.waitSampling()
          memory(index) = dut.io.dbgMemRData.toLong.toInt
        }
        dut.io.dbgMemEnable #= false
      }

      result = RunResult(didHalt, trapped, cause, trapPc, cycles, retired, regs, memory.toMap)
    }

    result
  }

  /** Run the same program on the reference model. */
  def reference(
      program: Array[Int],
      data: Map[Int, Int] = Map.empty,
      maxSteps: Int = 1000000,
      resetVector: Int = 0,
      loadAtWord: Int = 0
  ): CoreEmu = {
    val emu = new CoreEmu(MemWords, resetVector)
    emu.loadProgram(program, loadAtWord * 4)
    emu.loadData(data)
    emu.run(maxSteps)
    emu
  }
}
