package core

/** Tests for the conditions that stop the core. */
class TrapSpec extends CoreSpec {

  test("an undefined opcode traps and stops before the next instruction") {
    var at = 0
    val r = cosim() { a =>
      import a._
      movi(a0, 1)
      at = a.pc
      word(0x11 << Isa.OP_LO) // the reserved slot where SUBI would have been
      movi(a0, 2)
      halt()
    }
    assert(r.trapped, "an undefined opcode must trap")
    assert(r.cause == Isa.Cause.ILLEGAL, s"cause was ${r.causeName}")
    assert(r.trapPc == at.toLong, f"trapped at 0x${r.trapPc}%08x, expected 0x$at%08x")
    expectReg(r, 1, 1, "the instruction after the trap must not run")
  }

  test("every undefined opcode in each class traps") {
    for (op <- Seq(0x11, 0x25, 0x26, 0x27, 0x2b, 0x2c, 0x2d, 0x2e, 0x36, 0x37, 0x3c, 0x3d, 0x3e, 0x3f)) {
      assert(!Isa.isLegal(op << Isa.OP_LO), f"0x$op%02x should be undefined")
      val r = cosim() { a =>
        import a._
        word(op << Isa.OP_LO)
        halt()
      }
      assert(r.trapped && r.cause == Isa.Cause.ILLEGAL, f"opcode 0x$op%02x did not trap: $r")
      assert(r.trapPc == 0L)
    }
  }

  test("a misaligned word load traps") {
    var at = 0
    val r = cosim() { a =>
      import a._
      li(gp, DataByte + 1)
      at = a.pc
      ldw(a0, gp, 0)
      movi(a0, 0x5555)
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD, s"got $r")
    assert(r.trapPc == at.toLong)
    expectReg(r, 1, 0, "the load must not write its destination")
  }

  test("a misaligned halfword load traps") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte + 1)
      ldh(a0, gp, 0)
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD, s"got $r")
  }

  test("a misaligned store traps and leaves memory alone") {
    var at = 0
    val r = cosim(readRange = DataWord until (DataWord + 2)) { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0x11111111); stw(t0, gp, 0)
      li(t0, 0x22222222); stw(t0, gp, 4)
      li(t1, 0xffffffff)
      at = a.pc
      stw(t1, gp, 2) // straddles both words
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_STORE, s"got $r")
    assert(r.trapPc == at.toLong)
    assert(r.word(DataWord) == 0x11111111, "the word below the misaligned store changed")
    assert(r.word(DataWord + 1) == 0x22222222, "the word above the misaligned store changed")
  }

  test("a misaligned halfword store traps") {
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte)
      movi(t0, 0); stw(t0, gp, 0)
      li(t1, 0xbeef)
      sth(t1, gp, 1)
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_STORE, s"got $r")
    assert(r.word(DataWord) == 0)
  }

  test("byte accesses never need alignment") {
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte)
      movi(t0, 0); stw(t0, gp, 0)
      li(t1, 0xaa); stb(t1, gp, 1)
      li(t1, 0xbb); stb(t1, gp, 3)
      ldbu(a0, gp, 1)
      ldbu(a1, gp, 3)
      halt()
    }
    assert(!r.trapped, s"got $r")
    expectReg(r, 1, 0xaa)
    expectReg(r, 2, 0xbb)
    assert(r.word(DataWord) == 0xbb00aa00)
  }

  test("halt is not a trap") {
    val r = cosim() { a =>
      import a._
      movi(a0, 1)
      halt()
    }
    assert(r.halted)
    assert(!r.trapped)
    assert(r.cause == Isa.Cause.NONE)
  }

  test("an instruction already past execute still completes when a trap stops the core") {
    // The two instructions ahead of the trapping one are in memory and
    // writeback when it is caught; they must not be lost.
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0x1234)
      stw(t0, gp, 0)
      movi(a1, 0x42)
      li(t1, DataByte + 1)
      ldw(a0, t1, 0)
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD)
    expectReg(r, 2, 0x42, "the instruction before the trap must have committed")
    assert(r.word(DataWord) == 0x1234, "the store before the trap must have committed")
  }
}
