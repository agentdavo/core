package axiom

/** The first thing to run: does the plugin-composed core execute at all. */
class AxiomSmokeSpec extends AxiomSpec {

  test("arithmetic, a wide constant and a halt") {
    val r = cosim() { a =>
      import a._
      li(a0, 7)
      li(a1, 5)
      add(a2, a0, a1)
      sub(t0, a0, a1)
      li(t1, 0x0123456789abcdefL)
      halt()
    }
    expectReg(r, 1, 7)
    expectReg(r, 2, 5)
    expectReg(r, 3, 12)
    expectReg(r, 8, 2)
    expectReg(r, 9, 0x0123456789abcdefL)
  }
}
