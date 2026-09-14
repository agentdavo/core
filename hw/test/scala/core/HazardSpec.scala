package core

/** Tests for the parts of the pipeline that are not visible in the
  * instruction set: forwarding, the load-use interlock, and the branch shadow.
  *
  * Every one of these programs is architecturally trivial. They only fail if
  * the hardware lets a stale register value through or lets a killed
  * instruction commit, so the expected values are all obvious by inspection.
  */
class HazardSpec extends CoreSpec {

  test("a result forwards to a consumer at any distance") {
    val r = cosim() { a =>
      import a._
      movi(t0, 5); addi(a0, t0, 1)                       // distance 1
      movi(t0, 7); nop(); addi(a1, t0, 1)                // distance 2
      movi(t0, 9); nop(); nop(); addi(a2, t0, 1)         // distance 3
      movi(t0, 11); nop(); nop(); nop(); addi(a3, t0, 1) // distance 4, via the register file
      halt()
    }
    expectReg(r, 1, 6, "forwarded from the memory stage")
    expectReg(r, 2, 8, "forwarded from the writeback stage")
    expectReg(r, 3, 10, "bypassed into the register file read")
    expectReg(r, 4, 12, "read from the register file normally")
  }

  test("both operands forward at once") {
    val r = cosim() { a =>
      import a._
      movi(t0, 3)
      movi(t1, 4)
      add(a0, t0, t1) // t1 is one ahead, t0 is two ahead
      sub(a1, a0, t1) // a0 is one ahead, t1 is three ahead
      halt()
    }
    expectReg(r, 1, 7)
    expectReg(r, 2, 3)
  }

  test("a chain of dependent instructions") {
    val r = cosim() { a =>
      import a._
      movi(a0, 1)
      addi(a0, a0, 1)
      addi(a0, a0, 1)
      addi(a0, a0, 1)
      addi(a0, a0, 1)
      shli(a0, a0, 4)
      addi(a0, a0, -3)
      halt()
    }
    expectReg(r, 1, (5 << 4) - 3)
  }

  test("a write to r0 is never forwarded") {
    // The write is discarded, so the consumer must see zero rather than the
    // value the producer computed.
    val r = cosim() { a =>
      import a._
      movi(t0, 42)
      addi(zero, t0, 5)
      add(a0, zero, zero)
      addi(a1, zero, 1)
      halt()
    }
    expectReg(r, 1, 0)
    expectReg(r, 2, 1)
  }

  test("movhi forwards the destination it also reads") {
    // li of a wide constant is exactly this shape: movi writes rd and the very
    // next instruction reads rd through the second register port.
    val r = cosim() { a =>
      import a._
      movi(a0, 0x123)
      movhi(a0, 0x3fffff)
      li(a1, 0xcafebabe)
      li(a2, 0x00400000)
      halt()
    }
    expectReg(r, 1, 0xfffffd23)
    expectReg(r, 2, 0xcafebabe)
    expectReg(r, 3, 0x00400000)
  }

