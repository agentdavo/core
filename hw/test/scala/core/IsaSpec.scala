package core

/** Directed tests for every instruction in CORE-32.
  *
  * Each result is compared both against the reference model (through
  * [[CoreSpec.cosimProgram]]) and against a value worked out by hand from
  * docs/isa.md, so that a shared misreading cannot pass unnoticed.
  */
class IsaSpec extends CoreSpec {

  private case class RegCase(
      name: String,
      op: (Assembler, Int, Int, Int) => Unit,
      a: Int,
      b: Int,
      expected: Int)

  private case class ImmCase(
      name: String,
      op: (Assembler, Int, Int, Int) => Unit,
      a: Int,
      imm: Int,
      expected: Int)

  /** Compute each case into t2 and store it, then check every slot. */
  private def checkRegCases(cases: Seq[RegCase]): Unit = {
    val program = Assembler() { asm =>
      import asm._
      li(gp, DataByte)
      for ((c, i) <- cases.zipWithIndex) {
        li(t0, c.a)
        li(t1, c.b)
        c.op(asm, t2, t0, t1)
        stw(t2, gp, i * 4)
      }
      halt()
    }
    val result = cosimProgram(program, readRange = DataWord until (DataWord + cases.length))
    for ((c, i) <- cases.zipWithIndex) {
      val got = result.word(DataWord + i)
      assert(got == c.expected,
        f"${c.name}: ${c.name} of 0x${c.a}%08x and 0x${c.b}%08x gave 0x$got%08x, expected 0x${c.expected}%08x")
    }
  }

  private def checkImmCases(cases: Seq[ImmCase]): Unit = {
    val program = Assembler() { asm =>
      import asm._
      li(gp, DataByte)
      for ((c, i) <- cases.zipWithIndex) {
        li(t0, c.a)
        c.op(asm, t2, t0, c.imm)
        stw(t2, gp, i * 4)
      }
      halt()
    }
    val result = cosimProgram(program, readRange = DataWord until (DataWord + cases.length))
    for ((c, i) <- cases.zipWithIndex) {
      val got = result.word(DataWord + i)
      assert(got == c.expected,
        f"${c.name}: on 0x${c.a}%08x with immediate ${c.imm} gave 0x$got%08x, expected 0x${c.expected}%08x")
    }
  }

  test("register ALU: arithmetic and logic") {
    checkRegCases(Seq(
      RegCase("add", _.add(_, _, _), 7, 5, 12),
      RegCase("add wraps", _.add(_, _, _), 0x7fffffff, 1, 0x80000000),
      RegCase("sub", _.sub(_, _, _), 7, 5, 2),
      RegCase("sub goes negative", _.sub(_, _, _), 5, 7, -2),
      RegCase("and", _.and(_, _, _), 0xf0f0f0f0, 0x0ff00ff0, 0x00f000f0),
      RegCase("or", _.or(_, _, _), 0xf0f0f0f0, 0x0f0f0f0f, 0xffffffff),
      RegCase("xor", _.xor(_, _, _), 0xaaaaaaaa, 0xffffffff, 0x55555555)
    ))
  }

  test("register ALU: shifts and rotates use only the low five bits") {
    checkRegCases(Seq(
      RegCase("shl", _.shl(_, _, _), 0x12345678, 4, 0x23456780),
      RegCase("shl by 31", _.shl(_, _, _), 1, 31, 0x80000000),
      RegCase("shl by 33 means by 1", _.shl(_, _, _), 0x12345678, 33, 0x2468acf0),
      RegCase("shr is logical", _.shr(_, _, _), 0x80000000, 4, 0x08000000),
      RegCase("shr by 31", _.shr(_, _, _), 0x80000000, 31, 1),
      RegCase("sar is arithmetic", _.sar(_, _, _), 0x80000000, 4, 0xf8000000),
      RegCase("sar of -1", _.sar(_, _, _), -1, 31, -1),
      RegCase("ror", _.ror(_, _, _), 0x12345678, 8, 0x78123456),
      RegCase("ror by 0 is identity", _.ror(_, _, _), 0x12345678, 0, 0x12345678),
      RegCase("ror by 31 is rol by 1", _.ror(_, _, _), 1, 31, 2),
      RegCase("ror by 32 means by 0", _.ror(_, _, _), 0x12345678, 32, 0x12345678)
    ))
  }

