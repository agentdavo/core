package axiom

/** Divide and remainder through the whole core.
  *
  * The divider itself is checked at its own ports in `DivideUnitSpec`; this is
  * about the parts a component test cannot see: that the decoder knows the
  * opcode writes a register and reads two, that the stall is invisible to
  * everything behind it, and that a result produced sixty-five cycles late
  * forwards like any other.
  */
class AxiomDivideSpec extends AxiomSpec {

  // `dividend` and `divisor` rather than `a` and `b`, because the assembler's
  // register names are imported into the same scope and `b` is the branch.
  private def expect(name: String, fn: Int, dividend: Long, divisor: Long): Unit = {
    val program = Assembler() { asm =>
      import asm._
      li(a0, dividend)
      li(a1, divisor)
      asm.word(Isa.encAluDiv(fn, 8, 1, 2)) // t0 = a0 <op> a1
      halt()
    }
    val r = AxiomSim.run(program)
    val want = Isa.divide(fn, dividend, divisor)
    assert(!r.trapped, s"$name trapped: $r")
    assert(r.regs(8) == want,
      f"$name: 0x$dividend%016x ${Isa.DivFn.NAMES(fn)} 0x$divisor%016x " +
        f"gave 0x${r.regs(8)}%016x, want 0x$want%016x")
  }

  test("every form on ordinary operands") {
    for (fn <- Isa.DivFn.ALL.toSeq.sorted) {
      expect("ordinary", fn, 1000, 7)
      expect("negative dividend", fn, -1000, 7)
      expect("negative divisor", fn, 1000, -7)
      expect("both negative", fn, -1000, -7)
    }
  }

  test("division by zero is defined, not a trap") {
    for (fn <- Isa.DivFn.ALL.toSeq.sorted) {
      expect("by zero", fn, 1234, 0)
      expect("zero by zero", fn, 0, 0)
      expect("negative by zero", fn, -1234, 0)
    }
  }

  test("the signed overflow is defined, not a trap") {
    for (fn <- Isa.DivFn.ALL.toSeq.sorted) {
      expect("most negative by minus one", fn, Long.MinValue, -1)
      expect("most negative word by minus one", fn, Int.MinValue.toLong, -1)
    }
  }

  test("the W forms ignore the high half of both operands") {
    for (fn <- Seq(Isa.DivFn.DIVW, Isa.DivFn.DIVUW, Isa.DivFn.REMW, Isa.DivFn.REMUW)) {
      expect("high bits set", fn, 0x1234567800000064L, 7)
      expect("high bits set in divisor", fn, 1000, 0xabcdef0000000007L)
      expect("sign in the low half only", fn, 0x00000000fffffc18L, 7)
    }
  }

  test("a divide stalls everything behind it and forwards its result") {
    // The consumer is the instruction immediately after, so it depends on a
    // result that arrives sixty-five cycles later than an ALU result would.
    val program = Assembler() { asm =>
      import asm._
      li(a0, 1000)
      li(a1, 7)
      div(t0, a0, a1)
      addi(t1, t0, 1)       // forwarded from execute
      rem(t2, a0, a1)
      add(t3, t1, t2)       // forwarded across a second division
      halt()
    }
    val r = AxiomSim.run(program)
    assert(!r.trapped, s"$r")
    assert(r.regs(8) == 142, s"t0 was ${r.regs(8)}")
    assert(r.regs(9) == 143, s"t1 was ${r.regs(9)}")
    assert(r.regs(10) == 6, s"t2 was ${r.regs(10)}")
    assert(r.regs(11) == 149, s"t3 was ${r.regs(11)}")
  }

  test("a core built without a divider rejects the whole opcode") {
    val program = Assembler() { asm =>
      import asm._
      li(a0, 100)
      li(a1, 7)
      div(t0, a0, a1)
      halt()
    }
    val withIt = AxiomSim.run(program)
    assert(!withIt.trapped && withIt.regs(8) == 14, s"the default core should divide: $withIt")

    val without = AxiomSim.run(program, design = AxiomSim.socWithoutDivider)
    assert(without.trapped, s"divide should be illegal in this build: $without")
    assert(without.cause == Isa.Cause.ILLEGAL, s"cause was ${without.causeName}")
    assert(without.regs(8) == 0, "the rejected divide must not write its destination")
  }

  test("random dividends and divisors agree with the reference") {
    val rng = new scala.util.Random(31)
    for (_ <- 0 until 30) {
      val fn = Isa.DivFn.ALL.toSeq(rng.nextInt(8))
      val dividend = rng.nextLong()
      val divisor = if (rng.nextInt(5) == 0) 0L else rng.nextLong() >> rng.nextInt(60)
      expect("random", fn, dividend, divisor)
    }
  }
}
