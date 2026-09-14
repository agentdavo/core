package axiom

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim.SimCompiled

/** Base class for tests that run a program on the Axiom-64 RTL.
  *
  * Every test goes through [[cosim]], which runs the program on the hardware
  * and on the reference model and insists the two agree on the whole
  * architectural state: all thirty-two registers, all eight predicates, the
  * stop status and program counter, the retired instruction count, and every
  * memory doubleword the test asks to see.
  *
  * Tests then add their own hand-computed expectations. That second layer
  * matters: agreement between the model and the hardware only proves they read
  * the specification the same way.
  */
abstract class AxiomSpec extends AnyFunSuite {

  /** Byte address of the scratch area tests store into. */
  val DataByte: Int = 4096
  val DataWord: Int = DataByte / 8

  /** The addresses the reference model executes, in order. */
  private def referenceTrace(program: Array[Int], data: Map[Int, Long], limit: Int): Seq[Long] = {
    val emu = new AxiomEmu(AxiomSim.MemWords)
    emu.loadProgram(program)
    emu.loadData(data)
    val trace = scala.collection.mutable.ArrayBuffer[Long]()
    var steps = 0
    while (!emu.halted && steps < limit) {
      val at = emu.pc
      emu.step()
      // A stopping instruction does not retire, matching the hardware counter.
      if (!emu.halted) trace += at
      steps += 1
    }
    trace.toSeq
  }

  private def describe(program: Array[Int], address: Long): String = {
    val index = (address / 4).toInt
    if (index >= 0 && index < program.length) Isa.disassemble(program(index), address.toInt)
    else "<outside the program>"
  }

  /** Turn a mismatch into something actionable.
    *
    * First compares the executed instruction streams, which localises any
    * control flow bug exactly. If those agree then the bug is in a value, so
    * the program is bisected: the shortest prefix whose final state already
    * disagrees ends with the instruction at fault.
    */
  private def diagnose(program: Array[Int], data: Map[Int, Long], rtl: RunResult, message: String): Nothing = {
    val report = new StringBuilder
    report.append("\n").append(message).append("\n")

    val expected = referenceTrace(program, data, rtl.retireTrace.length + 16)
    val firstDifference = rtl.retireTrace.zip(expected).indexWhere { case (a, b) => a != b }

    if (firstDifference >= 0) {
      report.append(f"%nretired instruction $firstDifference differs:%n")
      report.append(f"  RTL       0x${rtl.retireTrace(firstDifference)}%08x  ${describe(program, rtl.retireTrace(firstDifference))}%n")
      report.append(f"  reference 0x${expected(firstDifference)}%08x  ${describe(program, expected(firstDifference))}%n")
    } else {
      def agrees(prefix: Int): Boolean = {
        val cut = program.take(prefix) :+ Isa.encSystem(Isa.SystemFn.HALT)
        val hw = AxiomSim.run(cut, data, 200000, 0 until 0)
        val model = AxiomSim.reference(cut, data)
        hw.halted && model.halted &&
          (0 until Isa.REG_COUNT).forall(i => hw.reg(i) == model.regs(i)) &&
          (0 until Isa.PRED_COUNT).forall(i => hw.predicate(i) == model.preds(i))
      }
      if (!agrees(program.length)) {
        var low = 0
        var high = program.length
        while (low + 1 < high) {
          val middle = (low + high) / 2
          if (agrees(middle)) low = middle else high = middle
        }
        val at = (high - 1) * 4
        report.append(f"%nstate first disagrees after the instruction at 0x$at%08x:%n")
        report.append(s"  ${describe(program, at.toLong)}\n")
      } else {
        report.append("\nno prefix disagrees, so the difference is in memory or in a counter\n")
      }
    }
    report.append("\n--- program ---\n").append(Assembler.listing(program)).append("\n")
    fail(report.toString)
  }

  def cosimProgram(
      program: Array[Int],
      data: Map[Int, Long] = Map.empty,
      readRange: Range = 0 until 0,
      maxCycles: Int = 200000,
      design: SimCompiled[AxiomSoc] = AxiomSim.soc
  ): RunResult = {
    val rtl = AxiomSim.run(program, data, maxCycles, readRange, design)
    val emu = AxiomSim.reference(program, data)

    def check(condition: Boolean, message: => String): Unit =
      if (!condition) diagnose(program, data, rtl, message)

    check(rtl.halted, s"the core never halted within $maxCycles cycles: $rtl")
    check(emu.halted, "the reference model never halted")

    for (i <- 0 until Isa.REG_COUNT) {
      check(rtl.reg(i) == emu.regs(i),
        f"${Isa.regName(i)}%s: RTL 0x${rtl.reg(i)}%016x, reference 0x${emu.regs(i)}%016x")
    }
    for (i <- 0 until Isa.PRED_COUNT) {
      check(rtl.predicate(i) == emu.preds(i), s"p$i: RTL ${rtl.predicate(i)}, reference ${emu.preds(i)}")
    }
    check(rtl.trapped == emu.trapped, s"trapped: RTL ${rtl.trapped}, reference ${emu.trapped}")
    check(rtl.cause == emu.cause, s"cause: RTL ${rtl.causeName}, reference ${Isa.Cause.NAMES(emu.cause)}")
    check(rtl.trapPc == emu.trapPc, f"stop pc: RTL 0x${rtl.trapPc}%016x, reference 0x${emu.trapPc}%016x")
    check(rtl.retired == emu.retired, s"retired: RTL ${rtl.retired}, reference ${emu.retired}")

    for (i <- readRange) {
      check(rtl.word(i) == emu.mem(i),
        f"doubleword $i: RTL 0x${rtl.word(i)}%016x, reference 0x${emu.mem(i)}%016x")
    }
    rtl
  }

  def cosim(
      data: Map[Int, Long] = Map.empty,
      readRange: Range = 0 until 0,
      maxCycles: Int = 200000
  )(body: Assembler => Unit): RunResult =
    cosimProgram(Assembler()(body), data, readRange, maxCycles)

  def expectReg(r: RunResult, index: Int, expected: Long, note: String = ""): Unit =
    assert(r.reg(index) == expected,
      f"${Isa.regName(index)}%s${if (note.isEmpty) "" else s" ($note)"}: " +
        f"got 0x${r.reg(index)}%016x, expected 0x$expected%016x")
}
