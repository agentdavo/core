package axiom

/** Tests for every condition that stops the core.
  *
  * Two properties matter here and neither of them is about arithmetic. The
  * first is that reserved encoding space traps rather than aliasing: section
  * 2.1 says that both an undefined primary opcode and an undefined
  * sub-function inside a defined opcode raise an illegal instruction trap,
  * which is what lets a future version of the architecture add an instruction
  * without silently changing the meaning of an old binary. The second is that
  * the stop is precise: section 6 says a trapping or halting instruction does
  * not commit, while instructions ahead of it in the pipeline do.
  *
  * The sub-function cases cannot be written with the assembler, because the
  * assembler refuses to encode them on purpose. They are built here as raw
  * words with `a.word`, and every one of them is cross-checked against
  * `Isa.isLegal` so that the test and the decoder cannot disagree about which
  * encodings are supposed to be reserved.
  */
class AxiomTrapSpec extends AxiomSpec {

  /** Run a single raw instruction word, surrounded by markers.
    *
    * a0 is set to 1 before the word and to 2 after it, so the returned result
    * proves both that the trapping instruction stopped the core and that the
    * instruction behind it never committed.
    */
  private def runIllegal(instr: Int, what: String): RunResult = {
    assert(!Isa.isLegal(instr), f"$what%s: 0x$instr%08x should not be a legal encoding")
    var at = 0L
    val r = cosim() { a =>
      import a._
      li(a0, 1)
      at = a.pc
      word(instr)
      li(a0, 2)
      halt()
    }
    assert(r.trapped, f"$what%s: 0x$instr%08x did not trap, got $r")
    assert(r.cause == Isa.Cause.ILLEGAL, s"$what: cause was ${r.causeName}")
    assert(r.trapPc == at, f"$what%s: stopped at 0x${r.trapPc}%08x, expected 0x$at%08x")
    assert(r.reg(1) == 1L, s"$what: the instruction after the trap ran")
    r
  }

  // =====================================================================
  // Undefined primary opcodes
  // =====================================================================

  test("every undefined primary opcode traps as ILLEGAL") {
    val undefined = (0 until 64).filterNot(Isa.PRIMARY_OPCODES.contains)
    // Sixty-four slots, thirty-seven defined, so twenty-seven are reserved.
    assert(undefined.length == 27, s"expected 27 reserved opcodes, found ${undefined.length}")
    for (op <- undefined) runIllegal(op << Isa.OP_LO, f"opcode 0x$op%02x")
  }

  test("an undefined opcode with non-zero operand fields still traps") {
    // The reserved check must look at the opcode, not at whether the rest of
    // the word happens to look like something.
    for (op <- Seq(0x0f, 0x17, 0x1f, 0x2f, 0x3f)) {
      val instr = (op << Isa.OP_LO) | (7 << Isa.RD_LO) | (9 << Isa.RN_LO) | (11 << Isa.RM_LO) | 0x7ff
      runIllegal(instr, f"opcode 0x$op%02x with operands")
    }
  }

  // =====================================================================
  // Undefined sub-functions inside a defined opcode
  // =====================================================================

  test("an undefined ALU function code traps") {
    // Twenty-eight functions are defined, so 0x1c to 0x1f are reserved.
    for (fn <- 0x1c to 0x1f) {
      val instr = (Isa.ALU_R << Isa.OP_LO) | (1 << Isa.RD_LO) | (2 << Isa.RN_LO) |
        (3 << Isa.RM_LO) | (fn << 6)
      assert(!Isa.Fn.ALL.contains(fn))
      runIllegal(instr, f"ALU function 0x$fn%02x")
    }
  }

  test("an undefined condition code traps in CMP and in CMPI") {
    // Ten condition codes are defined in a four-bit field, so 10 to 15 are
    // reserved. A decoder that treated the field as a lookup with a default
    // would quietly compute one of the defined comparisons instead.
    for (cc <- 10 to 15) {
      assert(!Isa.Cc.ALL.contains(cc))
      val cmpR = (Isa.CMP_R << Isa.OP_LO) | (2 << 21) | (3 << Isa.RN_LO) | (4 << Isa.RM_LO) | (cc << 7)
      runIllegal(cmpR, s"cmp with condition code $cc")
      val cmpI = (Isa.CMP_I << Isa.OP_LO) | (2 << 21) | (3 << Isa.RN_LO) | (cc << 12) | 5
      runIllegal(cmpI, s"cmpi with condition code $cc")
    }
  }

