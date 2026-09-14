package core

import org.scalatest.funsuite.AnyFunSuite

/** Base class for tests that run a program on the RTL.
  *
  * Every test goes through [[cosim]], which runs the same program on the
  * hardware and on the reference model in CoreEmu and insists that the two
  * agree on the whole architectural state: all sixteen registers, the halt and
  * trap status, the trapping PC, the number of instructions retired, and every
  * memory word the test asks to see.
  *
  * Individual tests then add their own hand-computed expectations on top. That
  * second layer matters: agreement between the RTL and the model only proves
  * they read docs/isa.md the same way, not that either read it correctly.
  */
abstract class CoreSpec extends AnyFunSuite {

  /** Byte address of the scratch area that tests store into. Chosen to sit
    * well clear of any program a test is likely to assemble.
    */
  val DataByte: Int = 2048
  val DataWord: Int = DataByte / 4

  def scratch(index: Int): Int = DataWord + index

  def cosimProgram(
      program: Array[Int],
      data: Map[Int, Int] = Map.empty,
      readRange: Range = 0 until 0,
      maxCycles: Int = 200000
  ): RunResult = {
    val rtl = Sim.run(program, data, maxCycles, readRange)
    val emu = Sim.reference(program, data)

    def listing = "\n--- program ---\n" + Assembler.listing(program) + "\n"

    assert(rtl.halted, s"the core never halted within $maxCycles cycles: $rtl$listing")
    assert(emu.halted, s"the reference model never halted$listing")

    for (i <- 0 until Isa.REG_COUNT) {
      assert(
        rtl.reg(i) == emu.regs(i),
        f"${Isa.regName(i)}%s: RTL 0x${rtl.reg(i)}%08x, reference 0x${emu.regs(i)}%08x$listing"
      )
    }
    assert(rtl.trapped == emu.trapped,
      s"trapped: RTL ${rtl.trapped}, reference ${emu.trapped}$listing")
    assert(rtl.cause == emu.cause,
      s"cause: RTL ${rtl.causeName}, reference ${emu.cause}$listing")
    assert(rtl.trapPc == (emu.trapPc.toLong & 0xffffffffL),
      f"stop PC: RTL 0x${rtl.trapPc}%08x, reference 0x${emu.trapPc}%08x$listing")
    assert(rtl.retired == emu.retired,
      s"retired: RTL ${rtl.retired}, reference ${emu.retired}$listing")

    for (i <- readRange) {
      assert(
        rtl.word(i) == emu.mem(i),
        f"memory word $i: RTL 0x${rtl.word(i)}%08x, reference 0x${emu.mem(i)}%08x$listing"
      )
    }
    rtl
  }

  /** Assemble, run on both, and compare. */
  def cosim(
      data: Map[Int, Int] = Map.empty,
      readRange: Range = 0 until 0,
      maxCycles: Int = 200000
  )(body: Assembler => Unit): RunResult =
    cosimProgram(Assembler()(body), data, readRange, maxCycles)

  /** Pack a byte string into little-endian words for the `data` map. */
  def packBytes(bytes: Seq[Int], atWord: Int): Map[Int, Int] =
    bytes.grouped(4).zipWithIndex.map { case (group, i) =>
      val padded = group.padTo(4, 0)
      val word = (padded(0) & 0xff) | ((padded(1) & 0xff) << 8) |
        ((padded(2) & 0xff) << 16) | ((padded(3) & 0xff) << 24)
      (atWord + i) -> word
    }.toMap

  /** Assert on a register by ABI name, reporting in hex on failure. */
  def expectReg(r: RunResult, index: Int, expected: Int, note: String = ""): Unit =
    assert(
      r.reg(index) == expected,
      f"${Isa.regName(index)}%s${if (note.isEmpty) "" else s" ($note)"}: " +
        f"got 0x${r.reg(index)}%08x, expected 0x$expected%08x"
    )
}
