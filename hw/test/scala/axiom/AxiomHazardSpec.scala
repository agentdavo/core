package axiom

/** Tests for the parts of the pipeline that the instruction set does not
  * mention: forwarding, the load-use interlock, the two-cycle atomic, and the
  * shadow of a taken branch.
  *
  * Every program here is architecturally trivial and its answer is obvious by
  * inspection. That is the point. None of them can fail because the
  * specification was misread; they can only fail if the hardware hands a
  * consumer a stale register value, forgets to stall, or lets an instruction
  * that should have been killed commit its result.
  *
  * The Axiom-64 forms that the CORE-32 pipeline never had to deal with get the
  * most attention here: an instruction that writes two architectural registers
  * (LDP and every pre or post indexed access), and the atomics, which hold the
  * memory stage for two cycles and therefore reach the forwarding network by a
  * different path from an ordinary load.
  */
class AxiomHazardSpec extends AxiomSpec {

  test("a result forwards to a consumer at distance one, two, three and four") {
    val r = cosim() { a =>
      import a._
      movz(t0, 5); addi(a0, t0, 1)                          // distance 1
      movz(t0, 7); nop(); addi(a1, t0, 1)                   // distance 2
      movz(t0, 9); nop(); nop(); addi(a2, t0, 1)            // distance 3
      movz(t0, 11); nop(); nop(); nop(); addi(a3, t0, 1)    // distance 4
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
      movz(t0, 3)
      movz(t1, 4)
      add(a0, t0, t1) // t1 is one ahead, t0 is two ahead
      sub(a1, a0, t1) // a0 is one ahead, t1 is three ahead
      add(a2, a0, a1) // both operands are freshly written
      halt()
    }
    expectReg(r, 1, 7)
    expectReg(r, 2, 3)
    expectReg(r, 3, 10)
  }

  test("a chain where every instruction depends on the one before it") {
    val r = cosim() { a =>
      import a._
      li(a0, 1)
      for (_ <- 0 until 4) addi(a0, a0, 1)
      shli(a0, a0, 4)
      addi(a0, a0, -3)
      mul(a0, a0, a0)
      halt()
    }
    val expected = { val v = (5L << 4) - 3; v * v }
    expectReg(r, 1, expected)
  }

  test("a write to x0 is never forwarded") {
    // The write is discarded, so a consumer one, two and three instructions
    // later must all see zero rather than the value the producer computed.
    val r = cosim() { a =>
      import a._
      li(t0, 42)
      add(zero, t0, t0)
      add(a0, zero, zero)
      addi(a1, zero, 1)
      add(a2, zero, t0)
      halt()
    }
    expectReg(r, 1, 0)
    expectReg(r, 2, 1)
    expectReg(r, 3, 42, "only the real operand contributes")
  }

  test("MOVK forwards the destination register it also reads") {
    // This is exactly the shape li uses for a wide constant: the MOVK reads rd
    // through a port that has to be fed by the instruction immediately ahead.
    val r = cosim() { a =>
      import a._
      movz(a0, 0x1111, 0)
      movk(a0, 0x2222, 1)
      movk(a0, 0x3333, 2)
      movk(a0, 0x4444, 3)
      li(a1, 0xdeadbeefcafebabeL)
      li(a2, 0xffff0000ffff0001L)
      halt()
    }
    expectReg(r, 1, 0x4444333322221111L, "four back to back MOVK, each reading the last")
    expectReg(r, 2, 0xdeadbeefcafebabeL)
    expectReg(r, 3, 0xffff0000ffff0001L)
  }

