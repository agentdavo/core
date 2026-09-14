package axiom

/** How big the caches should be, measured.
  *
  * Guessing four kilobytes each is a way of not answering. These run the same
  * work through every geometry worth building and report cycles, so the answer
  * is a number and the shape of the curve is visible: where it stops improving
  * is where the block RAM stops earning its place.
  *
  * The workload matters more than the size, so there are two: one that fits in
  * anything, and one that does not fit in the small ones.
  */
class AxiomCacheSizeSpec extends AxiomSpec {

  /** A tight loop over a small array: the case caches are good at. */
  private def tightLoop = Assembler() { a =>
    import a._
    li(s0, DataByte)
    li(t0, 60)
    label("loop")
    ldd(a0, s0, 0)
    ldd(a1, s0, 8)
    add(a2, a0, a1)
    std(a2, s0, 16)
    addi(t0, t0, -1)
    cmpi(Isa.Cc.NE, p0, t0, 0)
    bp(p0, "loop")
    halt()
  }

  /** A sweep over more memory than the small caches hold, with a loop body
    * long enough to matter for the instruction side too.
    */
  private def sweep = Assembler() { a =>
    import a._
    li(s0, DataByte)
    li(t0, 24)
    label("outer")
    li(s1, DataByte)
    li(t1, 64)
    label("inner")
    ldd(a0, s1, 0)
    addi(a0, a0, 1)
    std(a0, s1, 0)
    addi(s1, s1, 64)
    addi(t1, t1, -1)
    cmpi(Isa.Cc.NE, p1, t1, 0)
    bp(p1, "inner")
    addi(t0, t0, -1)
    cmpi(Isa.Cc.NE, p0, t0, 0)
    bp(p0, "outer")
    halt()
  }

  private def cycles(program: Array[Int], i: Int, d: Int, line: Int = 32): Long = {
    val r = AxiomSim.run(program, design = AxiomSim.socSized(i, d, line), maxCycles = 400000)
    assert(r.halted, s"did not finish with ${i}B/${d}B/${line}B: $r")
    r.cycles
  }

  test("cache size against cycles") {
    val reference = AxiomSim.run(tightLoop).cycles
    info(f"tight loop, tightly coupled memory: $reference%6d cycles")
    for ((i, d) <- Seq((0, 0), (512, 512), (1024, 1024), (2048, 2048), (4096, 4096), (8192, 8192))) {
      val c = cycles(tightLoop, i, d)
      info(f"tight loop, ${i}%5dB icache ${d}%5dB dcache: $c%6d cycles")
    }

    val sweepReference = AxiomSim.run(sweep).cycles
    info(f"sweep, tightly coupled memory:      $sweepReference%6d cycles")
    for ((i, d) <- Seq((0, 0), (512, 512), (1024, 1024), (2048, 2048), (4096, 4096), (8192, 8192))) {
      val c = cycles(sweep, i, d)
      info(f"sweep, ${i}%5dB icache ${d}%5dB dcache: $c%6d cycles")
    }
  }

  test("line size against cycles, at four kilobytes each") {
    for (line <- Seq(16, 32, 64, 128)) {
      info(f"tight loop, ${line}%3dB line: ${cycles(tightLoop, 4096, 4096, line)}%6d cycles")
    }
    for (line <- Seq(16, 32, 64, 128)) {
      info(f"sweep,      ${line}%3dB line: ${cycles(sweep, 4096, 4096, line)}%6d cycles")
    }
  }

  test("splitting a fixed budget between the two caches") {
    // Eight kilobytes of block RAM, divided every way.
    // Powers of two only, so the totals are not all equal; the total is
    // printed so the trade is visible anyway.
    for ((i, d) <- Seq((1024, 4096), (2048, 4096), (4096, 4096),
                       (4096, 2048), (4096, 1024), (8192, 2048), (2048, 8192))) {
      info(f"sweep, ${i}%5dB icache ${d}%5dB dcache (${(i + d) / 1024}%2dkB total): ${
        cycles(sweep, i, d)}%6d cycles")
    }
  }
}
