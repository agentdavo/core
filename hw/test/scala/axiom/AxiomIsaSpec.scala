package axiom

/** Directed tests for every instruction in Axiom-64.
  *
  * Every expected value here was worked out by hand from docs/axiom/isa.md and
  * written as a literal. That is the whole point of the suite. The randomised
  * co-simulation already proves that the RTL and the reference model agree;
  * what it cannot prove is that both of them read the specification correctly.
  * A shared misreading, say a W form that sign extends from the wrong bit or a
  * shift that masks six bits where the specification asks for five, would pass
  * every random program and fail here.
  *
  * Each test still goes through [[cosimProgram]], so the reference model is
  * checked at the same time and any disagreement is bisected down to the
  * offending instruction before this file's own assertions ever run.
  */
class AxiomIsaSpec extends AxiomSpec {

  // =====================================================================
  // Table driven helpers
  // =====================================================================

  private case class RegCase(
      name: String,
      op: (Assembler, Int, Int, Int) => Unit,
      a: Long,
      b: Long,
      expected: Long)

  private case class ShiftCase(
      name: String,
      op: (Assembler, Int, Int, Int) => Unit,
      a: Long,
      shamt: Int,
      expected: Long)

  private case class ImmCase(
      name: String,
      op: (Assembler, Int, Int, Int) => Unit,
      a: Long,
      imm: Int,
      expected: Long)

  /** Run every case into t2 and store the doubleword, then check each slot.
    *
    * Batching keeps one Verilator run per table rather than one per case, and
    * routing the answer through memory rather than through a register means
    * the store path is exercised on every single case as a side effect.
    */
  private def checkRegCases(cases: Seq[RegCase]): Unit = {
    val program = Assembler() { asm =>
      import asm._
      li(s0, DataByte)
      for ((c, i) <- cases.zipWithIndex) {
        li(t0, c.a)
        li(t1, c.b)
        c.op(asm, t2, t0, t1)
        std(t2, s0, i * 8)
      }
      halt()
    }
    val result = cosimProgram(program, readRange = DataWord until (DataWord + cases.length))
    for ((c, i) <- cases.zipWithIndex) {
      val got = result.word(DataWord + i)
      assert(got == c.expected,
        f"${c.name}%s: on 0x${c.a}%016x and 0x${c.b}%016x gave 0x$got%016x, expected 0x${c.expected}%016x")
    }
  }

  private def checkShiftCases(cases: Seq[ShiftCase]): Unit = {
    val program = Assembler() { asm =>
      import asm._
      li(s0, DataByte)
      for ((c, i) <- cases.zipWithIndex) {
        li(t0, c.a)
        c.op(asm, t2, t0, c.shamt)
        std(t2, s0, i * 8)
      }
      halt()
    }
    val result = cosimProgram(program, readRange = DataWord until (DataWord + cases.length))
    for ((c, i) <- cases.zipWithIndex) {
      val got = result.word(DataWord + i)
      assert(got == c.expected,
        f"${c.name}%s: on 0x${c.a}%016x by ${c.shamt}%d gave 0x$got%016x, expected 0x${c.expected}%016x")
    }
  }

  private def checkImmCases(cases: Seq[ImmCase]): Unit = {
    val program = Assembler() { asm =>
      import asm._
      li(s0, DataByte)
      for ((c, i) <- cases.zipWithIndex) {
        li(t0, c.a)
        c.op(asm, t2, t0, c.imm)
        std(t2, s0, i * 8)
      }
      halt()
    }
    val result = cosimProgram(program, readRange = DataWord until (DataWord + cases.length))
    for ((c, i) <- cases.zipWithIndex) {
      val got = result.word(DataWord + i)
      assert(got == c.expected,
        f"${c.name}%s: on 0x${c.a}%016x with immediate ${c.imm}%d gave 0x$got%016x, " +
          f"expected 0x${c.expected}%016x")
    }
  }

  /** A recognisable 64-bit pattern with a distinct value in every byte lane,
    * so that a shift or a rotate off by one lane is visible at a glance.
    */
  private val X = 0x0123456789abcdefL
  private val MIN = 0x8000000000000000L
  private val MAX = 0x7fffffffffffffffL
  private val ONES = 0xffffffffffffffffL

  // =====================================================================
  // Opcode 0x00: the twenty-eight register ALU functions
  // =====================================================================

  test("ALU register: add and subtract, including the wrap at both ends") {
    checkRegCases(Seq(
      RegCase("add", _.add(_, _, _), 7, 5, 12),
      RegCase("add of negatives", _.add(_, _, _), -7, -5, -12),
      // There are no flags in this architecture, so the only thing an overflow
      // can do is wrap. 2^63 - 1 plus one is the sign bit alone.
      RegCase("add wraps into the sign bit", _.add(_, _, _), MAX, 1, MIN),
      RegCase("add wraps through zero", _.add(_, _, _), ONES, 1, 0),
      RegCase("sub", _.sub(_, _, _), 7, 5, 2),
      RegCase("sub goes negative", _.sub(_, _, _), 5, 7, -2),
      RegCase("sub wraps out of the sign bit", _.sub(_, _, _), MIN, 1, MAX),
      RegCase("sub of equal operands", _.sub(_, _, _), X, X, 0)
    ))
  }

  test("ALU register: the eight logical functions") {
    // y is the byte pattern 0F F0 0F F0 ..., x is F0 repeated, so each result
    // below can be read off one byte at a time.
    val x = 0xf0f0f0f0f0f0f0f0L
    val y = 0x0ff00ff00ff00ff0L
    checkRegCases(Seq(
      RegCase("and", _.and(_, _, _), x, y, 0x00f000f000f000f0L),
      RegCase("or", _.or(_, _, _), x, y, 0xfff0fff0fff0fff0L),
      RegCase("xor", _.xor(_, _, _), x, y, 0xff00ff00ff00ff00L),
      // ANDN is rn & ~rm, not ~rn & rm. Getting the operand order backwards
      // is the classic way to implement this one wrong.
      RegCase("andn is rn and not rm", _.andn(_, _, _), x, y, 0xf000f000f000f000L),
      RegCase("andn the other way round", _.andn(_, _, _), y, x, 0x0f000f000f000f00L),
      RegCase("orn", _.orn(_, _, _), x, y, 0xf0fff0fff0fff0ffL),
      RegCase("xnor", _.xnor(_, _, _), x, y, 0x00ff00ff00ff00ffL),
      RegCase("xnor of equal operands is all ones", _.xnor(_, _, _), X, X, ONES),
      RegCase("andn by all ones is zero", _.andn(_, _, _), X, ONES, 0)
    ))
  }

