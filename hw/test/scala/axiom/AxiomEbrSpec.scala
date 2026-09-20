package axiom

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The tightly coupled memory as block RAM with its output register on.
  *
  * Two builds: the two-cycle memory as a model, and the same memory as the
  * DP16KD cells the part has, simulated through `hw/verilog/DP16KD.v`. The
  * model is what says how the memory behaves; the cells are what synthesis
  * gets. They must agree on every workload to the cycle, which checks the
  * wiring round the cell — the bit address, the byte enables, the bank
  * select delayed to match the data — against the model. What it cannot
  * check is the model against the vendor's cell, since yosys ships that as
  * an empty stub; the bit-address convention is taken from yosys's own
  * mapping of inferred memories onto the cell.
  */
class AxiomEbrSpec extends AnyFunSuite {

  private def build(name: String, native: Boolean): SimCompiled[AxiomSoc] =
    SimConfig.withVerilator.workspacePath("simWorkspace").workspaceName(name)
      .compile(new AxiomSoc(memWords = AxiomSim.MemWords, tcmTwoCycle = true, ebrNative = native))

  lazy val model = build("AxiomSocTcm2", native = false)
  lazy val cells = build("AxiomSocEbr", native = true)

  test("the two-cycle memory runs every workload, as a model and as the cells") {
    for (w <- AxiomWorkloads.all) {
      val one = AxiomSim.run(w.program, data = w.data, design = AxiomSim.soc)
      val m = AxiomSim.run(w.program, data = w.data, design = model)
      val c = AxiomSim.run(w.program, data = w.data, design = cells)
      assert(m.reg(AxiomWorkloads.ResultReg) == w.expected, s"${w.name} wrong on the model")
      assert(c.reg(AxiomWorkloads.ResultReg) == w.expected, s"${w.name} wrong on the cells")
      assert(c.regs.sameElements(m.regs), s"${w.name}: the cells and the model disagree on a register")
      assert(c.cycles == m.cycles, s"${w.name}: the cells took ${c.cycles} cycles, the model ${m.cycles}")
      val cost = (m.cycles - one.cycles) * 100.0 / one.cycles
      info(f"${w.name}%-15s one cycle ${one.cycles}%5d   two cycles ${m.cycles}%5d   $cost%+.0f%%")
    }
  }

  test("random programs agree with the plain memory through the cells") {
    val DataWord = RandomAxiomProgram.DataByte / 8
    val readRange = DataWord until (DataWord + 32)
    for (seed <- 4000 until 4008) {
      val program = RandomAxiomProgram.generate(
        new scala.util.Random(seed), groups = 40, memoryBias = 3)
      val plain = AxiomSim.run(program, readRange = readRange)
      val c = AxiomSim.run(program, design = cells, readRange = readRange)
      assert(c.halted, s"seed $seed did not halt on the cells")
      assert(plain.regs.sameElements(c.regs), s"registers differ at seed $seed")
      assert(plain.memory == c.memory, s"memory differs at seed $seed")
      assert(plain.retireTrace == c.retireTrace, s"stream differs at seed $seed")
    }
  }
}