  test("a load feeding the next instruction stalls exactly once") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0x1234)
      stw(t0, gp, 0)
      ldw(t1, gp, 0)
      addi(a0, t1, 1) // needs the interlock
      halt()
    }
    expectReg(r, 1, 0x1235)
  }

  test("a load feeding an instruction two later needs no stall") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0x1000)
      stw(t0, gp, 0)
      ldw(t1, gp, 0)
      nop()
      addi(a0, t1, 1)
      halt()
    }
    expectReg(r, 1, 0x1001)
  }

  test("a load feeding a branch condition") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      movi(t0, 0)
      stw(t0, gp, 0)
      movi(a0, 0)
      ldw(t1, gp, 0)
      bnez(t1, "skip") // must see the loaded zero, not a stale value
      addi(a0, a0, 1)
      label("skip")
      halt()
    }
    expectReg(r, 1, 1, "the branch must not be taken")
  }

  test("a load feeding a store's data and a store's address base") {
    val r = cosim(readRange = DataWord until (DataWord + 3)) { a =>
      import a._
      li(gp, DataByte)
      li(t0, 0xabcd)
      stw(t0, gp, 0)
      ldw(t1, gp, 0)
      stw(t1, gp, 4) // loaded value becomes store data immediately
      li(t2, DataByte + 8)
      stw(t2, gp, 0) // park an address in memory
      ldw(t2, gp, 0)
      li(s0, 0x99)
      stw(s0, t2, 0) // loaded value becomes the address base immediately
      halt()
    }
    assert(r.word(DataWord + 1) == 0xabcd, "store data forwarded from a load")
    assert(r.word(DataWord + 2) == 0x99, "address base forwarded from a load")
  }

  test("back to back loads and a load whose address comes from a load") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      li(t0, DataByte + 8)
      stw(t0, gp, 0) // word 0 points at word 2
      li(t0, 0x7777)
      stw(t0, gp, 8)
      ldw(t1, gp, 0)
      ldw(a0, t1, 0) // address depends on the previous load
      ldw(a1, gp, 0)
      ldw(a2, gp, 8)
      halt()
    }
    expectReg(r, 1, 0x7777)
    expectReg(r, 2, DataByte + 8)
    expectReg(r, 3, 0x7777)
  }

  test("a store's data forwards from the instruction right before it") {
    val r = cosim(readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(gp, DataByte)
      addi(t0, zero, 77)
      stw(t0, gp, 0)
      halt()
    }
    assert(r.word(DataWord) == 77)
  }

  test("a branch condition forwards from the instruction right before it") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      movi(t0, 1)
      movi(t1, 1)
      beq(t0, t1, "taken") // both operands are one and two ahead
      addi(a0, a0, 1)
      label("taken")
      addi(a0, a0, 10)
      halt()
    }
    expectReg(r, 1, 10, "the branch must be taken")
  }

  test("nothing in the shadow of a taken branch commits") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      movi(t0, 1)
      beq(t0, t0, "after")
      addi(a0, a0, 1) // first shadow slot
      addi(a0, a0, 2) // second shadow slot
      label("after")
      halt()
    }
    expectReg(r, 1, 0, "two instructions were killed by the flush")
  }

  test("a store in the shadow of a taken branch does not reach memory") {
    val r = cosim(readRange = DataWord until (DataWord + 2)) { a =>
      import a._
      li(gp, DataByte)
      movi(t0, 0)
      stw(t0, gp, 0)
      stw(t0, gp, 4)
      li(t1, 0xbadbad)
      beq(t0, t0, "after")
      stw(t1, gp, 0)
      stw(t1, gp, 4)
      label("after")
      halt()
    }
    assert(r.word(DataWord) == 0, "first shadow store must not commit")
    assert(r.word(DataWord + 1) == 0, "second shadow store must not commit")
  }

  test("nothing in the shadow of a jump or a call commits") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      jmp("afterJump")
      addi(a0, a0, 1)
      addi(a0, a0, 2)
      label("afterJump")
      call("routine")
      addi(a0, a0, 1000) // reached by the return, so it does run
      halt()

      label("routine")
      addi(a0, a0, 100)
      ret()
      addi(a0, a0, 4) // in the shadow of the ret
      addi(a0, a0, 8)
    }
    expectReg(r, 1, 1100)
  }

  test("a branch not taken lets its shadow run") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      movi(t0, 1)
      bnez(t0, "skip")
      addi(a0, a0, 1)
      addi(a0, a0, 2)
      label("skip")
      beqz(t0, "skip2") // not taken
      addi(a0, a0, 4)
      addi(a0, a0, 8)
      label("skip2")
      halt()
    }
    expectReg(r, 1, 12, "only the not-taken branch's shadow runs")
  }

  test("an interlock immediately before a taken branch") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      movi(a0, 0)
      movi(t0, 1)
      stw(t0, gp, 0)
      ldw(t1, gp, 0)
      beq(t1, t0, "after") // stalls, then flushes
      addi(a0, a0, 1)
      addi(a0, a0, 2)
      label("after")
      addi(a0, a0, 100)
      halt()
    }
    expectReg(r, 1, 100)
  }

  test("a tight dependent loop") {
    val r = cosim() { a =>
      import a._
      li(gp, DataByte)
      movi(a0, 0)
      movi(t0, 20)
      label("loop")
      stw(t0, gp, 0)
      ldw(t1, gp, 0)  // reads back what was just written
      add(a0, a0, t1) // interlock
      addi(t0, t0, -1)
      bnez(t0, "loop")
      halt()
    }
    expectReg(r, 1, 20 * 21 / 2)
  }

  test("cycles and retired instructions are both counted") {
    val r = cosim() { a =>
      import a._
      movi(a0, 0)
      nop(); nop(); nop(); nop()
      halt()
    }
    // Five instructions retire; the halt itself does not.
    assert(r.retired == 5, s"retired ${r.retired}")
    // No stalls or flushes, so the only overhead is filling the pipeline.
    assert(r.cycles >= r.retired, s"cycles ${r.cycles} retired ${r.retired}")
    assert(r.cycles <= r.retired + 6, s"unexpectedly many cycles: ${r.cycles}")
  }
}