  test("ALU register: 64-bit shifts take the amount from rm(5 downto 0)") {
    checkRegCases(Seq(
      RegCase("shl by 0", _.shl(_, _, _), X, 0, X),
      RegCase("shl by 4", _.shl(_, _, _), X, 4, 0x123456789abcdef0L),
      RegCase("shl by 31", _.shl(_, _, _), 1, 31, 0x0000000080000000L),
      RegCase("shl by 32", _.shl(_, _, _), 1, 32, 0x0000000100000000L),
      RegCase("shl by 63", _.shl(_, _, _), 1, 63, MIN),
      // Six bits of amount: 64 is zero again, not a register clear.
      RegCase("shl by 64 is shl by 0", _.shl(_, _, _), 1, 64, 1),
      RegCase("shl by 65 is shl by 1", _.shl(_, _, _), 1, 65, 2),
      RegCase("shr is logical", _.shr(_, _, _), MIN, 4, 0x0800000000000000L),
      RegCase("shr by 63", _.shr(_, _, _), MIN, 63, 1),
      RegCase("shr by 32", _.shr(_, _, _), ONES, 32, 0x00000000ffffffffL),
      RegCase("shr by 64 is shr by 0", _.shr(_, _, _), MIN, 64, MIN),
      RegCase("sar is arithmetic", _.sar(_, _, _), MIN, 4, 0xf800000000000000L),
      RegCase("sar by 63 of a negative", _.sar(_, _, _), MIN, 63, ONES),
      RegCase("sar of all ones stays all ones", _.sar(_, _, _), ONES, 63, ONES),
      RegCase("sar by 64 is sar by 0", _.sar(_, _, _), MIN, 64, MIN),
      RegCase("sar of a positive", _.sar(_, _, _), MAX, 62, 1),
      RegCase("ror by 8 moves the bottom byte to the top", _.ror(_, _, _), X, 8, 0xef0123456789abcdL),
      RegCase("ror by 0 is the identity", _.ror(_, _, _), X, 0, X),
      RegCase("ror by 64 is the identity", _.ror(_, _, _), X, 64, X),
      RegCase("ror by 32 swaps the halves", _.ror(_, _, _), X, 32, 0x89abcdef01234567L),
      RegCase("ror by 1", _.ror(_, _, _), 1, 1, MIN),
      RegCase("ror by 63 is rotate left by 1", _.ror(_, _, _), 1, 63, 2)
    ))
  }

  test("ALU register: signed and unsigned comparison") {
    checkRegCases(Seq(
      RegCase("slt is signed", _.slt(_, _, _), -1, 1, 1),
      RegCase("slt the other way", _.slt(_, _, _), 1, -1, 0),
      RegCase("slt on equal operands", _.slt(_, _, _), 42, 42, 0),
      RegCase("slt at the signed extremes", _.slt(_, _, _), MIN, MAX, 1),
      RegCase("sltu is unsigned", _.sltu(_, _, _), -1, 1, 0),
      RegCase("sltu the other way", _.sltu(_, _, _), 1, -1, 1),
      RegCase("sltu on equal operands", _.sltu(_, _, _), 42, 42, 0),
      // Unsigned, the sign bit alone is larger than every positive value.
      RegCase("sltu at the signed extremes", _.sltu(_, _, _), MIN, MAX, 0)
    ))
  }

  test("ALU register: min and max, signed and unsigned, on mixed signs") {
    checkRegCases(Seq(
      RegCase("min picks the negative", _.min(_, _, _), -1, 1, -1),
      RegCase("min is order independent", _.min(_, _, _), 1, -1, -1),
      RegCase("max picks the positive", _.max(_, _, _), -1, 1, 1),
      RegCase("max on equal operands", _.max(_, _, _), 7, 7, 7),
      RegCase("min at the signed extremes", _.min(_, _, _), MIN, MAX, MIN),
      RegCase("max at the signed extremes", _.max(_, _, _), MIN, MAX, MAX),
      // The same four pairs read as unsigned give the opposite answers, which
      // is exactly what separates MIN from MINU.
      RegCase("minu picks the small unsigned value", _.minu(_, _, _), -1, 1, 1),
      RegCase("maxu picks all ones", _.maxu(_, _, _), -1, 1, ONES),
      RegCase("minu at the signed extremes", _.minu(_, _, _), MIN, MAX, MAX),
      RegCase("maxu at the signed extremes", _.maxu(_, _, _), MIN, MAX, MIN)
    ))
  }

  test("ALU register: multiply, and the two high halves") {
    checkRegCases(Seq(
      RegCase("mul", _.mul(_, _, _), 1000, 1000, 1000000),
      RegCase("mul of two negatives", _.mul(_, _, _), -3, -4, 12),
      RegCase("mul keeps only the low half", _.mul(_, _, _), MIN, 2, 0),
      // (2^64 - 1)^2 = 2^128 - 2^65 + 1, whose low half is 1.
      RegCase("mul of all ones squared", _.mul(_, _, _), ONES, ONES, 1),
      RegCase("mul by zero", _.mul(_, _, _), X, 0, 0),
      // Signed: -1 times -1 is 1, so the high half is zero.
      RegCase("mulh of -1 squared", _.mulh(_, _, _), -1, -1, 0),
      // Unsigned the same operands are 2^64 - 1 each, and the high half of
      // 2^128 - 2^65 + 1 is 2^64 - 2.
      RegCase("mulhu of -1 squared", _.mulhu(_, _, _), -1, -1, 0xfffffffffffffffeL),
      RegCase("mulh of -1 by 1 is all ones", _.mulh(_, _, _), -1, 1, ONES),
      RegCase("mulhu of -1 by 1 is zero", _.mulhu(_, _, _), -1, 1, 0),
      // -2^63 times 2 is -2^64, whose high half is -1 and low half zero.
      RegCase("mulh of the sign bit by two", _.mulh(_, _, _), MIN, 2, ONES),
      RegCase("mulhu of the sign bit by two", _.mulhu(_, _, _), MIN, 2, 1),
      // 2^32 squared is 2^64 exactly.
      RegCase("mulh of 2^32 squared", _.mulh(_, _, _), 0x100000000L, 0x100000000L, 1),
      RegCase("mulh of small operands", _.mulh(_, _, _), 2, 3, 0),
      // (-2^63)^2 = 2^126, and 2^126 >> 64 is 2^62.
      RegCase("mulh of the sign bit squared", _.mulh(_, _, _), MIN, MIN, 0x4000000000000000L),
      RegCase("mulhu of the sign bit squared", _.mulhu(_, _, _), MIN, MIN, 0x4000000000000000L)
    ))
  }

  test("ALU register: the W forms compute in 32 bits and sign extend") {
    checkRegCases(Seq(
      // 0x7fffffff + 1 is INT_MIN, which sign extends to a register full of
      // ones in the top half. A W form that failed to extend would leave
      // 0x0000000080000000 here.
      RegCase("addw overflows into a negative", _.addw(_, _, _), 0x7fffffffL, 1, 0xffffffff80000000L),
      // The high halves of both operands are ignored entirely.
      RegCase("addw ignores the high halves", _.addw(_, _, _),
        0x1234567800000005L, 0xffffffff0000000bL, 0x10),
      RegCase("addw of two negatives", _.addw(_, _, _), -1, -1, 0xfffffffffffffffeL),
      RegCase("subw", _.subw(_, _, _), 0, 1, ONES),
      RegCase("subw underflows into a positive", _.subw(_, _, _), 0x80000000L, 1, 0x7fffffffL),
      RegCase("mulw drops the carry out of 32 bits", _.mulw(_, _, _), 0x10000L, 0x10000L, 0),
      RegCase("mulw of a large positive", _.mulw(_, _, _), 0x7fffffffL, 2, 0xfffffffffffffffeL),
      RegCase("mulw of two negatives", _.mulw(_, _, _), -1, -1, 1),
      // W shifts take five bits of amount, not six.
      RegCase("shlw by 31", _.shlw(_, _, _), 1, 31, 0xffffffff80000000L),
      RegCase("shlw by 32 is shlw by 0", _.shlw(_, _, _), 1, 32, 1),
      RegCase("shlw by 0 still sign extends the low half", _.shlw(_, _, _),
        0x123456789abcdef0L, 0, 0xffffffff9abcdef0L),
      RegCase("shrw is logical within 32 bits", _.shrw(_, _, _), 0x80000000L, 4, 0x08000000L),
      RegCase("shrw by 31", _.shrw(_, _, _), 0x80000000L, 31, 1),
      RegCase("shrw by 32 is shrw by 0", _.shrw(_, _, _), 0x80000000L, 32, 0xffffffff80000000L),
      RegCase("shrw of all ones", _.shrw(_, _, _), ONES, 0, ONES),
      RegCase("sarw is arithmetic within 32 bits", _.sarw(_, _, _), 0x80000000L, 4, 0xfffffffff8000000L),
      RegCase("sarw by 31 of a negative", _.sarw(_, _, _), 0x80000000L, 31, ONES),
      RegCase("sarw by 31 of a positive", _.sarw(_, _, _), 0x7fffffffL, 31, 0),
      RegCase("rorw rotates within 32 bits", _.rorw(_, _, _), 0x12345678L, 8, 0x78123456L),
      RegCase("rorw by 32 is rorw by 0", _.rorw(_, _, _), 0x12345678L, 32, 0x12345678L),
      RegCase("rorw can produce a negative", _.rorw(_, _, _), 0xffffL, 16, 0xffffffffffff0000L),
      RegCase("rorw by 1", _.rorw(_, _, _), 1, 1, 0xffffffff80000000L)
    ))
  }

