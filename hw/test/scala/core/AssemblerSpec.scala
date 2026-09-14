package core

import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

/** Encoding tests. No hardware involved: these check that the bit layout in
  * Isa.scala matches what docs/isa.md says, independently of whether the CPU
  * implements it correctly.
  */
class AssemblerSpec extends AnyFunSuite {

  test("RRR fields land in the documented bit positions") {
    val w = Isa.encRRR(Isa.SUB, rd = 3, ra = 5, rb = 9)
    assert(Isa.opcode(w) == Isa.SUB)
    assert(Isa.rd(w) == 3)
    assert(Isa.ra(w) == 5)
    assert(Isa.rb(w) == 9)
    assert((w & 0x3fff) == 0, "the low 14 bits of an RRR instruction are reserved and must be zero")
  }

  test("RRI carries a signed 18-bit immediate") {
    for (imm <- Seq(0, 1, -1, 12345, -12345, 131071, -131072)) {
      val w = Isa.encRRI(Isa.ADDI, rd = 15, ra = 1, imm)
      assert(Isa.opcode(w) == Isa.ADDI)
      assert(Isa.rd(w) == 15)
      assert(Isa.ra(w) == 1)
      assert(Isa.imm18(w) == imm, s"imm18 round trip failed for $imm")
    }
  }

  test("RI carries a signed 22-bit immediate") {
    for (imm <- Seq(0, 1, -1, 2097151, -2097152)) {
      val w = Isa.encRI(Isa.MOVI, rd = 7, imm)
      assert(Isa.opcode(w) == Isa.MOVI)
      assert(Isa.rd(w) == 7)
      assert(Isa.imm22(w) == imm, s"imm22 round trip failed for $imm")
    }
  }

  test("branch and jump offsets are word scaled") {
    val b = Isa.encBR(Isa.BNE, ra = 2, rb = 3, byteOffset = -64)
    assert(Isa.opcode(b) == Isa.BNE)
    assert(Isa.ra(b) == 2 && Isa.rb(b) == 3)
    assert(Isa.off14(b) == -64)
    assert(Isa.rd(b) == 0, "the rd field of a branch is reserved")

    val j = Isa.encJ(Isa.JMP, byteOffset = 1 << 20)
    assert(Isa.off26(j) == (1 << 20))

    assert(Isa.off14(Isa.encBR(Isa.BEQ, 0, 0, 32764)) == 32764, "maximum forward branch")
    assert(Isa.off14(Isa.encBR(Isa.BEQ, 0, 0, -32768)) == -32768, "maximum backward branch")
    assert(Isa.off26(Isa.encJ(Isa.JMP, 134217724)) == 134217724, "maximum forward jump")
    assert(Isa.off26(Isa.encJ(Isa.JMP, -134217728)) == -134217728, "maximum backward jump")
  }

  test("out of range operands are rejected") {
    assertThrows[IllegalArgumentException](Isa.encRRR(Isa.ADD, 16, 0, 0))
    assertThrows[IllegalArgumentException](Isa.encRRI(Isa.ADDI, 0, 0, 131072))
    assertThrows[IllegalArgumentException](Isa.encRRI(Isa.ADDI, 0, 0, -131073))
    assertThrows[IllegalArgumentException](Isa.encRI(Isa.MOVI, 0, 2097152))
    assertThrows[IllegalArgumentException](Isa.encBR(Isa.BEQ, 0, 0, 2))
    assertThrows[IllegalArgumentException](Isa.encJ(Isa.JMP, 1))
  }

  /** Interpret a MOVI/MOVHI pair the way the specification defines them. */
  private def evaluate(words: Array[Int]): Int = {
    var rd = 0
    words.foreach { w =>
      Isa.opcode(w) match {
        case Isa.MOVI  => rd = Isa.imm22(w)
        case Isa.MOVHI => rd = (Isa.imm22(w) << 10) | (rd & 0x3ff)
        case other     => fail(f"unexpected opcode 0x$other%02x in a li expansion")
      }
    }
    rd
  }

  test("li builds every 32-bit constant") {
    val rng = new Random(1234)
    val values = Seq(0, 1, -1, 7, -7, 0x3ff, 0x400, 0x1fffff, 0x200000, -0x200000, -0x200001,
      0x7fffffff, 0x80000000, 0xdeadbeef, 0x12345678, 0xffff0000) ++
      Seq.fill(200)(rng.nextInt())

    for (v <- values) {
      val a = new Assembler()
      a.li(1, v)
      val words = a.assemble()
      assert(words.length == a.liSize(v), s"liSize disagrees with li for $v")
      assert(evaluate(words) == v, f"li failed for 0x$v%08x")
    }
  }

