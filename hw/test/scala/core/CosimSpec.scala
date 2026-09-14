package core

import scala.util.Random

/** Randomized co-simulation.
  *
  * Each program is run on the RTL and on the reference model, and the two are
  * compared on the whole architectural state: every register, the stop status
  * and PC, the retired instruction count, and the whole scratch region of
  * memory. The comparison lives in [[CoreSpec.cosimProgram]].
  *
  * The seeds are fixed so a failure is reproducible.
  */
class CosimSpec extends CoreSpec {

  private def sweep(name: String, count: Int, seedBase: Int)(
      build: Random => Array[Int]): Unit = {
    var instructions = 0L
    var cycles = 0L
    for (i <- 0 until count) {
      val seed = seedBase + i
      val program = build(new Random(seed))
      val result =
        try cosimProgram(program, readRange = RandomProgram.dataRange, maxCycles = 200000)
        catch {
          case e: Throwable =>
            throw new AssertionError(s"$name failed with seed $seed: ${e.getMessage}", e)
        }
      instructions += result.retired
      cycles += result.cycles
    }
    info(f"$name: $count programs, $instructions instructions in $cycles cycles " +
      f"(${instructions.toDouble / cycles}%.2f per cycle)")
  }

  test("random programs over the whole register file") {
    sweep("wide register pool", 40, 1000) { rng =>
      RandomProgram.generate(rng, groups = 120)
    }
  }

  test("random programs over a narrow register pool") {
    // Restricting writes to four registers makes nearly every instruction
    // depend on one of the three before it, so forwarding and the interlock
    // are exercised on almost every cycle.
    sweep("narrow register pool", 40, 2000) { rng =>
      RandomProgram.generate(rng, groups = 120, writePool = Seq(1, 2, 3, 4))
    }
  }

  test("memory-heavy random programs") {
    sweep("memory heavy", 30, 3000) { rng =>
      RandomProgram.generate(rng, groups = 140, writePool = Seq(1, 2, 3, 4, 5, 6), memoryBias = 3)
    }
  }

  test("short random programs, many of them") {
    sweep("short programs", 60, 4000) { rng =>
      RandomProgram.generate(rng, groups = 25, writePool = Seq(1, 2, 3))
    }
  }
}