  test("register ALU: comparisons") {
    checkRegCases(Seq(
      RegCase("slt is signed", _.slt(_, _, _), -1, 1, 1),
      RegCase("slt the other way", _.slt(_, _, _), 1, -1, 0),
      RegCase("sltu is unsigned", _.sltu(_, _, _), -1, 1, 0),
      RegCase("sltu the other way", _.sltu(_, _, _), 1, -1, 1),
      RegCase("slt on equal operands", _.slt(_, _, _), 42, 42, 0),
      RegCase("seq when equal", _.seq(_, _, _), 42, 42, 1),
      RegCase("seq when not", _.seq(_, _, _), 42, 43, 0),
      RegCase("sne when not equal", _.sne(_, _, _), 42, 43, 1),
      RegCase("sne when equal", _.sne(_, _, _), 42, 42, 0)
    ))
  }

  test("register ALU: multiplies") {
    val x = 0x12345678
    val y = 0x9abcdef0
    checkRegCases(Seq(
      RegCase("mul", _.mul(_, _, _), 1000, 1000, 1000000),
      RegCase("mul keeps the low word", _.mul(_, _, _), x, y, x * y),
      RegCase("mul of negatives", _.mul(_, _, _), -3, -4, 12),
      RegCase("mulh is signed", _.mulh(_, _, _), x, y, ((x.toLong * y.toLong) >> 32).toInt),
      RegCase("mulh of -1 squared", _.mulh(_, _, _), -1, -1, 0),
      RegCase("mulh of a big negative", _.mulh(_, _, _), 0x80000000, 2, -1),
      RegCase("mulhu is unsigned", _.mulhu(_, _, _), x, y,
        (((x.toLong & 0xffffffffL) * (y.toLong & 0xffffffffL)) >>> 32).toInt),
      RegCase("mulhu of -1 squared", _.mulhu(_, _, _), -1, -1, 0xfffffffe),
      RegCase("mulhu of a big unsigned", _.mulhu(_, _, _), 0x80000000, 2, 1)
    ))
  }

  test("immediate ALU sign extends the immediate for every operation") {
    checkImmCases(Seq(
      ImmCase("addi", _.addi(_, _, _), 100, -30, 70),
      ImmCase("addi at the top of the range", _.addi(_, _, _), 0, 131071, 131071),
      ImmCase("addi at the bottom", _.addi(_, _, _), 0, -131072, -131072),
      // -1 sign extends to all ones. If the immediate were zero extended these
      // three would come out as 0x0003ffff masks instead.
      ImmCase("andi with -1 is the identity", _.andi(_, _, _), 0xdeadbeef, -1, 0xdeadbeef),
      ImmCase("andi", _.andi(_, _, _), 0xdeadbeef, 0xff, 0xef),
      ImmCase("ori", _.ori(_, _, _), 0x0000beef, -65536, 0xffffbeef),
      ImmCase("xori with -1 is not", _.xori(_, _, _), 0x12345678, -1, 0xedcba987),
      ImmCase("shli", _.shli(_, _, _), 1, 5, 32),
      ImmCase("shri", _.shri(_, _, _), 0x80000000, 31, 1),
      ImmCase("sari", _.sari(_, _, _), 0x80000000, 31, -1),
      ImmCase("sari by 33 means by 1", _.sari(_, _, _), 0x80000000, 33, 0xc0000000),
      ImmCase("slti", _.slti(_, _, _), -5, -1, 1),
      ImmCase("sltui compares against the extended immediate", _.sltui(_, _, _), 5, -1, 1),
      ImmCase("sltui on a large operand", _.sltui(_, _, _), -1, -1, 0),
      ImmCase("seqi", _.seqi(_, _, _), -7, -7, 1),
      ImmCase("snei", _.snei(_, _, _), -7, -7, 0),
      ImmCase("rori", _.rori(_, _, _), 0x12345678, 8, 0x78123456)
    ))
  }