  // =====================================================================
  // Opcode 0x01: the eight shift-immediate forms
  // =====================================================================

  test("shift immediate: all eight functions") {
    checkShiftCases(Seq(
      ShiftCase("shli by 4", _.shli(_, _, _), X, 4, 0x123456789abcdef0L),
      ShiftCase("shli by 0", _.shli(_, _, _), X, 0, X),
      ShiftCase("shli by 63", _.shli(_, _, _), 1, 63, MIN),
      ShiftCase("shri by 63", _.shri(_, _, _), MIN, 63, 1),
      ShiftCase("shri by 0", _.shri(_, _, _), X, 0, X),
      ShiftCase("shri by 4", _.shri(_, _, _), MIN, 4, 0x0800000000000000L),
      ShiftCase("sari by 63", _.sari(_, _, _), MIN, 63, ONES),
      ShiftCase("sari by 4", _.sari(_, _, _), MIN, 4, 0xf800000000000000L),
      ShiftCase("sari by 0", _.sari(_, _, _), MIN, 0, MIN),
      ShiftCase("rori by 8", _.rori(_, _, _), X, 8, 0xef0123456789abcdL),
      ShiftCase("rori by 0", _.rori(_, _, _), X, 0, X),
      ShiftCase("rori by 63", _.rori(_, _, _), 1, 63, 2),
      ShiftCase("shlwi by 31", _.shlwi(_, _, _), 1, 31, 0xffffffff80000000L),
      ShiftCase("shlwi by 0 sign extends the low half", _.shlwi(_, _, _),
        0x123456789abcdef0L, 0, 0xffffffff9abcdef0L),
      ShiftCase("shrwi by 31", _.shrwi(_, _, _), 0x80000000L, 31, 1),
      ShiftCase("shrwi by 0 of all ones", _.shrwi(_, _, _), ONES, 0, ONES),
      ShiftCase("sarwi by 31", _.sarwi(_, _, _), 0x80000000L, 31, ONES),
      ShiftCase("sarwi by 4", _.sarwi(_, _, _), 0x80000000L, 4, 0xfffffffff8000000L),
      ShiftCase("rorwi by 8", _.rorwi(_, _, _), 0x12345678L, 8, 0x78123456L),
      ShiftCase("rorwi by 16", _.rorwi(_, _, _), 0xffffL, 16, 0xffffffffffff0000L)
    ))
  }

  // =====================================================================
  // Opcodes 0x02 to 0x07: immediate ALU, sign extended immediates
  // =====================================================================

  test("immediate ALU: the immediate is sign extended, not zero extended") {
    checkImmCases(Seq(
      ImmCase("addi", _.addi(_, _, _), 10, -1, 9),
      ImmCase("addi at the top of the field", _.addi(_, _, _), 0, 0x7fff, 0x7fff),
      ImmCase("addi at the bottom of the field", _.addi(_, _, _), 0, -0x8000, -32768),
      ImmCase("addi wraps", _.addi(_, _, _), MAX, 1, MIN),
      // andi with -1 is the identity precisely because the immediate is sign
      // extended to all ones. Zero extension would leave only the low sixteen
      // bits and this case would fail loudly.
      ImmCase("andi by -1 is the identity", _.andi(_, _, _), X, -1, X),
      ImmCase("andi keeps the low byte", _.andi(_, _, _), X, 0xff, 0xef),
      ImmCase("andi by -256 clears the low byte", _.andi(_, _, _), X, -256, 0x0123456789abcd00L),
      ImmCase("ori by -1 is all ones", _.ori(_, _, _), X, -1, ONES),
      ImmCase("ori", _.ori(_, _, _), 0, 0x7fff, 0x7fff),
      ImmCase("ori by -256 sets every high bit", _.ori(_, _, _), 0, -256, 0xffffffffffffff00L),
      ImmCase("xori by -1 is a bitwise not", _.xori(_, _, _), X, -1, 0xfedcba9876543210L),
      ImmCase("xori", _.xori(_, _, _), 0, 0x1234, 0x1234),
      ImmCase("slti is signed", _.slti(_, _, _), -2, -1, 1),
      ImmCase("slti the other way", _.slti(_, _, _), 0, -1, 0),
      ImmCase("slti on equal operands", _.slti(_, _, _), 5, 5, 0),
      ImmCase("slti against a positive", _.slti(_, _, _), 0, 1, 1),
      // The immediate is sign extended first and only then read as unsigned,
      // so -1 here is the largest unsigned value there is.
      ImmCase("sltui compares against all ones", _.sltui(_, _, _), 0, -1, 1),
      ImmCase("sltui of all ones against all ones", _.sltui(_, _, _), -1, -1, 0),
      ImmCase("sltui just below all ones", _.sltui(_, _, _), -2, -1, 1),
      ImmCase("sltui on equal small operands", _.sltui(_, _, _), 1, 1, 0)
    ))
  }

  // =====================================================================
  // Opcodes 0x08 to 0x0a: constant formation
  // =====================================================================

  test("MOVZ and MOVN place a lane and fill the rest") {
    val r = cosim() { a =>
      import a._
      movz(a0, 0xbeef, 0)
      movz(a1, 0xbeef, 1)
      movz(a2, 0xbeef, 2)
      movz(a3, 0xbeef, 3)
      movn(a4, 0xbeef, 0)
      movn(a5, 0xbeef, 2)
      movz(a6, 0, 3)
      movn(t0, 0, 0)
      halt()
    }
    expectReg(r, 1, 0x000000000000beefL, "lane 0")
    expectReg(r, 2, 0x00000000beef0000L, "lane 1")
    expectReg(r, 3, 0x0000beef00000000L, "lane 2")
    expectReg(r, 4, 0xbeef000000000000L, "lane 3")
    // MOVN is the ones complement of the whole shifted pattern, so the three
    // lanes it does not name come out as all ones rather than as zero.
    expectReg(r, 5, 0xffffffffffff4110L, "not 0x000000000000beef")
    expectReg(r, 6, 0xffff4110ffffffffL, "not 0x0000beef00000000")
    expectReg(r, 7, 0, "movz of zero")
    expectReg(r, 8, ONES, "movn of zero is all ones")
  }

  test("MOVK replaces one lane and preserves the other three") {
    val r = cosim() { a =>
      import a._
      li(a0, 0x1111222233334444L); movk(a0, 0xabcd, 0)
      li(a1, 0x1111222233334444L); movk(a1, 0xabcd, 1)
      li(a2, 0x1111222233334444L); movk(a2, 0xabcd, 2)
      li(a3, 0x1111222233334444L); movk(a3, 0xabcd, 3)
      // Writing zero into a lane must clear it, which only works if MOVK
      // masks the lane out before merging rather than just OR-ing.
      li(a4, ONES); movk(a4, 0, 2)
      halt()
    }
    expectReg(r, 1, 0x111122223333abcdL, "lane 0")
    expectReg(r, 2, 0x11112222abcd4444L, "lane 1")
    expectReg(r, 3, 0x1111abcd33334444L, "lane 2")
    expectReg(r, 4, 0xabcd222233334444L, "lane 3")
    expectReg(r, 5, 0xffff0000ffffffffL, "a zero lane must be cleared, not merged")
  }

