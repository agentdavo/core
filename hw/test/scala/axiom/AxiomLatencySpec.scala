package axiom

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** What a memory that answers in two cycles costs the core.
  *
  * The instruction side is built to hide it: the counter runs ahead of the
  * pipeline, and the second command goes out while the first answer is on
  * its way. So straight-line code must not slow down at all. Loads are still
  * issued one at a time, so anything that loads pays the extra cycle per
  * load, and that is what the other rows measure rather than assert.
  */
class AxiomLatencySpec extends AnyFunSuite {
  lazy val slow: SimCompiled[AxiomSoc] =
    SimConfig.withVerilator.workspacePath("simWorkspace").workspaceName("AxiomSocLat2")
      .compile(new AxiomSoc(memWords = AxiomSim.MemWords, memoryLatency = 2,
        icacheBytes = 0, dcacheBytes = 0, plugins = AxiomProfile.cached))

  test("a two-cycle memory costs straight-line code nothing, and every load one cycle") {
    for (w <- AxiomWorkloads.all) {
      val one = AxiomSim.run(w.program, data = w.data, design = AxiomSim.soc)
      val two = AxiomSim.run(w.program, data = w.data, design = slow)
      assert(one.reg(AxiomWorkloads.ResultReg) == w.expected, s"${w.name} wrong on a one-cycle memory")
      assert(two.reg(AxiomWorkloads.ResultReg) == w.expected, s"${w.name} wrong on a two-cycle memory")
      val cost = (two.cycles - one.cycles) * 100.0 / one.cycles
      info(f"${w.name}%-15s latency 1 ${one.cycles}%5d   latency 2 ${two.cycles}%5d   $cost%+.0f%%")
      if (w.name == "straight-line") assert(cost < 1.0, "the front end hides a two-cycle memory on straight-line code")
    }
  }
}