  test("an undefined atomic function traps") {
    for (fn <- 10 to 15) {
      assert(!Isa.AtomicFn.ALL.contains(fn))
      val instr = (Isa.ATOMIC << Isa.OP_LO) | (1 << Isa.RD_LO) | (2 << Isa.RN_LO) |
        (3 << Isa.RM_LO) | (fn << 7) | (Isa.Ord.PLAIN << 5) | (Isa.SIZE_D << 3)
      runIllegal(instr, s"atomic function $fn")
    }
  }

  test("an atomic on a byte or a halfword traps") {
    // Section 4 says atomics operate on words or doublewords. There is no
    // read-modify-write on a narrower quantity, so those two size codes are
    // reserved rather than being quietly widened.
    for (size <- Seq(Isa.SIZE_B, Isa.SIZE_H)) {
      val instr = (Isa.ATOMIC << Isa.OP_LO) | (1 << Isa.RD_LO) | (2 << Isa.RN_LO) |
        (3 << Isa.RM_LO) | (Isa.AtomicFn.SWP << 7) | (Isa.Ord.PLAIN << 5) | (size << 3)
      runIllegal(instr, s"atomic of size $size")
    }
  }

  test("the reserved addressing mode traps on a single access and on a pair") {
    for (op <- Seq(Isa.LDB, Isa.LDD, Isa.STB, Isa.STD)) {
      val instr = (op << Isa.OP_LO) | (1 << Isa.RD_LO) | (2 << Isa.RN_LO) | (3 << 14)
      runIllegal(instr, f"opcode 0x$op%02x with addressing mode 3")
    }
    for (op <- Seq(Isa.LDP, Isa.STP)) {
      val instr = (op << Isa.OP_LO) | (1 << Isa.RD_LO) | (2 << Isa.RN_LO) |
        (3 << Isa.RM_LO) | (3 << 9)
      runIllegal(instr, f"opcode 0x$op%02x with addressing mode 3")
    }
  }

  test("a reserved ordering annotation traps on an ordered access") {
    // An ordered access with no ordering is a plain access with a different
    // opcode, and the fourth encoding means acquire-and-release, which only
    // makes sense on a read-modify-write. Both are reserved here.
    for (op <- Seq(Isa.LD_ORD, Isa.ST_ORD); ord <- Seq(Isa.Ord.PLAIN, Isa.Ord.ACQREL_BOTH)) {
      val instr = (op << Isa.OP_LO) | (1 << Isa.RD_LO) | (2 << Isa.RN_LO) |
        (Isa.SIZE_D << 14) | (ord << 12)
      runIllegal(instr, f"opcode 0x$op%02x with ordering $ord")
    }
  }

  test("an undefined fence kind traps") {
    for (kind <- 4 to 15) {
      assert(!Isa.FenceKind.ALL.contains(kind))
      runIllegal((Isa.FENCE << Isa.OP_LO) | kind, s"fence kind $kind")
    }
  }

  test("an undefined system function traps") {
    // HALT is function zero and nothing else is defined yet.
    for (fn <- Seq(1, 2, 3, 15, 16, 31)) {
      assert(!Isa.SystemFn.ALL.contains(fn))
      runIllegal((Isa.SYSTEM << Isa.OP_LO) | fn, s"system function $fn")
    }
  }

  // =====================================================================
  // Alignment
  // =====================================================================

