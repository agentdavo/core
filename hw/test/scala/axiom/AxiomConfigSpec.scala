package axiom

/** Tests for the knobs in [[AxiomParam]].
  *
  * Each of these elaborates and verilates a different core, so they are kept to
  * the three that change behaviour. They exist because a configuration flag
  * nobody exercises is a flag that does not work.
  */
class AxiomConfigSpec extends AxiomSpec {

  test("a core built without a multiplier rejects the multiply functions") {
    val program = Assembler() { a =>
      import a._
      li(a0, 6)
      li(a1, 7)
      mul(a2, a0, a1)
      halt()
    }

    val withMultiplier = AxiomSim.run(program)
    assert(!withMultiplier.trapped, s"the default core should multiply: $withMultiplier")
    expectReg(withMultiplier, 3, 42)

    val without = AxiomSim.run(program, design = AxiomSim.socWithoutMultiplier)
    assert(without.trapped, s"mul should be illegal without a multiplier: $without")
    assert(without.cause == Isa.Cause.ILLEGAL, s"cause was ${without.causeName}")
    expectReg(without, 3, 0, "the multiply must not write its destination")
    // Two li pairs of two instructions each retire; the multiply does not.
    assert(without.retired == 2, s"retired ${without.retired}")
  }

  test("a core built without atomics rejects the atomic opcodes") {
    val program = Assembler() { a =>
      import a._
      li(a0, DataByte)
      li(a1, 5)
      ldadd(a2, a0, a1, Isa.SIZE_D)
      halt()
    }

    val withAtomics = AxiomSim.run(program, readRange = DataWord until (DataWord + 1))
    assert(!withAtomics.trapped, s"the default core should support atomics: $withAtomics")
    assert(withAtomics.word(DataWord) == 5L, "the atomic add should have reached memory")

    val without = AxiomSim.run(program, design = AxiomSim.socWithoutAtomics,
      readRange = DataWord until (DataWord + 1))
    assert(without.trapped, s"an atomic should be illegal in this build: $without")
    assert(without.cause == Isa.Cause.ILLEGAL, s"cause was ${without.causeName}")
    assert(without.word(DataWord) == 0L, "the rejected atomic must not have touched memory")
  }

  test("the reset vector is configurable") {
    val base = AxiomSim.AltResetVector
    val program = Assembler(base) { a =>
      import a._
      movz(a0, 0)
      bl("routine")
      addi(a0, a0, 1)
      halt()

      label("routine")
      addi(a0, a0, 10)
      ret()
    }

    val rtl = AxiomSim.run(program, design = AxiomSim.socAltReset, loadAtWord = base / 8)
    val emu = AxiomSim.reference(program, resetVector = base, loadAtByte = base)

    assert(rtl.halted && !rtl.trapped, s"got $rtl")
    // Program counter relative call and return still work away from zero, and
    // the link register carries an address in the new range.
    expectReg(rtl, 1, 11)
    for (i <- 0 until Isa.REG_COUNT) {
      assert(rtl.reg(i) == emu.regs(i),
        f"${Isa.regName(i)}%s: RTL 0x${rtl.reg(i)}%016x, reference 0x${emu.regs(i)}%016x")
    }
    assert(rtl.retired == emu.retired, s"retired ${rtl.retired} against ${emu.retired}")
    assert(rtl.trapPc == emu.trapPc, f"stop pc 0x${rtl.trapPc}%016x against 0x${emu.trapPc}%016x")
  }
}
