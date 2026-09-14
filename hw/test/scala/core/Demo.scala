package core

/** Assemble a small program, show it, run it on the C1 core, and print what
  * came out.
  *
  * {{{
  *   sbt "Test/runMain core.Demo"
  * }}}
  */
object Demo extends App {

  val program = Assembler() { a =>
    import a._
    li(a1, 24)      // n
    movi(t0, 0)     // f(0)
    movi(t1, 1)     // f(1)

    label("loop")
    beqz(a1, "done")
    add(t2, t0, t1)
    mov(t0, t1)
    mov(t1, t2)
    addi(a1, a1, -1)
    jmp("loop")

    label("done")
    mov(a0, t0)     // result
    li(gp, 2048)
    stw(a0, gp, 0)  // and leave it in memory
    halt()
  }

  println("CORE-32 program")
  println("---------------")
  println(Assembler.listing(program))

  val result = Sim.run(program, readRange = 512 until 513)

  println()
  println("Result")
  println("------")
  println(s"stopped:  ${if (result.trapped) s"trap ${result.causeName}" else "halt"} " +
    f"at 0x${result.trapPc}%08x")
  println(f"retired:  ${result.retired} instructions in ${result.cycles} cycles " +
    f"(${result.retired.toDouble / result.cycles}%.2f per cycle)")
  println(f"memory:   word 512 = ${result.word(512)} (fibonacci 24 = 46368)")
  println()
  println("Registers")
  println("---------")
  result.regs.zipWithIndex.grouped(4).foreach { row =>
    println(row.map { case (v, i) => f"${Isa.regName(i)}%-5s 0x$v%08x" }.mkString("   "))
  }
}
