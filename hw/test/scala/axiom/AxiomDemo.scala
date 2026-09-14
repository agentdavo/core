package axiom

/** Assemble a small Axiom-64 program, show it, run it on the A1 core, and
  * print what came out.
  *
  * {{{
  *   sbt "Test/runMain axiom.AxiomDemo"
  * }}}
  */
object AxiomDemo extends App {

  val ScratchByte = 4096
  val ScratchWord = ScratchByte / 8

  val program = Assembler() { a =>
    import a._

    // Sum the squares of 1 to n, keeping the running total in memory so the
    // load/store path and the interlock both get used.
    li(a0, 20)          // n
    li(t0, 1)           // i
    li(t4, ScratchByte)   // scratch pointer
    li(t1, 0)
    std(t1, t4, 0)        // total = 0

    label("loop")
    cmpGt(p0, t0, a0)
    bp(p0, "done")
    mul(t2, t0, t0)     // i * i
    ldd(t3, t4, 0)        // the load feeds the add directly, so this interlocks
    add(t3, t3, t2)
    std(t3, t4, 0)
    addi(t0, t0, 1)
    b("loop")

    label("done")
    ldd(a1, t4, 0)
    halt()
  }

  println("Axiom-64 program")
  println("----------------")
  println(Assembler.listing(program))

  val result = AxiomSim.run(program, readRange = ScratchWord until (ScratchWord + 1))
  val expected = (1 to 20).map(i => i.toLong * i).sum

  println()
  println("Result")
  println("------")
  println(s"stopped:  ${if (result.trapped) s"trap ${result.causeName}" else "halt"} " +
    f"at 0x${result.trapPc}%016x")
  println(f"retired:  ${result.retired} instructions in ${result.cycles} cycles " +
    f"(${result.retired.toDouble / result.cycles}%.2f per cycle)")
  println(s"memory:   doubleword $ScratchWord = ${result.word(ScratchWord)}, expected $expected")
  println()
  println("Registers")
  println("---------")
  result.regs.zipWithIndex.grouped(4).foreach { row =>
    println(row.map { case (v, i) => f"${Isa.regName(i)}%-5s 0x$v%016x" }.mkString("   "))
  }
  println()
  println(f"Predicates p7..p0: ${result.predicates.toBinaryString.reverse.padTo(8, '0').reverse}")

  require(result.word(ScratchWord) == expected, "the demo program computed the wrong answer")
  require(result.reg(2) == expected, "a1 should hold the total")
}