  test("a load feeding the very next instruction takes the interlock") {
    val r = cosim() { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x1234)
      std(t0, s0, 0)
      ldd(t1, s0, 0)
      addi(a0, t1, 1) // needs the interlock
      halt()
    }
    expectReg(r, 1, 0x1235)
  }

  test("a load feeding an instruction two later needs no stall") {
    val r = cosim() { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x1000)
      std(t0, s0, 0)
      ldd(t1, s0, 0)
      nop()
      addi(a0, t1, 1)
      halt()
    }
    expectReg(r, 1, 0x1001)
  }

  test("a load feeding a branch predicate through a compare") {
    val r = cosim() { a =>
      import a._
      li(s0, DataByte)
      li(a0, 0)
      std(zero, s0, 0)
      ldd(t1, s0, 0)
      cmpNei(p0, t1, 0) // must see the loaded zero, not a stale value
      bp(p0, "skip")
      addi(a0, a0, 1)
      label("skip")
      halt()
    }
    expectReg(r, 1, 1, "the branch must not be taken")
  }

  test("a load feeding a store's data and a store's address base") {
    val r = cosim(readRange = DataWord until (DataWord + 3)) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0xabcd)
      std(t0, s0, 0)
      ldd(t1, s0, 0)
      std(t1, s0, 8) // the loaded value becomes store data immediately
      li(t2, DataByte + 16)
      std(t2, s0, 0) // park an address in memory
      ldd(t2, s0, 0)
      li(s1, 0x99)
      std(s1, t2, 0) // the loaded value becomes the address base immediately
      halt()
    }
    assert(r.word(DataWord + 1) == 0xabcd, "store data forwarded from a load")
    assert(r.word(DataWord + 2) == 0x99, "address base forwarded from a load")
  }

  test("a load pair feeding the store right after it") {
    // A store may start before the instruction producing its data has one,
    // and collect the value in the memory stage. A load pair is the producer
    // that cannot be treated that way: it writes its two registers on two
    // passes, and by the time the store is in memory the pass in writeback is
    // naming the other register. The store has to wait for it as it always
    // did, and this is the program that says whether it does.
    val r = cosim(readRange = DataWord until (DataWord + 6)) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x1111)
      li(t1, 0x2222)
      stp(t0, t1, s0, 0)
      ldp(t2, t3, s0, 0)
      std(t2, s0, 16) // the pair's first register, straight into a store
      ldp(t4, t5, s0, 0)
      std(t5, s0, 24) // and its second
      halt()
    }
    assert(r.word(DataWord + 2) == 0x1111, "the pair's first register reached the store")
    assert(r.word(DataWord + 3) == 0x2222, "the pair's second register reached the store")
  }

  test("back to back loads, and a load whose address came from a load") {
    val r = cosim() { a =>
      import a._
      li(s0, DataByte)
      li(t0, DataByte + 16)
      std(t0, s0, 0)  // doubleword 0 points at doubleword 2
      li(t0, 0x7777)
      std(t0, s0, 16)
      ldd(t1, s0, 0)
      ldd(a0, t1, 0)  // the address depends on the previous load
      ldd(a1, s0, 0)
      ldd(a2, s0, 16)
      halt()
    }
    expectReg(r, 1, 0x7777, "a load through a just-loaded pointer")
    expectReg(r, 2, DataByte + 16)
    expectReg(r, 3, 0x7777)
  }

  test("an atomic feeding the very next instruction") {
    // An atomic holds the memory stage for two cycles, so its result reaches
    // the forwarding network a cycle later than a load's does. A pipeline that
    // forwarded from the first of the two beats would hand the consumer the
    // value that was read before the modify.
    val r = cosim(data = Map(DataWord -> 100L), readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 5)
      ldadd(t0, s0, s1) // t0 is the old value, memory becomes 105
      addi(a0, t0, 1)   // consumes it immediately
      halt()
    }
    expectReg(r, 1, 101, "the atomic returned the old value and it forwarded")
    assert(r.word(DataWord) == 105, "the atomic still wrote memory")
  }

  test("an atomic feeding an instruction two later") {
    val r = cosim(data = Map(DataWord -> 100L)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 5)
      ldadd(t0, s0, s1)
      nop()
      addi(a0, t0, 1)
      halt()
    }
    expectReg(r, 1, 101)
  }

  test("a chain of atomics, each consuming the one before it") {
    // Two atomics back to back keep the memory stage busy continuously, which
    // is where a two-cycle occupancy is most likely to drop a beat.
    val r = cosim(
      data = Map(DataWord -> 1L, (DataWord + 1) -> 2L, (DataWord + 2) -> 4L),
      readRange = DataWord until (DataWord + 3)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 10)
      ldadd(t0, s0, s1)        // t0 = 1, memory 0 becomes 11
      li(s2, DataByte + 8)
      ldadd(t1, s2, t0)        // the addend comes straight from the last atomic
      li(s3, DataByte + 16)
      ldadd(t2, s3, t1)        // t1 = 2, so memory 2 becomes 6
      add(a0, t0, t1)
      add(a1, a0, t2)
      halt()
    }
    expectReg(r, 1, 3, "1 plus 2")
    expectReg(r, 2, 7, "plus the third old value, 4")
    assert(r.word(DataWord) == 11)
    assert(r.word(DataWord + 1) == 3, "2 plus the forwarded 1")
    assert(r.word(DataWord + 2) == 6, "4 plus the forwarded 2")
  }

  test("an atomic feeding a store, a branch predicate and an address base") {
    val r = cosim(
      data = Map(DataWord -> 7L, (DataWord + 4) -> 0L),
      readRange = DataWord until (DataWord + 5)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 1)
      li(a0, 0)
      swp(t0, s0, s1)     // t0 = 7, memory 0 becomes 1
      std(t0, s0, 8)      // the swapped-out value becomes store data at once
      ldadd(t1, s0, s1)   // t1 = 1, memory 0 becomes 2
      cmpEqi(p0, t1, 1)   // the predicate depends on the atomic immediately
      bp(p0, "taken")
      addi(a0, a0, 100)
      label("taken")
      li(s2, DataByte + 32)
      swp(t2, s2, s0)     // t2 = 0, memory 4 becomes the base address
      ldd(a1, s2, 0)
      halt()
    }
    expectReg(r, 1, 0, "the branch on the atomic result must be taken")
    expectReg(r, 2, DataByte)
    assert(r.word(DataWord) == 2)
    assert(r.word(DataWord + 1) == 7, "the store consumed the atomic result")
  }

  test("LDP feeds consumers of each of its two destinations") {
    // LDP is the one instruction that puts two transactions through the memory
    // stage and writes two architectural registers. Both have to forward, and
    // the second one arrives a cycle after the first.
    val r = cosim(data = Map(DataWord -> 11L, (DataWord + 1) -> 22L)) { a =>
      import a._
      li(s0, DataByte)
      ldp(t0, t1, s0, 0)
      addi(a0, t0, 1) // consumes the first destination at distance one
      addi(a1, t1, 1) // consumes the second destination at distance two
      ldp(t2, t3, s0, 0)
      add(a2, t2, t3) // consumes both at once
      halt()
    }
    expectReg(r, 1, 12)
    expectReg(r, 2, 23)
    expectReg(r, 3, 33)
  }

  test("LDP feeding its second destination to the instruction right after it") {
    val r = cosim(data = Map(DataWord -> 11L, (DataWord + 1) -> 22L)) { a =>
      import a._
      li(s0, DataByte)
      ldp(t0, t1, s0, 0)
      addi(a0, t1, 1) // the later of the two destinations, at distance one
      halt()
    }
    expectReg(r, 1, 23)
  }

  test("an indexed access feeds a consumer of its updated base register") {
    // Pre-index and post-index write the base as well as the data register.
    // The base update is a second architectural write from one instruction and
    // has its own forwarding comparator; a pipeline that only forwards the
    // data register would hand the consumer the old base.
    val r = cosim(data = Map(DataWord -> 0xaaaaL, (DataWord + 1) -> 0xbbbbL)) { a =>
      import a._
      li(s0, DataByte)
      ldd(t0, s0, 8, Isa.Mode.PRE)
      mov(a0, s0)                 // the updated base, at distance one
      li(s1, DataByte)
      ldd(t1, s1, 8, Isa.Mode.POST)
      addi(a1, s1, 8)             // arithmetic on the updated base, distance one
      li(s2, DataByte)
      li(t2, 0x1234)
      std(t2, s2, 8, Isa.Mode.PRE)
      ldd(a2, s2, 0)              // the new base becomes a load address at once
      halt()
    }
    expectReg(r, 1, DataByte + 8, "the pre-index base update forwarded")
    expectReg(r, 2, DataByte + 16, "the post-index base update forwarded")
    expectReg(r, 3, 0x1234, "an indexed store's new base used as an address")
  }

  test("nothing in the shadow of a taken branch commits") {
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      cmpEqi(p0, zero, 0)
      bp(p0, "after")
      addi(a0, a0, 1) // first shadow slot
      addi(a0, a0, 2) // second shadow slot
      addi(a0, a0, 4) // third shadow slot
      label("after")
      halt()
    }
    expectReg(r, 1, 0, "three instructions were killed by the flush")
  }

  test("a store in the shadow of a taken branch never reaches memory") {
    val r = cosim(
      data = Map(DataWord -> 0L, (DataWord + 1) -> 0L),
      readRange = DataWord until (DataWord + 2)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(t1, 0xbadbad)
      cmpEqi(p0, zero, 0)
      bp(p0, "after")
      std(t1, s0, 0)
      std(t1, s0, 8)
      label("after")
      halt()
    }
    assert(r.word(DataWord) == 0, "the first shadow store must not commit")
    assert(r.word(DataWord + 1) == 0, "the second shadow store must not commit")
  }

  test("nothing in the shadow of a predicted-taken branch commits") {
    // The other shadow. A branch the front end guessed right about never
    // redirects from execute at all: the instructions behind it were thrown
    // when decode folded it, and what killed them is the fetch generation
    // rather than an explicit throw. The loop below trains the prediction on
    // its first pass and closes on it three more times, and the store on the
    // fall-through path must run once, at the end, however confident the front
    // end became about the branch.
    val r = cosim(
      data = Map(DataWord -> 0L),
      readRange = DataWord until (DataWord + 1)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 4)
      li(t1, 0)
      label("loop")
      addi(t1, t1, 7)
      addi(t0, t0, -1)
      cmpGti(p0, t0, 0)
      bp(p0, "loop")
      std(t1, s0, 0)
      halt()
    }
    assert(r.word(DataWord) == 28, "the fall-through store runs once, after the loop")
  }

  test("nothing in the shadow of a jump or a call commits") {
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      b("afterJump")
      addi(a0, a0, 1)
      addi(a0, a0, 2)
      label("afterJump")
      bl("routine")
      addi(a0, a0, 1000) // reached by the return, so this one does run
      halt()

      label("routine")
      addi(a0, a0, 100)
      ret()
      addi(a0, a0, 4) // in the shadow of the return
      addi(a0, a0, 8)
    }
    expectReg(r, 1, 1100)
  }

  test("a branch not taken lets its shadow run") {
    val r = cosim() { a =>
      import a._
      li(a0, 0)
      cmpNei(p0, zero, 0) // false
      bp(p0, "skip")
      addi(a0, a0, 1)
      addi(a0, a0, 2)
      label("skip")
      bp(p0, "skip2", invert = true) // taken, the inverted sense of a false predicate
      addi(a0, a0, 4)
      addi(a0, a0, 8)
      label("skip2")
      halt()
    }
    expectReg(r, 1, 3, "only the not-taken branch's shadow runs")
  }

  test("an interlock immediately before a taken branch") {
    // The load-use stall and the branch flush arrive in the same few cycles.
    // A pipeline that resolved the stall against the flushed instruction would
    // either hang or let the shadow through.
    val r = cosim() { a =>
      import a._
      li(s0, DataByte)
      li(a0, 0)
      li(t0, 1)
      std(t0, s0, 0)
      ldd(t1, s0, 0)
      cmpEq(p0, t1, t0) // stalls on the load, then the branch flushes
      bp(p0, "after")
      addi(a0, a0, 1)
      addi(a0, a0, 2)
      label("after")
      addi(a0, a0, 100)
      halt()
    }
    expectReg(r, 1, 100)
  }

  test("an atomic immediately before a taken branch") {
    val r = cosim(data = Map(DataWord -> 1L)) { a =>
      import a._
      li(s0, DataByte)
      li(s1, 0)
      li(a0, 0)
      ldadd(t1, s0, s1) // two cycles in memory, then a compare that needs it
      cmpEqi(p0, t1, 1)
      bp(p0, "after")
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
      li(s0, DataByte)
      li(a0, 0)
      li(t0, 20)
      label("loop")
      std(t0, s0, 0)
      ldd(t1, s0, 0)   // reads back what was just written
      add(a0, a0, t1)  // interlock
      addi(t0, t0, -1)
      cmpNei(p0, t0, 0)
      bp(p0, "loop")
      halt()
    }
    expectReg(r, 1, 20 * 21 / 2)
  }

  test("a tight loop around an atomic") {
    val r = cosim(data = Map(DataWord -> 0L), readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(s0, DataByte)
      li(a0, 0)
      li(t0, 16)
      li(s1, 1)
      label("loop")
      ldadd(t1, s0, s1) // the old value feeds the accumulate immediately
      add(a0, a0, t1)
      addi(t0, t0, -1)
      cmpNei(p0, t0, 0)
      bp(p0, "loop")
      halt()
    }
    // The counter takes the values 0 to 15, so the accumulated sum is 120.
    expectReg(r, 1, 120)
    assert(r.word(DataWord) == 16)
  }

  test("cycles and retired instructions are both counted, and a pair counts once") {
    // Built with the assembler kept, so the instruction count is not a number
    // anyone has to maintain by hand.
    val asm = Assembler.build(0) { a =>
      import a._
      li(s0, DataByte)   // one MOVZ
      li(t0, 0x1111)     // one MOVZ
      li(t1, 0x2222)     // one MOVZ
      stp(t0, t1, s0, 0) // two memory beats, one retired instruction
      ldp(t2, t3, s0, 0) // likewise
      nop(); nop()
      halt()
    }
    val program = asm.assemble()
    val r = cosimProgram(program, readRange = DataWord until (DataWord + 2))
    val expected = program.length - 1 // the halt itself does not retire
    assert(expected == 7, s"the program should be seven instructions plus a halt, got $expected")
    assert(r.retired == expected,
      s"retired ${r.retired}, expected $expected: a pair must count as one instruction")
    assert(r.cycles >= r.retired, s"cycles ${r.cycles} retired ${r.retired}")
    // Filling the pipeline, plus one extra beat each for the two pair forms.
    assert(r.cycles <= r.retired + 10, s"unexpectedly many cycles: ${r.cycles}")
    expectReg(r, 10, 0x1111); expectReg(r, 11, 0x2222)
  }

  test("straight line code retires close to one instruction per cycle") {
    val r = cosim() { a =>
      import a._
      li(t0, 1)
      for (_ <- 0 until 200) addi(t1, t0, 1) // no dependency between any two
      halt()
    }
    val ipc = r.retired.toDouble / r.cycles.toDouble
    assert(ipc > 0.9, f"instructions per cycle was $ipc%.3f (${r.retired} in ${r.cycles})")
  }
}