  test("li builds awkward constants out of MOVZ, MOVN and MOVK") {
    val constants = Seq(
      0L, -1L, MIN, MAX, 1L, 0xffffL, 0x10000L,
      0x0123456789abcdefL, 0xffff0000ffff0000L, 0x0000ffff0000ffffL,
      0xffffffffffff0001L, 0x8000000000000001L, 0xdeadbeefcafebabeL)
    val r = cosim(readRange = DataWord until (DataWord + constants.length)) { a =>
      import a._
      li(s0, DataByte)
      for ((v, i) <- constants.zipWithIndex) {
        li(t0, v)
        std(t0, s0, i * 8)
      }
      halt()
    }
    for ((v, i) <- constants.zipWithIndex) {
      assert(r.word(DataWord + i) == v, f"li of 0x$v%016x produced 0x${r.word(DataWord + i)}%016x")
    }
    // The sequence length is part of the contract of section 7: no constant
    // may need a fifth instruction, and li must agree with its own estimate.
    val asm = new Assembler(0)
    for (v <- constants) {
      val before = asm.assemble().length
      asm.li(1, v)
      val used = asm.assemble().length - before
      assert(used == asm.liSize(v), f"li of 0x$v%016x used $used words, liSize said ${asm.liSize(v)}")
      assert(used <= 4, f"li of 0x$v%016x needed $used instructions")
    }
  }

  // =====================================================================
  // Opcode 0x0b: ADDPC
  // =====================================================================

  test("ADDPC reads the address of its own instruction") {
    var first = 0L
    var second = 0L
    var third = 0L
    val r = cosim() { a =>
      import a._
      li(t0, 0) // push ADDPC away from address zero so a stuck-at-zero shows
      first = a.pc; addpc(a0, 0)
      second = a.pc; addpc(a1, 16)
      third = a.pc; addpc(a2, -8)
      halt()
    }
    expectReg(r, 1, first, "addpc with a zero displacement is the instruction's own address")
    expectReg(r, 2, second + 16, "a positive displacement")
    expectReg(r, 3, third - 8, "a negative displacement, proving imm21 is sign extended")
  }

  // =====================================================================
  // x0
  // =====================================================================

