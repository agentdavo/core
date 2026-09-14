package axiom

import scala.util.Random

/** Randomized co-simulation of the Axiom-64 core against the reference model.
  *
  * Seeds are fixed so a failure is reproducible.
  */
class AxiomCosimSpec extends AxiomSpec {

  private def sweep(name: String, count: Int, seedBase: Int)(build: Random => Array[Int]): Unit = {
    var instructions = 0L
    var cycles = 0L
    for (i <- 0 until count) {
      val seed = seedBase + i
      val program = build(new Random(seed))
      val result =
        try cosimProgram(program, readRange = RandomAxiomProgram.dataRange, maxCycles = 200000)
        catch {
          case e: Throwable => throw new AssertionError(s"$name failed with seed $seed: ${e.getMessage}", e)
        }
      instructions += result.retired
      cycles += result.cycles
    }
    info(f"$name: $count programs, $instructions instructions in $cycles cycles " +
      f"(${instructions.toDouble / cycles}%.2f per cycle)")
  }

  test("random programs over the whole register file") {
    sweep("wide register pool", 30, 1000) { rng =>
      RandomAxiomProgram.generate(rng, groups = 100)
    }
  }

  test("random programs over a narrow register pool") {
    // Restricting writes to six registers makes nearly every instruction
    // depend on one of the three before it, so forwarding and the interlock
    // are exercised almost every cycle.
    sweep("narrow register pool", 30, 2000) { rng =>
      RandomAxiomProgram.generate(rng, groups = 100, writePool = Seq(1, 2, 3, 4, 5, 6))
    }
  }

  test("memory-heavy random programs") {
    sweep("memory heavy", 25, 3000) { rng =>
      RandomAxiomProgram.generate(rng, groups = 110, writePool = (1 to 10), memoryBias = 3)
    }
  }

  test("short random programs, many of them") {
    sweep("short programs", 40, 4000) { rng =>
      RandomAxiomProgram.generate(rng, groups = 25, writePool = Seq(1, 2, 3))
    }
  }
}
