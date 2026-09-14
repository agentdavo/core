package core

/** Tests for the knobs in [[CoreConfig]]. Each one elaborates and verilates a
  * different core, so they are kept to the two that change behaviour.
  */
class ConfigSpec extends CoreSpec {

  test("a core built without a multiplier rejects the multiply opcodes") {
    val program = Assembler() { a =>
      import a._
      li(a0, 6)
      li(a1, 7)
      mul(a2, a0, a1)
      halt()
    }

    val withMultiplier = Sim.run(program)
    assert(!withMultiplier.trapped, s"the default core should multiply: $withMultiplier")
    expectReg(withMultiplier, 3, 42)

    val without = Sim.run(program, design = Sim.socWithoutMultiplier)
    assert(without.trapped, s"mul should be illegal without a multiplier: $without")
    assert(without.cause == Isa.Cause.ILLEGAL, s"cause was ${without.causeName}")
    expectReg(without, 3, 0, "the multiply must not write its destination")
  }

  test("the reset vector is configurable") {
    val base = Sim.AltResetVector
    val program = Assembler(base) { a =>
      import a._
      movi(a0, 0)
      call("routine")
      addi(a0, a0, 1)
      halt()

      label("routine")
      addi(a0, a0, 10)
      ret()
    }

    val rtl = Sim.run(program, design = Sim.socAltReset, loadAtWord = base / 4)
    val emu = Sim.reference(program, resetVector = base, loadAtWord = base / 4)

    assert(rtl.halted && !rtl.trapped, s"got $rtl")
    expectReg(rtl, 1, 11, "pc-relative call and return from a non-zero reset vector")
    for (i <- 0 until Isa.REG_COUNT) {
      assert(rtl.reg(i) == emu.regs(i), f"${Isa.regName(i)}: RTL 0x${rtl.reg(i)}%08x, reference 0x${emu.regs(i)}%08x")
    }
    assert(rtl.retired == emu.retired, s"retired ${rtl.retired} vs ${emu.retired}")
  }
}
