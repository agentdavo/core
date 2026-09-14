package axiom

/** The first level caches.
  *
  * Two questions, and they need different tests. Does the core still compute
  * the right answers when its memory is a cache rather than an array, and does
  * the cache actually earn the block RAM it costs.
  *
  * The first is equality against the same program on a tightly coupled memory:
  * same registers, same memory, same retired stream. The second is a cycle
  * count against the same slow memory with no cache in front of it, which is
  * what the caches have to beat for any of this to have been worth building.
  */
class AxiomCacheSpec extends AxiomSpec {

  private def sameAnswers(name: String)(build: Assembler => Unit): Unit =
    test(name) {
      val program = Assembler()(build)
      val plain = AxiomSim.run(program, readRange = DataWord until (DataWord + 32))
      val cached = AxiomSim.run(program, design = AxiomSim.socCached,
        readRange = DataWord until (DataWord + 32))

      assert(!plain.trapped && !cached.trapped, s"trapped:%nplain $plain%ncached $cached")
      assert(plain.regs.sameElements(cached.regs),
        s"registers differ:%nplain  ${plain.regs.mkString(",")}%ncached ${cached.regs.mkString(",")}")
      assert(plain.memory == cached.memory, "memory differs")
      assert(plain.retireTrace == cached.retireTrace, "a different instruction stream retired")
    }

  sameAnswers("arithmetic, so only the instruction cache is exercised") { a =>
    import a._
    li(a0, 1)
    for (_ <- 0 until 20) { addi(a0, a0, 3); shli(a1, a0, 2); sub(a2, a1, a0) }
    halt()
  }

  sameAnswers("loads and stores of every width") { a =>
    import a._
    li(s0, DataByte)
    li(a0, 0x0123456789abcdefL)
    std(a0, s0, 0)
    ldd(a1, s0, 0)
    stw(a1, s0, 8); ldwu(a2, s0, 8)
    sth(a2, s0, 16); ldhu(a3, s0, 16)
    stb(a3, s0, 24); ldbu(a4, s0, 24)
    halt()
  }

  sameAnswers("a store then a load of the same address, which must not be stale") { a =>
    import a._
    li(s0, DataByte)
    for (i <- 0 until 8) {
      li(a0, 0x1000 + i)
      std(a0, s0, i * 8)
      ldd(a1, s0, i * 8)
      add(a2, a2, a1)
    }
    halt()
  }

  sameAnswers("walking a range larger than the cache, so lines are replaced") { a =>
    import a._
    li(s0, DataByte)
    li(t0, 0)
    label("loop")
    std(t0, s0, 0)
    addi(s0, s0, 64)
    addi(t0, t0, 1)
    cmpi(Isa.Cc.NE, p0, t0, 96)
    bp(p0, "loop")
    halt()
  }

  sameAnswers("pairs and atomics through the cache") { a =>
    import a._
    li(sp, DataByte + 256)
    li(a0, 0x1111); li(a1, 0x2222)
    stp(a0, a1, sp, -16, Isa.Mode.PRE)
    ldp(t0, t1, sp, 16, Isa.Mode.POST)
    li(s0, DataByte)
    li(a2, 5)
    std(a2, s0, 0)
    ldadd(t2, s0, a2, Isa.SIZE_D)
    ldd(t3, s0, 0)
    halt()
  }

  test("random programs agree with a tightly coupled memory") {
    for (seed <- 3000 until 3006) {
      val program = RandomAxiomProgram.generate(
        new scala.util.Random(seed), groups = 40, memoryBias = 3)
      val plain = AxiomSim.run(program, readRange = DataWord until (DataWord + 32))
      val cached = AxiomSim.run(program, design = AxiomSim.socCached,
        readRange = DataWord until (DataWord + 32))
      assert(plain.regs.sameElements(cached.regs), s"registers differ at seed $seed")
      assert(plain.memory == cached.memory, s"memory differs at seed $seed")
      assert(plain.retireTrace == cached.retireTrace, s"stream differs at seed $seed")
    }
  }

  test("the caches beat the same memory without them") {
    // A loop small enough to sit in the instruction cache, walking data it has
    // already touched. This is the case caches exist for, and if it does not
    // win here it does not win anywhere.
    val program = Assembler() { a =>
      import a._
      li(s0, DataByte)
      li(t0, 40)
      label("loop")
      ldd(a0, s0, 0)
      addi(a0, a0, 1)
      std(a0, s0, 0)
      ldd(a1, s0, 8)
      add(a2, a2, a1)
      addi(t0, t0, -1)
      cmpi(Isa.Cc.NE, p0, t0, 0)
      bp(p0, "loop")
      halt()
    }

    val cached = AxiomSim.run(program, design = AxiomSim.socCached)
    val uncached = AxiomSim.run(program, design = AxiomSim.socUncached)
    val tcm = AxiomSim.run(program)

    assert(cached.regs.sameElements(tcm.regs), "the cached core must agree with the plain one")
    assert(uncached.regs.sameElements(tcm.regs), "the stalling core must agree too")

    info(f"tightly coupled memory: ${tcm.cycles}%5d cycles")
    info(f"caches, slow memory:    ${cached.cycles}%5d cycles")
    info(f"no caches, slow memory: ${uncached.cycles}%5d cycles")
    info(f"the caches are worth ${uncached.cycles.toDouble / cached.cycles}%.2f times")

    assert(cached.cycles < uncached.cycles,
      s"the caches cost ${cached.cycles} cycles against ${uncached.cycles} without them")
  }
}