  test("movi and movhi build constants") {
    val r = cosim() { a =>
      import a._
      movi(a0, -1)
      movi(a1, 2097151)
      movi(a2, -2097152)
      movi(a3, 0x123)
      movhi(a3, 0x3fffff)
      li(t0, 0xdeadbeef)
      li(t1, 0x12345678)
      li(t2, 0x80000000)
      halt()
    }
    expectReg(r, 1, 0xffffffff, "movi sign extends imm22")
    expectReg(r, 2, 0x001fffff, "largest positive imm22")
    expectReg(r, 3, 0xffe00000, "smallest negative imm22")
    expectReg(r, 4, 0xfffffd23, "movhi keeps the low ten bits of rd")
    expectReg(r, 5, 0xdeadbeef)
    expectReg(r, 6, 0x12345678)
    expectReg(r, 7, 0x80000000)
  }

  test("addpc reads the pc of the instruction itself") {
    var first  = 0
    var second = 0
    val r = cosim() { a =>
      import a._
      first = a.pc
      addpc(a0, 0)
      second = a.pc
      addpc(a1, 16)
      addpc(a2, -4)
      halt()
    }
    expectReg(r, 1, first, "addpc with a zero displacement is the instruction's own address")
    expectReg(r, 2, second + 16)
    expectReg(r, 3, second + 4 - 4)
  }

  test("r0 reads as zero and discards writes") {
    val r = cosim() { a =>
      import a._
      movi(zero, 0x1234)
      addi(zero, zero, 99)
      add(a0, zero, zero)
      addi(a1, zero, 7)
      halt()
    }
    expectReg(r, 0, 0)
    expectReg(r, 1, 0)
    expectReg(r, 2, 7)
  }