  test("li uses one instruction exactly when the constant fits in imm22") {
    assert(new Assembler().liSize(2097151) == 1)
    assert(new Assembler().liSize(-2097152) == 1)
    assert(new Assembler().liSize(2097152) == 2)
    assert(new Assembler().liSize(-2097153) == 2)
  }

  test("la always takes two instructions") {
    val a = new Assembler()
    a.la(1, "here")
    a.nop()
    a.label("here")
    val words = a.assemble()
    assert(words.length == 3)
    assert(evaluate(words.take(2)) == 12, "la should have produced the address of the label")
  }

  test("labels resolve forwards and backwards") {
    val asm = Assembler.build() { a =>
      a.label("top")
      a.nop()
      a.beq(0, 0, "bottom")
      a.nop()
      a.label("bottom")
      a.jmp("top")
    }
    val words = asm.assemble()
    assert(asm.symbols("top") == 0)
    assert(asm.symbols("bottom") == 12)
    assert(4 + Isa.off14(words(1)) == 12, "forward branch target")
    assert(12 + Isa.off26(words(3)) == 0, "backward jump target")
  }

  test("exactly the documented opcodes are legal") {
    // 16 register ALU + 15 immediate (0x11 is reserved) + 9 memory/system + 10 control.
    assert(Isa.DEFINED_OPCODES.size == 50)
    for (op <- 0 until 64) {
      val legal = Isa.isLegal(op << Isa.OP_LO)
      assert(legal == Isa.DEFINED_OPCODES.contains(op), f"opcode 0x$op%02x legality")
      if (legal) assert(Isa.MNEMONICS.contains(op), f"opcode 0x$op%02x has no mnemonic")
    }
    assert(!Isa.isLegal(0x11 << Isa.OP_LO), "0x11 is the reserved SUBI slot")
  }

  test("the opcode map is structured as documented") {
    // The low nibble of an immediate ALU opcode is the same ALU function code
    // as its register form, which is what lets the decoder skip a translation.
    val mirrored = Seq(
      Isa.ADD -> Isa.ADDI, Isa.AND -> Isa.ANDI, Isa.OR -> Isa.ORI, Isa.XOR -> Isa.XORI,
      Isa.SHL -> Isa.SHLI, Isa.SHR -> Isa.SHRI, Isa.SAR -> Isa.SARI,
      Isa.SLT -> Isa.SLTI, Isa.SLTU -> Isa.SLTUI,
      Isa.SEQ -> Isa.SEQI, Isa.SNE -> Isa.SNEI, Isa.ROR -> Isa.RORI
    )
    for ((reg, imm) <- mirrored) assert(imm == (reg | 0x10), f"0x$imm%02x should mirror 0x$reg%02x")

    // The three that break the mirror are the multiplies, which have no
    // immediate form; their slots hold constant formation instead.
    assert(Isa.CLS1_RI_OPS == Set(Isa.MUL | 0x10, Isa.MULH | 0x10, Isa.MULHU | 0x10))

    // Class bits.
    for (op <- Seq(Isa.ADD, Isa.ROR)) assert((op >> 4) == Isa.CLS_ALU_R)
    for (op <- Seq(Isa.ADDI, Isa.MOVHI)) assert((op >> 4) == Isa.CLS_ALU_I)
    for (op <- Seq(Isa.LDW, Isa.STB, Isa.HALT)) assert((op >> 4) == Isa.CLS_MEM)
    for (op <- Seq(Isa.BEQ, Isa.CALLR)) assert((op >> 4) == Isa.CLS_CTRL)

    // Within memory, bit 3 says store and within control, bit 3 says jump.
    for (op <- Seq(Isa.LDW, Isa.LDH, Isa.LDHU, Isa.LDB, Isa.LDBU)) assert((op & 0x8) == 0)
    for (op <- Seq(Isa.STW, Isa.STH, Isa.STB)) assert((op & 0x8) != 0)
    for (op <- Seq(Isa.BEQ, Isa.BGEU)) assert((op & 0x8) == 0)
    for (op <- Seq(Isa.JMP, Isa.CALLR)) assert((op & 0x8) != 0)
  }

  test("disassembly names every legal opcode") {
    for (op <- Isa.DEFINED_OPCODES) {
      val text = Isa.disassemble(op << Isa.OP_LO)
      assert(text.nonEmpty && !text.contains("illegal"), f"opcode 0x$op%02x disassembled as '$text'")
    }
  }
}