  test("a misaligned load traps at every size that needs alignment") {
    // Halfwords need two-byte alignment, words four and doublewords eight, so
    // each case below is the smallest misalignment that size can have.
    val cases = Seq(
      ("ldh", 1, (a: Assembler, base: Int) => a.ldh(a.a0, base, 0)),
      ("ldhu", 1, (a: Assembler, base: Int) => a.ldhu(a.a0, base, 0)),
      ("ldw", 1, (a: Assembler, base: Int) => a.ldw(a.a0, base, 0)),
      ("ldw", 2, (a: Assembler, base: Int) => a.ldw(a.a0, base, 0)),
      ("ldwu", 2, (a: Assembler, base: Int) => a.ldwu(a.a0, base, 0)),
      ("ldd", 4, (a: Assembler, base: Int) => a.ldd(a.a0, base, 0)),
      ("ldd", 1, (a: Assembler, base: Int) => a.ldd(a.a0, base, 0))
    )
    for ((name, offset, emit) <- cases) {
      var at = 0L
      val r = cosim(data = Map(DataWord -> 0x1111111111111111L)) { a =>
        import a._
        li(s0, DataByte + offset)
        li(a0, 0)
        at = a.pc
        emit(a, s0)
        li(a0, 0x5555)
        halt()
      }
      assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD,
        s"$name at byte offset $offset gave $r")
      assert(r.trapPc == at, f"$name%s stopped at 0x${r.trapPc}%08x, expected 0x$at%08x")
      expectReg(r, 1, 0, s"$name must not write its destination, nor may the instruction after it run")
    }
  }

  test("a misaligned store traps and leaves both neighbouring doublewords alone") {
    // The store below would straddle the boundary between two doublewords.
    // Splitting it would corrupt eight bytes on each side, so both are checked.
    var at = 0L
    val r = cosim(
      data = Map(DataWord -> 0x1111111111111111L, (DataWord + 1) -> 0x2222222222222222L),
      readRange = DataWord until (DataWord + 2)
    ) { a =>
      import a._
      li(s0, DataByte + 4)
      li(t0, ~0L)
      at = a.pc
      std(t0, s0, 0)
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_STORE, s"got $r")
    assert(r.trapPc == at, f"stopped at 0x${r.trapPc}%08x, expected 0x$at%08x")
    assert(r.word(DataWord) == 0x1111111111111111L, "the doubleword below the store changed")
    assert(r.word(DataWord + 1) == 0x2222222222222222L, "the doubleword above the store changed")
  }

  test("a misaligned store traps at every size that needs alignment") {
    val cases = Seq(
      ("sth", 1, (a: Assembler, base: Int) => a.sth(a.t0, base, 0)),
      ("stw", 1, (a: Assembler, base: Int) => a.stw(a.t0, base, 0)),
      ("stw", 2, (a: Assembler, base: Int) => a.stw(a.t0, base, 0)),
      ("std", 4, (a: Assembler, base: Int) => a.std(a.t0, base, 0)),
      ("std", 2, (a: Assembler, base: Int) => a.std(a.t0, base, 0))
    )
    for ((name, offset, emit) <- cases) {
      val r = cosim(
        data = Map(DataWord -> 0x1111111111111111L),
        readRange = DataWord until (DataWord + 1)
      ) { a =>
        import a._
        li(s0, DataByte + offset)
        li(t0, ~0L)
        emit(a, s0)
        halt()
      }
      assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_STORE,
        s"$name at byte offset $offset gave $r")
      assert(r.word(DataWord) == 0x1111111111111111L, s"$name changed memory before trapping")
    }
  }

  test("byte accesses never need alignment") {
    val r = cosim(data = Map(DataWord -> 0L), readRange = DataWord until (DataWord + 1)) { a =>
      import a._
      li(s0, DataByte)
      for (lane <- 0 until 8) {
        li(t0, 0xa0 + lane)
        stb(t0, s0, lane)
      }
      ldb(a0, s0, 1)   // 0xa1, which has its top bit set and so is negative
      ldbu(a1, s0, 7)  // 0xa7
      ldb(a2, s0, 7)   // 0xa7 is negative as a byte
      halt()
    }
    assert(!r.trapped, s"a byte access must never trap on alignment: $r")
    expectReg(r, 1, 0xffffffffffffffa1L)
    expectReg(r, 2, 0xa7L)
    expectReg(r, 3, 0xffffffffffffffa7L)
    assert(r.word(DataWord) == 0xa7a6a5a4a3a2a1a0L)
  }

  test("a misaligned pair traps") {
    for ((name, isLoad, cause) <- Seq(
      ("ldp", true, Isa.Cause.MISALIGNED_LOAD),
      ("stp", false, Isa.Cause.MISALIGNED_STORE)
    )) {
      var at = 0L
      val r = cosim(
        data = Map(DataWord -> 0x1111111111111111L, (DataWord + 1) -> 0x2222222222222222L),
        readRange = DataWord until (DataWord + 2)
      ) { a =>
        import a._
        li(s0, DataByte + 4) // a pair needs eight-byte alignment
        li(t0, 0); li(t1, 0)
        at = a.pc
        if (isLoad) ldp(t0, t1, s0, 0) else stp(t0, t1, s0, 0)
        halt()
      }
      assert(r.trapped && r.cause == cause, s"$name gave $r")
      assert(r.trapPc == at, f"$name%s stopped at 0x${r.trapPc}%08x, expected 0x$at%08x")
      assert(r.word(DataWord) == 0x1111111111111111L, s"$name disturbed memory")
      assert(r.word(DataWord + 1) == 0x2222222222222222L, s"$name disturbed memory")
    }
  }

  test("a misaligned atomic raises MISALIGNED_LOAD even though it also writes") {
    // Section 4 is explicit about this: a read-modify-write reads first, so
    // the cause is the load cause at both sizes.
    for ((size, offset) <- Seq((Isa.SIZE_D, 4), (Isa.SIZE_W, 2), (Isa.SIZE_W, 1))) {
      var at = 0L
      val r = cosim(
        data = Map(DataWord -> 0x1111111111111111L),
        readRange = DataWord until (DataWord + 1)
      ) { a =>
        import a._
        li(s0, DataByte + offset)
        li(s1, 1)
        at = a.pc
        ldadd(t0, s0, s1, size)
        halt()
      }
      assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD,
        s"an atomic of size $size at offset $offset gave $r")
      assert(r.trapPc == at)
      assert(r.word(DataWord) == 0x1111111111111111L, "a trapping atomic must not write")
    }
  }

  test("a misaligned ordered access traps with the load or the store cause") {
    for ((isLoad, cause) <- Seq((true, Isa.Cause.MISALIGNED_LOAD), (false, Isa.Cause.MISALIGNED_STORE))) {
      val r = cosim(data = Map(DataWord -> 0x1111111111111111L)) { a =>
        import a._
        li(s0, DataByte + 4)
        li(t0, 7)
        if (isLoad) ldOrd(t1, s0, Isa.SIZE_D, Isa.Ord.SEQ)
        else stOrd(t0, s0, Isa.SIZE_D, Isa.Ord.SEQ)
        halt()
      }
      assert(r.trapped && r.cause == cause, s"an ordered access gave $r")
    }
  }

  // =====================================================================
  // Stopping precisely
  // =====================================================================

  test("HALT is not a trap") {
    val r = cosim() { a =>
      import a._
      li(a0, 1)
      halt()
    }
    assert(r.halted, "the core must stop")
    assert(!r.trapped, "HALT is not a trap")
    assert(r.cause == Isa.Cause.NONE, s"cause was ${r.causeName}")
    expectReg(r, 1, 1)
  }

  test("HALT itself does not retire but everything before it does") {
    val asm = Assembler.build(0) { a =>
      import a._
      li(a0, 1) // one instruction
      nop()
      nop()
      halt()
    }
    val r = cosimProgram(asm.assemble())
    assert(r.retired == 3, s"retired ${r.retired}, expected three instructions before the halt")
  }

  test("instructions already past execute still commit when a trap stops the core") {
    // The two instructions ahead of the trapping one are in the memory and
    // writeback stages when it is caught. Section 6 says they commit, so a
    // flush that reached backwards would lose both the store and the register
    // write below.
    val r = cosim(
      data = Map(DataWord -> 0L),
      readRange = DataWord until (DataWord + 1)
    ) { a =>
      import a._
      li(s0, DataByte)
      li(t0, 0x1234)
      li(s1, DataByte + 1) // a misaligned address for later
      std(t0, s0, 0)
      li(a1, 0x42)
      ldd(a0, s1, 0)       // traps
      li(a2, 0x99)         // must not run
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD, s"got $r")
    expectReg(r, 2, 0x42, "the instruction before the trap must have committed")
    expectReg(r, 1, 0, "the trapping load must not write its destination")
    expectReg(r, 3, 0, "the instruction after the trap must not run")
    assert(r.word(DataWord) == 0x1234, "the store before the trap must have committed")
  }

  test("an illegal instruction in the shadow of a taken branch never traps") {
    // A killed instruction has not executed, so it cannot raise anything. If
    // the decoder raised the trap instead of the execute stage, this program
    // would stop with ILLEGAL rather than halting cleanly.
    val r = cosim() { a =>
      import a._
      li(a0, 7)
      cmpEqi(p0, zero, 0)
      bp(p0, "after")
      word(0x3f << Isa.OP_LO)
      word(0x2f << Isa.OP_LO)
      label("after")
      halt()
    }
    assert(!r.trapped, s"a killed illegal instruction must not trap: $r")
    expectReg(r, 1, 7)
  }

  test("the first of two traps is the one reported") {
    var first = 0L
    val r = cosim() { a =>
      import a._
      li(s0, DataByte + 1)
      first = a.pc
      ldd(a0, s0, 0)            // misaligned load
      word(0x3f << Isa.OP_LO)   // illegal, but never reached
      halt()
    }
    assert(r.trapped && r.cause == Isa.Cause.MISALIGNED_LOAD, s"got $r")
    assert(r.trapPc == first, f"stopped at 0x${r.trapPc}%08x, expected 0x$first%08x")
  }
}