  test("byte and halfword stores hit the right lanes, little endian") {
    val r = cosim(readRange = DataWord until (DataWord + 2)) { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0x11); stb(t0, gp, 0)
      li(t0, 0x22); stb(t0, gp, 1)
      li(t0, 0x33); stb(t0, gp, 2)
      li(t0, 0x44); stb(t0, gp, 3)
      ldw(a0, gp, 0)
      li(t0, 0xbeef); sth(t0, gp, 4)
      li(t0, 0xdead); sth(t0, gp, 6)
      ldw(a1, gp, 4)
      halt()
    }
    expectReg(r, 1, 0x44332211, "four bytes assemble little endian")
    expectReg(r, 2, 0xdeadbeef, "two halfwords assemble little endian")
    assert(r.word(DataWord) == 0x44332211)
    assert(r.word(DataWord + 1) == 0xdeadbeef)
  }

  test("a narrow store leaves the rest of the word alone") {
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0xffffffff); stw(t0, gp, 0)
      li(t0, 0x00); stb(t0, gp, 2)
      halt()
    }
    assert(r.word(DataWord) == 0xff00ffff, f"got 0x${r.word(DataWord)}%08x")
  }

  test("loads extend exactly as the opcode says") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0xff); stb(t0, gp, 0)
      ldb(a0, gp, 0)
      ldbu(a1, gp, 0)
      li(t0, 0x8000); sth(t0, gp, 2)
      ldh(a2, gp, 2)
      ldhu(a3, gp, 2)
      halt()
    }
    expectReg(r, 1, -1, "ldb sign extends")
    expectReg(r, 2, 255, "ldbu zero extends")
    expectReg(r, 3, -32768, "ldh sign extends")
    expectReg(r, 4, 32768, "ldhu zero extends")
  }

  test("memory displacements may be negative") {
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte + 64)
      li(t0, 0xcafe)
      stw(t0, gp, -64)
      ldw(a0, gp, -64)
      halt()
    }
    expectReg(r, 1, 0xcafe)
    assert(r.word(DataWord) == 0xcafe)
  }

  test("a store takes its data from the rd field") {
    // stw uses rd as a source and ra as the address base. Getting the two the
    // wrong way round would store the address instead of the value.
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0x5a5a5a5a)
      stw(t0, gp, 0)
      ldw(a0, gp, 0)
      halt()
    }
    assert(r.word(DataWord) == 0x5a5a5a5a)
    expectReg(r, 1, 0x5a5a5a5a)
  }

  test("every branch condition decides correctly") {
    // Each case branches over a single ORI. A bit ends up set in s0 exactly
    // when its branch was not taken.
    val cases: Seq[(String, (Assembler, Int, Int, String) => Unit, Int, Int, Boolean)] = Seq(
      ("beq equal",         (a, x, y, l) => a.beq(x, y, l),   5,  5, true),
      ("beq unequal",       (a, x, y, l) => a.beq(x, y, l),   5,  6, false),
      ("bne unequal",       (a, x, y, l) => a.bne(x, y, l),   5,  6, true),
      ("bne equal",         (a, x, y, l) => a.bne(x, y, l),   5,  5, false),
      ("blt signed",        (a, x, y, l) => a.blt(x, y, l),  -1,  1, true),
      ("blt signed other",  (a, x, y, l) => a.blt(x, y, l),   1, -1, false),
      ("bge signed",        (a, x, y, l) => a.bge(x, y, l),   1, -1, true),
      ("bge signed other",  (a, x, y, l) => a.bge(x, y, l),  -1,  1, false),
      ("bge on equal",      (a, x, y, l) => a.bge(x, y, l),   3,  3, true),
      ("bltu unsigned",     (a, x, y, l) => a.bltu(x, y, l),  1, -1, true),
      ("bltu other",        (a, x, y, l) => a.bltu(x, y, l), -1,  1, false),
      ("bgeu unsigned",     (a, x, y, l) => a.bgeu(x, y, l), -1,  1, true),
      ("bgeu other",        (a, x, y, l) => a.bgeu(x, y, l),  1, -1, false)
    )

    val r = cosim() { a =>
      import a._
      movi(s0, 0)
      for (((_, emit, x, y, _), i) <- cases.zipWithIndex) {
        li(t0, x)
        li(t1, y)
        emit(a, t0, t1, s"skip$i")
        ori(s0, s0, 1 << i)
        label(s"skip$i")
      }
      halt()
    }

    val expected = cases.zipWithIndex.foldLeft(0) { case (acc, ((_, _, _, _, taken), i)) =>
      if (taken) acc else acc | (1 << i)
    }
    val got = r.reg(8)
    for (((name, _, _, _, taken), i) <- cases.zipWithIndex) {
      val wasTaken = (got & (1 << i)) == 0
      assert(wasTaken == taken, s"$name: branch ${if (wasTaken) "was" else "was not"} taken")
    }
    expectReg(r, 8, expected)
  }

  test("jmp, call, ret and a tagged indirect jump") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      call("addTwo")
      addi(a0, a0, 1) // reached only by returning through lr
      la(t0, "tail")
      ori(t0, t0, 3)  // jmpr must clear the low two bits of a computed target
      jmpr(t0, 0)
      li(a0, 0xbad)
      halt()

      label("addTwo")
      addi(a0, a0, 2)
      ret()

      label("tail")
      addi(a0, a0, 100)
      halt()
    }
    expectReg(r, 1, 103, "2 from the call, 1 after returning, 100 in the tail")
  }

  test("callr links into a named register") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      li(lr, 0xdead) // callr must not touch lr when told to use s0
      la(t0, "func")
      callr(s0, t0, 0)
      addi(a0, a0, 1)
      halt()

      label("func")
      addi(a0, a0, 10)
      jmpr(s0, 0)
    }
    expectReg(r, 1, 11)
    expectReg(r, 15, 0xdead, "lr untouched by callr")
  }

  test("jmp reaches backwards as well as forwards") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      jmp("forward")

      label("back")
      addi(a0, a0, 2)
      jmp("done")

      label("forward")
      addi(a0, a0, 1)
      jmp("back")

      label("done")
      halt()
    }
    expectReg(r, 1, 3)
  }

  test("the halt address is reported") {
    var haltAt = 0
    val r = cosim() { a =>
      import a._
      nop()
      nop()
      haltAt = a.pc
      halt()
    }
    assert(!r.trapped)
    assert(r.cause == Isa.Cause.NONE)
    assert(r.trapPc == haltAt.toLong, f"halted at 0x${r.trapPc}%08x, expected 0x$haltAt%08x")
  }
}
