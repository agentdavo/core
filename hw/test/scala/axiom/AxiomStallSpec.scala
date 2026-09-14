package axiom

/** The stallable memory protocol.
  *
  * A memory that answers the cycle after it is asked is the easy case and the
  * one every other test runs. These run the same work through a memory that
  * refuses commands pseudo-randomly, which is what a cache miss, a shared
  * interconnect or anything with a queue behind it looks like from the core.
  *
  * The bar is equality, not survival: the same program must reach the same
  * registers and the same memory, retiring the same instructions in the same
  * order. Only the cycle count may move.
  */
class AxiomStallSpec extends AxiomSpec {

  private def bothWays(name: String)(build: Assembler => Unit): Unit =
    test(name) {
      val program = Assembler()(build)
      val fast = AxiomSim.run(program, readRange = DataWord until (DataWord + 32))
      val slow = AxiomSim.run(program, design = AxiomSim.socStalling,
        readRange = DataWord until (DataWord + 32))

      assert(!fast.trapped && !slow.trapped, s"trapped:%nfast $fast%nslow $slow")
      assert(fast.regs.sameElements(slow.regs),
        s"registers differ:%nfast ${fast.regs.mkString(",")}%nslow ${slow.regs.mkString(",")}")
      assert(fast.memory == slow.memory, "memory differs")
      assert(fast.retireTrace == slow.retireTrace,
        s"a different instruction stream retired:%nfast ${fast.retireTrace.length} " +
          s"instructions%nslow ${slow.retireTrace.length}")
      assert(fast.retired == slow.retired, s"retired ${fast.retired} against ${slow.retired}")
      assert(slow.cycles > fast.cycles, "a stalling memory that costs nothing is not stalling")
    }

  bothWays("arithmetic alone, so only the fetch port stalls") { a =>
    import a._
    li(a0, 1)
    for (_ <- 0 until 12) { addi(a0, a0, 3); shli(a1, a0, 2); sub(a2, a1, a0) }
    halt()
  }

  bothWays("loads and stores, so both ports stall together") { a =>
    import a._
    li(s0, DataByte)
    li(a0, 0x0123456789abcdefL)
    std(a0, s0, 0)
    ldd(a1, s0, 0)
    stw(a1, s0, 8)
    ldwu(a2, s0, 8)
    stb(a2, s0, 16)
    ldbu(a3, s0, 16)
    halt()
  }

  bothWays("a load feeding the instruction behind it, over and over") { a =>
    import a._
    li(s0, DataByte)
    li(a0, 7)
    std(a0, s0, 0)
    for (_ <- 0 until 8) { ldd(a1, s0, 0); addi(a1, a1, 1); std(a1, s0, 0) }
    halt()
  }

  bothWays("pairs, which take two passes through the memory stage") { a =>
    import a._
    li(sp, DataByte + 512)
    li(a0, 0x1111)
    li(a1, 0x2222)
    stp(a0, a1, sp, -16, Isa.Mode.PRE)
    ldp(t0, t1, sp, 16, Isa.Mode.POST)
    stp(t0, t1, sp, 0)
    ldp(t2, t3, sp, 0)
    halt()
  }

  bothWays("an atomic, which reads and writes in one instruction") { a =>
    import a._
    li(s0, DataByte)
    li(a0, 10)
    std(a0, s0, 0)
    li(a1, 5)
    for (_ <- 0 until 4) ldadd(t0, s0, a1, Isa.SIZE_D)
    ldd(a2, s0, 0)
    halt()
  }

  bothWays("branches, so a redirect lands while a fetch is outstanding") { a =>
    import a._
    li(a0, 0)
    li(t0, 20)
    label("loop")
    addi(a0, a0, 3)
    addi(t0, t0, -1)
    cmpi(Isa.Cc.NE, p0, t0, 0)
    bp(p0, "loop")
    bl("routine")
    halt()
    label("routine")
    addi(a0, a0, 1)
    ret()
  }

  test("random programs agree through a stalling memory") {
    for (seed <- 2000 until 2006) {
      val program = RandomAxiomProgram.generate(
        new scala.util.Random(seed), groups = 40, memoryBias = 3)
      val fast = AxiomSim.run(program, readRange = DataWord until (DataWord + 32))
      val slow = AxiomSim.run(program, design = AxiomSim.socStalling,
        readRange = DataWord until (DataWord + 32))
      assert(fast.regs.sameElements(slow.regs), s"registers differ at seed $seed")
      assert(fast.memory == slow.memory, s"memory differs at seed $seed")
      assert(fast.retireTrace == slow.retireTrace, s"stream differs at seed $seed")
    }
  }
}