  test("x0 reads as zero and discards every write") {
    val r = cosim(data = Map(DataWord -> 0x7777777777777777L)) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x1234)
      add(zero, t0, t0)     // discarded
      movz(zero, 0xffff, 3) // discarded
      ldd(zero, s0, 0)      // a load into x0 is discarded too
      mov(a0, zero)
      addi(a1, zero, 5)
      add(a2, zero, zero)
      sub(a3, zero, t0)
      halt()
    }
    expectReg(r, 1, 0, "x0 still reads as zero after three writes to it")
    expectReg(r, 2, 5)
    expectReg(r, 3, 0)
    expectReg(r, 4, -0x1234, "neg through x0")
  }

  // =====================================================================
  // Opcodes 0x0c and 0x0d: the ten condition codes
  // =====================================================================

  private case class CcCase(cc: Int, x: Long, y: Long, expected: Boolean) {
    def name: String = s"cmp.${Isa.Cc.NAMES(cc)} of $x and $y"
  }

  /** Every condition code, each shown both true and false.
    *
    * Each case materialises its predicate into a register with SEL, so a
    * condition that never writes its predicate at all cannot pass by leaving a
    * stale value behind: the previous case's value would be the wrong one for
    * at least one of the two senses.
    */
  private val ccCases: Seq[CcCase] = Seq(
    CcCase(Isa.Cc.EQ, 5, 5, true), CcCase(Isa.Cc.EQ, 5, 6, false),
    CcCase(Isa.Cc.NE, 5, 6, true), CcCase(Isa.Cc.NE, 5, 5, false),
    CcCase(Isa.Cc.LT, -1, 1, true), CcCase(Isa.Cc.LT, 1, -1, false),
    CcCase(Isa.Cc.GE, 1, -1, true), CcCase(Isa.Cc.GE, -1, 1, false),
    CcCase(Isa.Cc.LTU, 1, -1, true), CcCase(Isa.Cc.LTU, -1, 1, false),
    CcCase(Isa.Cc.GEU, -1, 1, true), CcCase(Isa.Cc.GEU, 1, -1, false),
    CcCase(Isa.Cc.LE, -1, -1, true), CcCase(Isa.Cc.LE, 1, -1, false),
    CcCase(Isa.Cc.GT, 1, -1, true), CcCase(Isa.Cc.GT, -1, 1, false),
    CcCase(Isa.Cc.LEU, 1, -1, true), CcCase(Isa.Cc.LEU, -1, 1, false),
    CcCase(Isa.Cc.GTU, -1, 1, true), CcCase(Isa.Cc.GTU, 1, -1, false)
  )

  test("CMP: all ten condition codes, each proven both ways") {
    val r = cosim(readRange = DataWord until (DataWord + ccCases.length)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 1)
      for ((c, i) <- ccCases.zipWithIndex) {
        li(t0, c.x)
        li(t1, c.y)
        cmp(c.cc, p0, t0, t1)
        sel(t2, s1, zero, p0)
        std(t2, s0, i * 8)
      }
      halt()
    }
    for ((c, i) <- ccCases.zipWithIndex) {
      val got = r.word(DataWord + i)
      assert(got == (if (c.expected) 1L else 0L), s"${c.name} gave $got")
    }
  }

  test("CMPI: all ten condition codes against a sign extended imm12") {
    // The immediates here are deliberately negative for the unsigned codes, so
    // that a CMPI that zero extended its immediate would compare against 4095
    // instead of against all ones and give the opposite answer.
    val cases = Seq(
      (Isa.Cc.EQ, 5L, 5, true), (Isa.Cc.EQ, 5L, 6, false),
      (Isa.Cc.NE, 5L, 6, true), (Isa.Cc.NE, 5L, 5, false),
      (Isa.Cc.LT, -1L, 0, true), (Isa.Cc.LT, 0L, -1, false),
      (Isa.Cc.GE, 0L, -1, true), (Isa.Cc.GE, -1L, 0, false),
      (Isa.Cc.LTU, 1L, -1, true), (Isa.Cc.LTU, -1L, 1, false),
      (Isa.Cc.GEU, -1L, 1, true), (Isa.Cc.GEU, 1L, -1, false),
      (Isa.Cc.LE, -1L, -1, true), (Isa.Cc.LE, 0L, -1, false),
      (Isa.Cc.GT, 0L, -1, true), (Isa.Cc.GT, -1L, 0, false),
      (Isa.Cc.LEU, 1L, -1, true), (Isa.Cc.LEU, -1L, 1, false),
      (Isa.Cc.GTU, -1L, 1, true), (Isa.Cc.GTU, 1L, -1, false)
    )
    val r = cosim(readRange = DataWord until (DataWord + cases.length)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 1)
      for (((cc, x, imm, _), i) <- cases.zipWithIndex) {
        li(t0, x)
        cmpi(cc, p1, t0, imm)
        sel(t2, s1, zero, p1)
        std(t2, s0, i * 8)
      }
      halt()
    }
    for (((cc, x, imm, expected), i) <- cases.zipWithIndex) {
      val got = r.word(DataWord + i)
      assert(got == (if (expected) 1L else 0L),
        s"cmpi.${Isa.Cc.NAMES(cc)} of $x and $imm gave $got")
    }
  }

  test("all eight predicate registers are independent") {
    val r = cosim() { a =>
      import a._
      li(s1, 1)
      // p0, p2, p4, p6 true and the rest false.
      for (p <- 0 until Isa.PRED_COUNT) cmpi(if (p % 2 == 0) Isa.Cc.EQ else Isa.Cc.NE, p, zero, 0)
      for (p <- 0 until Isa.PRED_COUNT) sel(1 + p, s1, zero, p)
      halt()
    }
    for (p <- 0 until Isa.PRED_COUNT) {
      expectReg(r, 1 + p, if (p % 2 == 0) 1L else 0L, s"p$p")
      assert(r.predicate(p) == (p % 2 == 0), s"p$p read back wrong")
    }
  }

  // =====================================================================
  // Opcode 0x0e: SEL
  // =====================================================================

  test("SEL picks rn or rm according to the predicate and the sense bit") {
    val r = cosim() { a =>
      import a._
      li(t0, 0x1111)
      li(t1, 0x2222)
      cmpEqi(p3, zero, 0) // p3 is true
      cmpNei(p4, zero, 0) // p4 is false
      sel(a0, t0, t1, p3)               // true, straight sense
      sel(a1, t0, t1, p3, invert = true) // true, inverted
      sel(a2, t0, t1, p4)               // false, straight sense
      sel(a3, t0, t1, p4, invert = true) // false, inverted
      halt()
    }
    expectReg(r, 1, 0x1111, "p3 true selects rn")
    expectReg(r, 2, 0x2222, "p3 true with the sense bit selects rm")
    expectReg(r, 3, 0x2222, "p4 false selects rm")
    expectReg(r, 4, 0x1111, "p4 false with the sense bit selects rn")
  }

  // =====================================================================
  // Opcodes 0x10 to 0x1b: loads and stores
  // =====================================================================

  /** The byte lanes of this pattern are all distinct, so a load from the wrong
    * lane cannot accidentally give the right answer. Little endian means the
    * byte at the lowest address is 0xef.
    */
  private val Pattern = 0x0123456789abcdefL

  test("loads: every size, every lane, signed and unsigned") {
    val r = cosim(data = Map(DataWord -> Pattern)) { a =>
      import a._
      li(s0, DataByte)
      ldbu(a0, s0, 0); ldb(a1, s0, 0)
      ldbu(a2, s0, 3); ldb(a3, s0, 3)
      ldbu(a4, s0, 7); ldb(a5, s0, 7)
      ldhu(a6, s0, 0); ldh(t0, s0, 0)
      ldhu(t1, s0, 2); ldh(t2, s0, 2)
      ldhu(t3, s0, 6); ldh(t4, s0, 6)
      ldwu(t5, s0, 0); ldw(t6, s0, 0)
      ldwu(t7, s0, 4); ldw(t8, s0, 4)
      ldd(t9, s0, 0)
      halt()
    }
    expectReg(r, 1, 0xefL, "byte 0 zero extended")
    expectReg(r, 2, 0xffffffffffffffefL, "byte 0 sign extended")
    expectReg(r, 3, 0x89L, "byte 3 zero extended")
    expectReg(r, 4, 0xffffffffffffff89L, "byte 3 sign extended")
    expectReg(r, 5, 0x01L, "byte 7, the top lane")
    expectReg(r, 6, 0x01L, "byte 7 is positive so both forms agree")
    expectReg(r, 7, 0xcdefL, "halfword 0 zero extended")
    expectReg(r, 8, 0xffffffffffffcdefL, "halfword 0 sign extended")
    expectReg(r, 9, 0x89abL, "halfword at byte 2")
    expectReg(r, 10, 0xffffffffffff89abL, "halfword at byte 2 sign extended")
    expectReg(r, 11, 0x0123L, "halfword at byte 6")
    expectReg(r, 12, 0x0123L, "positive halfword, both forms agree")
    expectReg(r, 13, 0x89abcdefL, "word 0 zero extended")
    expectReg(r, 14, 0xffffffff89abcdefL, "word 0 sign extended")
    expectReg(r, 15, 0x01234567L, "word at byte 4")
    expectReg(r, 16, 0x01234567L, "positive word, both forms agree")
    expectReg(r, 17, Pattern, "the whole doubleword")
  }

  test("loads: a negative displacement reaches backwards") {
    val r = cosim(data = Map(DataWord -> Pattern, (DataWord + 1) -> 0x1122334455667788L)) { a =>
      import a._
      li(s0, DataByte + 8)
      ldd(a0, s0, -8)
      ldd(a1, s0, 0)
      ldbu(a2, s0, -8)   // the lowest byte of the doubleword below
      ldw(a3, s0, -4)    // the top word of the doubleword below
      halt()
    }
    expectReg(r, 1, Pattern)
    expectReg(r, 2, 0x1122334455667788L)
    expectReg(r, 3, 0xefL)
    expectReg(r, 4, 0x01234567L)
  }

  test("stores: a narrow store changes only its own bytes") {
    // Both neighbouring doublewords start as all ones, so any byte the store
    // touches that it should not is immediately visible as a zero nibble.
    val r = cosim(
      data = Map(DataWord -> ONES, (DataWord + 1) -> ONES, (DataWord + 2) -> ONES,
        (DataWord + 3) -> ONES),
      readRange = DataWord until (DataWord + 4)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x0011223344556677L)
      stb(t0, s0, 2)   // byte lane 2 of the first doubleword
      sth(t0, s0, 12)  // halfword at byte 4 of the second
      stw(t0, s0, 16)  // word 0 of the third
      std(t0, s0, 24)
      halt()
    }
    // A store takes the low bytes of its data register, little endian.
    assert(r.word(DataWord) == 0xffffffffff77ffffL, f"stb gave 0x${r.word(DataWord)}%016x")
    assert(r.word(DataWord + 1) == 0xffff6677ffffffffL, f"sth gave 0x${r.word(DataWord + 1)}%016x")
    assert(r.word(DataWord + 2) == 0xffffffff44556677L, f"stw gave 0x${r.word(DataWord + 2)}%016x")
    assert(r.word(DataWord + 3) == 0x0011223344556677L, f"std gave 0x${r.word(DataWord + 3)}%016x")
  }

  test("stores: every byte lane of a doubleword can be written alone") {
    val r = cosim(data = Map(DataWord -> 0L), readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(s0, DataByte)
      for (lane <- 0 until 8) {
        li(t0, 0x10 + lane)
        stb(t0, s0, lane)
      }
      halt()
    }
    assert(r.word(DataWord) == 0x1716151413121110L,
      f"lane-by-lane store gave 0x${r.word(DataWord)}%016x")
  }

  test("stores and loads round trip at every size") {
    val r = cosim(data = Map(DataWord -> 0L), readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(s0, DataByte)
      li(t0, Pattern)
      std(t0, s0, 0)
      ldd(a0, s0, 0)
      li(t1, 0x5a5a)
      sth(t1, s0, 2)
      ldhu(a1, s0, 2)
      ldd(a2, s0, 0)
      halt()
    }
    expectReg(r, 1, Pattern)
    expectReg(r, 2, 0x5a5aL)
    expectReg(r, 3, 0x012345675a5acdefL, "the halfword store left the other six bytes alone")
  }

  // =====================================================================
  // Addressing modes
  // =====================================================================

  test("addressing modes: offset leaves the base alone, pre-index writes the address") {
    val data = Map(DataWord -> 0xaaaaL, (DataWord + 1) -> 0xbbbbL, (DataWord + 2) -> 0xccccL)
    val r = cosim(data = data) { a =>
      import a._
      li(s0, DataByte)
      ldd(a0, s0, 16)                    // offset mode reads DataWord + 2
      mov(a1, s0)                        // base must still be DataByte
      li(s1, DataByte)
      ldd(a2, s1, 8, Isa.Mode.PRE)       // reads DataWord + 1
      mov(a3, s1)                        // base becomes the computed address
      li(s2, DataByte + 16)
      ldd(a4, s2, -16, Isa.Mode.PRE)     // a negative pre-index
      mov(a5, s2)
      halt()
    }
    expectReg(r, 1, 0xccccL)
    expectReg(r, 2, DataByte, "offset mode must not touch the base")
    expectReg(r, 3, 0xbbbbL)
    expectReg(r, 4, DataByte + 8, "pre-index writes the computed address")
    expectReg(r, 5, 0xaaaaL)
    expectReg(r, 6, DataByte, "pre-index with a negative displacement")
  }

  test("addressing modes: post-index accesses the base and writes base plus imm") {
    val data = Map(DataWord -> 0xaaaaL, (DataWord + 1) -> 0xbbbbL, (DataWord + 2) -> 0xccccL)
    val r = cosim(data = data) { a =>
      import a._
      li(s0, DataByte)
      ldd(a0, s0, 8, Isa.Mode.POST) // reads DataWord, then base becomes DataByte + 8
      mov(a1, s0)
      ldd(a2, s0, 8, Isa.Mode.POST) // reads DataWord + 1
      mov(a3, s0)
      ldd(a4, s0, -8, Isa.Mode.POST) // reads DataWord + 2, base walks back
      mov(a5, s0)
      halt()
    }
    expectReg(r, 1, 0xaaaaL, "post-index accesses the base itself, not base plus imm")
    expectReg(r, 2, DataByte + 8)
    expectReg(r, 3, 0xbbbbL)
    expectReg(r, 4, DataByte + 16)
    expectReg(r, 5, 0xccccL)
    expectReg(r, 6, DataByte + 8)
  }

  test("addressing modes: indexed stores update the base too") {
    val r = cosim(
      data = Map(DataWord -> 0L, (DataWord + 1) -> 0L, (DataWord + 2) -> 0L),
      readRange = DataWord until (DataWord + 3)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x1111)
      std(t0, s0, 8, Isa.Mode.PRE)   // writes DataWord + 1, base becomes DataByte + 8
      mov(a0, s0)
      li(t0, 0x2222)
      std(t0, s0, 8, Isa.Mode.POST)  // writes DataWord + 1 again, base moves on
      mov(a1, s0)
      li(t0, 0x3333)
      std(t0, s0, 0)                 // plain offset at DataWord + 2
      mov(a2, s0)
      halt()
    }
    expectReg(r, 1, DataByte + 8)
    expectReg(r, 2, DataByte + 16)
    expectReg(r, 3, DataByte + 16, "offset mode leaves the base where post-index put it")
    assert(r.word(DataWord) == 0, "nothing should have been written to the first doubleword")
    assert(r.word(DataWord + 1) == 0x2222, "the post-index store overwrote the pre-index one")
    assert(r.word(DataWord + 2) == 0x3333)
  }

  // =====================================================================
  // Opcodes 0x1c and 0x1d: load and store pair
  // =====================================================================

  test("LDP and STP move two doublewords, with a displacement and with indexing") {
    val data = Map(
      DataWord -> 0x1111111111111111L,
      (DataWord + 1) -> 0x2222222222222222L,
      (DataWord + 2) -> 0x3333333333333333L,
      (DataWord + 3) -> 0x4444444444444444L)
    val r = cosim(data = data, readRange = (DataWord + 8) until (DataWord + 12)) { a =>
      import a._
      li(s0, DataByte)
      ldp(a0, a1, s0, 0)               // the pair at DataWord and DataWord + 1
      ldp(a2, a3, s0, 16)              // a non-zero displacement, scaled by eight
      li(s1, DataByte)
      ldp(a4, a5, s1, 16, Isa.Mode.PRE)
      mov(a6, s1)                      // pre-index wrote the computed address
      li(s2, DataByte)
      ldp(t0, t1, s2, 16, Isa.Mode.POST) // accesses the base, then adds
      mov(t2, s2)
      // Store the pair back out somewhere clean and read it from memory.
      li(s3, DataByte + 64)
      stp(a0, a1, s3, 0)
      stp(a2, a3, s3, 16)
      halt()
    }
    expectReg(r, 1, 0x1111111111111111L)
    expectReg(r, 2, 0x2222222222222222L, "rt2 comes from address plus eight")
    expectReg(r, 3, 0x3333333333333333L)
    expectReg(r, 4, 0x4444444444444444L)
    expectReg(r, 5, 0x3333333333333333L)
    expectReg(r, 6, 0x4444444444444444L)
    expectReg(r, 7, DataByte + 16, "pre-index base update")
    expectReg(r, 8, 0x1111111111111111L, "post-index reads the base itself")
    expectReg(r, 9, 0x2222222222222222L)
    expectReg(r, 10, DataByte + 16, "post-index base update")
    assert(r.word(DataWord + 8) == 0x1111111111111111L)
    assert(r.word(DataWord + 9) == 0x2222222222222222L)
    assert(r.word(DataWord + 10) == 0x3333333333333333L)
    assert(r.word(DataWord + 11) == 0x4444444444444444L)
  }

  test("STP with pre and post index walks a stack frame") {
    val r = cosim(readRange = (DataWord + 2) until (DataWord + 6)) { a =>
      import a._
      li(sp, DataByte + 48) // one doubleword past the area written below
      li(t0, 0xaaaa); li(t1, 0xbbbb)
      stp(t0, t1, sp, -16, Isa.Mode.PRE)  // writes DataWord + 4 and + 5
      li(t0, 0xcccc); li(t1, 0xdddd)
      stp(t0, t1, sp, -16, Isa.Mode.PRE)  // writes DataWord + 2 and + 3
      mov(a0, sp)
      ldp(t2, t3, sp, 16, Isa.Mode.POST)  // reads back what was just written
      mov(a1, sp)
      ldp(t4, t5, sp, 0)
      halt()
    }
    expectReg(r, 1, DataByte + 16, "two pre-index pushes of sixteen bytes each")
    expectReg(r, 2, DataByte + 32, "the post-index pop moved sp back up")
    expectReg(r, 10, 0xccccL); expectReg(r, 11, 0xddddL)
    expectReg(r, 12, 0xaaaaL); expectReg(r, 13, 0xbbbbL)
    assert(r.word(DataWord + 2) == 0xcccc)
    assert(r.word(DataWord + 3) == 0xdddd)
    assert(r.word(DataWord + 4) == 0xaaaa)
    assert(r.word(DataWord + 5) == 0xbbbb)
  }

  // =====================================================================
  // Opcodes 0x20 to 0x23: control transfer
  // =====================================================================

  test("B branches forwards and backwards") {
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      b("forward")
      addi(a0, a0, 1)      // skipped
      label("back")
      addi(a0, a0, 10)
      b("done")
      label("forward")
      addi(a0, a0, 100)
      b("back")            // backwards
      label("done")
      halt()
    }
    expectReg(r, 1, 110, "the skipped instruction must not run")
  }

  test("BL writes lr implicitly and RET comes back through it") {
    var callSite = 0L
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      callSite = a.pc
      bl("routine")
      addi(a0, a0, 1)
      halt()

      label("routine")
      mov(a1, lr)
      addi(a0, a0, 10)
      ret()
    }
    expectReg(r, 1, 11, "both the routine and the instruction after the call ran")
    expectReg(r, 2, callSite + 4, "lr is the address of the instruction after the call")
    expectReg(r, 30, callSite + 4, "lr survives the return")
  }

  test("BP branches on either sense of a predicate") {
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      cmpEqi(p2, zero, 0) // p2 true
      cmpNei(p5, zero, 0) // p5 false
      bp(p2, "one")        // taken
      addi(a0, a0, 1)
      label("one")
      bp(p5, "two")        // not taken
      addi(a0, a0, 10)
      label("two")
      bp(p5, "three", invert = true) // taken, inverted sense
      addi(a0, a0, 100)
      label("three")
      bp(p2, "four", invert = true)  // not taken, inverted sense
      addi(a0, a0, 1000)
      label("four")
      halt()
    }
    expectReg(r, 1, 1010, "only the two not-taken branches let their next instruction run")
  }

  test("JALR clears the low two bits of a tagged pointer") {
    var retSite = 0L
    val r = cosim() { a =>
      import a._
      la(t0, "target")
      ori(t0, t0, 3) // tag the pointer, as a language runtime would
      mov(a2, t0)
      retSite = a.pc
      jalr(a1, t0, 0)
      addi(a0, a0, 1) // not reached
      halt()

      label("target")
      li(a0, 42)
      halt()
    }
    expectReg(r, 1, 42, "the tagged pointer still reached the target")
    expectReg(r, 2, retSite + 4, "JALR writes the address after itself")
    assert((r.reg(3) & 3L) == 3L, "the pointer in the register keeps its tag")
  }

  test("JALR adds a sign extended displacement before masking") {
    val r = cosim() { a =>
      import a._
      la(t0, "second")
      jalr(zero, t0, -4) // one instruction earlier than the label
      halt()

      label("first")
      li(a0, 7)
      label("second")
      addi(a0, a0, 1)
      halt()
    }
    expectReg(r, 1, 8, "the negative displacement landed on the li, not on the addi")
  }

  // =====================================================================
  // Opcodes 0x30 and 0x31: ordered accesses
  // =====================================================================

  test("ordered loads zero extend at every size") {
    // Section 4: there is no signed ordered load, because these address a
    // lock, a flag or a reference count. A hardware that reused the signed
    // load path would give 0xffff... here instead of a small positive value.
    val r = cosim(data = Map(DataWord -> ONES)) { a =>
      import a._
      li(s0, DataByte)
      ldOrd(a0, s0, Isa.SIZE_B, Isa.Ord.ACQREL)
      ldOrd(a1, s0, Isa.SIZE_H, Isa.Ord.ACQREL)
      ldOrd(a2, s0, Isa.SIZE_W, Isa.Ord.ACQREL)
      ldOrd(a3, s0, Isa.SIZE_D, Isa.Ord.ACQREL)
      ldOrd(a4, s0, Isa.SIZE_B, Isa.Ord.SEQ)
      ldOrd(a5, s0, Isa.SIZE_W, Isa.Ord.SEQ)
      halt()
    }
    expectReg(r, 1, 0xffL, "an acquire byte load zero extends")
    expectReg(r, 2, 0xffffL)
    expectReg(r, 3, 0xffffffffL)
    expectReg(r, 4, ONES)
    expectReg(r, 5, 0xffL, "a seq_cst byte load zero extends")
    expectReg(r, 6, 0xffffffffL)
  }

  test("ordered stores write exactly their own size") {
    val r = cosim(
      data = Map(DataWord -> 0L, (DataWord + 1) -> 0L, (DataWord + 2) -> 0L, (DataWord + 3) -> 0L),
      readRange = DataWord until (DataWord + 4)
    ) { a =>
      import a._
      li(t0, ONES)
      li(s0, DataByte);      stOrd(t0, s0, Isa.SIZE_B, Isa.Ord.ACQREL)
      li(s1, DataByte + 8);  stOrd(t0, s1, Isa.SIZE_H, Isa.Ord.ACQREL)
      li(s2, DataByte + 16); stOrd(t0, s2, Isa.SIZE_W, Isa.Ord.SEQ)
      li(s3, DataByte + 24); stOrd(t0, s3, Isa.SIZE_D, Isa.Ord.SEQ)
      // Read one of them back through an ordered load, since the two have to
      // agree about which bytes they name.
      ldOrd(a0, s2, Isa.SIZE_D, Isa.Ord.SEQ)
      halt()
    }
    assert(r.word(DataWord) == 0xffL, f"byte store gave 0x${r.word(DataWord)}%016x")
    assert(r.word(DataWord + 1) == 0xffffL)
    assert(r.word(DataWord + 2) == 0xffffffffL)
    assert(r.word(DataWord + 3) == ONES)
    expectReg(r, 1, 0xffffffffL)
  }

  // =====================================================================
  // Opcode 0x32: atomics
  // =====================================================================

  test("every atomic function on a doubleword") {
    // Each case gets its own doubleword, preloaded with the same value, so the
    // old value returned and the value left behind can both be checked.
    val start = 0x00000000000000f0L
    val operand = 0x000000000000000fL
    val ops: Seq[(String, (Assembler, Int, Int, Int) => Unit, Long)] = Seq(
      ("swp", (a, d, n, s) => a.swp(d, n, s), operand),
      ("ldadd", (a, d, n, s) => a.ldadd(d, n, s), start + operand),
      ("ldand", (a, d, n, s) => a.ldand(d, n, s), start & operand),
      ("ldor", (a, d, n, s) => a.ldor(d, n, s), start | operand),
      ("ldxor", (a, d, n, s) => a.ldxor(d, n, s), start ^ operand),
      ("ldmin", (a, d, n, s) => a.ldmin(d, n, s), operand),
      ("ldmax", (a, d, n, s) => a.ldmax(d, n, s), start),
      ("ldminu", (a, d, n, s) => a.ldminu(d, n, s), operand),
      ("ldmaxu", (a, d, n, s) => a.ldmaxu(d, n, s), start)
    )
    val data = ops.indices.map(i => (DataWord + i) -> start).toMap
    val program = Assembler() { asm =>
      import asm._
      li(s1, operand)
      for (((_, emit, _), i) <- ops.zipWithIndex) {
        li(s0, DataByte + i * 8)
        emit(asm, t0, s0, s1)
        std(t0, s0, 64) // park the old value eight doublewords further on
      }
      halt()
    }
    val r = cosimProgram(program, data = data, readRange = DataWord until (DataWord + 24))
    for (((name, _, after), i) <- ops.zipWithIndex) {
      assert(r.word(DataWord + i) == after,
        f"$name%s left 0x${r.word(DataWord + i)}%016x in memory, expected 0x$after%016x")
      assert(r.word(DataWord + i + 8) == start,
        f"$name%s returned 0x${r.word(DataWord + i + 8)}%016x, expected the old value 0x$start%016x")
    }
  }

  test("every atomic function on a word leaves the high word alone") {
    // The same ten functions again at word size. The high half of every
    // doubleword is a marker that no word atomic may disturb, and the old
    // value each one returns is 0xf0, which is positive and so looks the same
    // whether or not the sign extension is working; that case is checked on
    // its own above with a negative word.
    val start = 0xaaaaaaaa000000f0L
    val operand = 0x0fL
    val ops: Seq[(String, (Assembler, Int, Int, Int) => Unit, Long)] = Seq(
      ("swp", (a, d, n, s) => a.swp(d, n, s, Isa.SIZE_W), 0x0fL),
      ("ldadd", (a, d, n, s) => a.ldadd(d, n, s, Isa.SIZE_W), 0xffL),
      ("ldand", (a, d, n, s) => a.ldand(d, n, s, Isa.SIZE_W), 0x00L),
      ("ldor", (a, d, n, s) => a.ldor(d, n, s, Isa.SIZE_W), 0xffL),
      ("ldxor", (a, d, n, s) => a.ldxor(d, n, s, Isa.SIZE_W), 0xffL),
      // rd is preloaded with 0xf0 below, which is what memory holds, so this
      // compare and swap succeeds.
      ("cas", (a, d, n, s) => a.cas(d, n, s, Isa.SIZE_W), 0x0fL),
      ("ldmin", (a, d, n, s) => a.ldmin(d, n, s, Isa.SIZE_W), 0x0fL),
      ("ldmax", (a, d, n, s) => a.ldmax(d, n, s, Isa.SIZE_W), 0xf0L),
      ("ldminu", (a, d, n, s) => a.ldminu(d, n, s, Isa.SIZE_W), 0x0fL),
      ("ldmaxu", (a, d, n, s) => a.ldmaxu(d, n, s, Isa.SIZE_W), 0xf0L)
    )
    val data = ops.indices.map(i => (DataWord + i) -> start).toMap
    val program = Assembler() { asm =>
      import asm._
      li(s1, operand)
      for (((_, emit, _), i) <- ops.zipWithIndex) {
        li(s0, DataByte + i * 8)
        li(t0, 0xf0)
        emit(asm, t0, s0, s1)
        std(t0, s0, 128) // park the old value sixteen doublewords further on
      }
      halt()
    }
    val r = cosimProgram(program, data = data, readRange = DataWord until (DataWord + 32))
    for (((name, _, lowWord), i) <- ops.zipWithIndex) {
      val after = 0xaaaaaaaa00000000L | lowWord
      assert(r.word(DataWord + i) == after,
        f"$name%s left 0x${r.word(DataWord + i)}%016x in memory, expected 0x$after%016x")
      assert(r.word(DataWord + i + 16) == 0xf0L,
        f"$name%s returned 0x${r.word(DataWord + i + 16)}%016x, expected the old word 0xf0")
    }
  }

  test("signed and unsigned atomic min and max disagree on a negative") {
    val r = cosim(
      data = Map(DataWord -> -1L, (DataWord + 1) -> -1L, (DataWord + 2) -> -1L, (DataWord + 3) -> -1L),
      readRange = DataWord until (DataWord + 4)
    ) { a =>
      import a._
      li(s1, 1)
      li(s0, DataByte);      ldmin(a0, s0, s1)
      li(s0, DataByte + 8);  ldmax(a1, s0, s1)
      li(s0, DataByte + 16); ldminu(a2, s0, s1)
      li(s0, DataByte + 24); ldmaxu(a3, s0, s1)
      halt()
    }
    expectReg(r, 1, -1L); expectReg(r, 2, -1L); expectReg(r, 3, -1L); expectReg(r, 4, -1L)
    assert(r.word(DataWord) == -1L, "signed min of -1 and 1 is -1")
    assert(r.word(DataWord + 1) == 1L, "signed max of -1 and 1 is 1")
    assert(r.word(DataWord + 2) == 1L, "unsigned min of all ones and 1 is 1")
    assert(r.word(DataWord + 3) == -1L, "unsigned max of all ones and 1 is all ones")
  }

  test("CAS stores on a match and leaves memory alone on a mismatch") {
    val r = cosim(
      data = Map(DataWord -> 0x1234L, (DataWord + 1) -> 0x1234L),
      readRange = DataWord until (DataWord + 2)
    ) { a =>
      import a._
      li(s1, 0x9999)         // the new value
      li(s0, DataByte)
      li(a0, 0x1234)         // rd holds the expected value and receives the old one
      cas(a0, s0, s1)
      li(s0, DataByte + 8)
      li(a1, 0x4321)         // deliberately not what is in memory
      cas(a1, s0, s1)
      halt()
    }
    expectReg(r, 1, 0x1234L, "a successful CAS still returns the old value")
    expectReg(r, 2, 0x1234L, "a failed CAS returns the value that did not match")
    assert(r.word(DataWord) == 0x9999L, "the matching CAS must store")
    assert(r.word(DataWord + 1) == 0x1234L, "the mismatching CAS must not store")
  }

  test("a word atomic sign extends the old value and works on the low word only") {
    // Section 4: a word-sized atomic sign extends the old value it returns,
    // following the W arithmetic convention, so that an atomic on a 32-bit
    // signed counter compares correctly afterwards.
    val r = cosim(
      data = Map(DataWord -> 0xaaaaaaaa80000000L, (DataWord + 1) -> 0x00000000ffffffffL),
      readRange = DataWord until (DataWord + 2)
    ) { a =>
      import a._
      li(s1, 1)
      li(s0, DataByte)
      ldadd(a0, s0, s1, Isa.SIZE_W)  // old is 0x80000000, which is negative
      ldd(a1, s0, 0)
      li(s0, DataByte + 8)
      swp(a2, s0, s1, Isa.SIZE_W)    // old is 0xffffffff, that is -1
      halt()
    }
    expectReg(r, 1, 0xffffffff80000000L, "the returned word is sign extended")
    expectReg(r, 2, 0xaaaaaaaa80000001L, "only the low word of the doubleword changed")
    expectReg(r, 3, -1L, "a word of all ones comes back as -1")
    assert(r.word(DataWord) == 0xaaaaaaaa80000001L)
    assert(r.word(DataWord + 1) == 0x0000000000000001L, "the swap wrote only the low word")
  }

  test("a word CAS compares only the low word") {
    val r = cosim(
      data = Map(DataWord -> 0x1111111180000000L, (DataWord + 1) -> 0x1111111180000000L),
      readRange = DataWord until (DataWord + 2)
    ) { a =>
      import a._
      li(s1, 0x55)
      li(s0, DataByte)
      // The comparison value is sign extended from its low word too, so a
      // register holding 0xffffffff80000000 matches a memory word 0x80000000.
      li(a0, 0xffffffff80000000L)
      cas(a0, s0, s1, Isa.SIZE_W)
      li(s0, DataByte + 8)
      li(a1, 0x80000001L)
      cas(a1, s0, s1, Isa.SIZE_W)
      halt()
    }
    expectReg(r, 1, 0xffffffff80000000L)
    expectReg(r, 2, 0xffffffff80000000L, "the failed CAS returns the sign extended old word")
    assert(r.word(DataWord) == 0x1111111100000055L, "the matching word CAS stored")
    assert(r.word(DataWord + 1) == 0x1111111180000000L, "the mismatching word CAS did not store")
  }

  test("the four ordering annotations all decode on an atomic") {
    val r = cosim(
      data = (0 until 4).map(i => (DataWord + i) -> 5L).toMap,
      readRange = DataWord until (DataWord + 4)
    ) { a =>
      import a._
      li(s1, 3)
      for (ord <- 0 until 4) {
        li(s0, DataByte + ord * 8)
        atomic(Isa.AtomicFn.ADD, t0, s0, s1, Isa.SIZE_D, ord)
      }
      halt()
    }
    // A single thread cannot observe the ordering, but every annotation still
    // has to be a legal encoding and still has to do the arithmetic.
    for (i <- 0 until 4) assert(r.word(DataWord + i) == 8L, s"ordering $i did not add")
  }

  test("FENCE in all four kinds executes and changes nothing") {
    val r = cosim() { a =>
      import a._
      li(a0, 7)
      fence(Isa.FenceKind.FULL)
      fence(Isa.FenceKind.ACQUIRE)
      fence(Isa.FenceKind.RELEASE)
      fence(Isa.FenceKind.TILE)
      addi(a0, a0, 1)
      halt()
    }
    assert(!r.trapped, s"a fence must not trap: $r")
    expectReg(r, 1, 8)
  }
}
