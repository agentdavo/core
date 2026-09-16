package axiom

import spinal.core.sim.SimCompiled

/** Where the cycles go.
  *
  * Not a correctness test. Every claim about performance in this project is
  * supposed to be measured rather than argued, and a total cycle count says a
  * program is slow without saying whose fault it is. This runs the workloads
  * with the counters read out, prints the accounting, and checks the few
  * things that have to hold for the numbers to mean anything: that the
  * workloads compute their known answers, that a counter only counts what it
  * claims to, and that the stall cycles do not add up to more than the run.
  *
  * The printed table is the point. It is what decides which optimisation is
  * worth doing next.
  */
class AxiomPerfSpec extends AxiomSpec {

  private def measure(workload: Workload, design: SimCompiled[AxiomSoc]): RunResult = {
    val result = AxiomSim.run(workload.program, data = workload.data, design = design)
    assert(result.halted, s"${workload.name} did not halt")
    assert(!result.trapped, s"${workload.name} trapped with ${result.causeName}")
    assert(result.reg(AxiomWorkloads.ResultReg) == workload.expected,
      s"${workload.name} computed ${result.reg(AxiomWorkloads.ResultReg)}, " +
        s"expected ${workload.expected}")
    result
  }

  private def report(title: String, rows: Seq[(Workload, RunResult)]): Unit = {
    val counted = PerfEvent.NAMES.filter(name => rows.exists(_._2.perf(name) != 0))
    val header = f"${"workload"}%-15s${"cycles"}%9s${"retired"}%9s${"IPC"}%7s" +
      counted.map(name => f"$name%13s").mkString
    println()
    println(title)
    println("-" * header.length)
    println(header)
    for ((workload, result) <- rows) {
      val ipc = result.retired.toDouble / result.cycles
      println(f"${workload.name}%-15s${result.cycles}%9d${result.retired}%9d$ipc%7.2f" +
        counted.map(name => f"${result.perf(name)}%13d").mkString)
    }
    println()
  }

  test("cycle accounting on a tightly coupled memory") {
    val rows = AxiomWorkloads.all.map(w => w -> measure(w, AxiomSim.soc))
    report("Axiom-64, tightly coupled memory", rows)

    for ((workload, result) <- rows) {
      val stalls = Seq("fetch", "interlock", "load", "issue", "divide").map(result.event).sum
      assert(stalls <= result.cycles,
        s"${workload.name} counted $stalls stall cycles in ${result.cycles} cycles")
      assert(result.retired <= result.cycles)
      // No divider is used and no cache is built, so those counters say so.
      assert(result.event("divide") == 0)
      assert(result.event("icache-miss") == 0)
      assert(result.event("dcache-miss") == 0)
    }

    // A straight line of independent adds has nothing to wait for but its own
    // loop branch, which is the only thing that should show up.
    val straight = rows.find(_._1.name == "straight-line").get._2
    assert(straight.event("interlock") == 0, "independent adds should not interlock")
    assert(straight.event("load") == 0)
    assert(straight.event("issue") == 0)
    assert(straight.event("redirect") > 0, "the loop branch is taken every iteration")

    // A load feeding the instruction after it is the hazard the interlock
    // exists for, so the workload built around it had better show one.
    val squares = rows.find(_._1.name == "sum-of-squares").get._2
    assert(squares.event("interlock") > 0, "a load-use pair should interlock")
  }

  test("cycle accounting behind caches") {
    val rows = AxiomWorkloads.all.map(w => w -> measure(w, AxiomSim.socCached))
    report("Axiom-64, 4 kB caches over a memory of latency 8", rows)

    for ((_, result) <- rows) {
      // Every program starts with a cold cache, so the first instruction of
      // every one of them misses.
      assert(result.event("icache-miss") > 0)
      assert(result.event("fetch") > 0, "a refill is cycles decode spent waiting")
    }

    // The data caches only see traffic from the workloads that touch memory.
    val straight = rows.find(_._1.name == "straight-line").get._2
    assert(straight.event("dcache-miss") == 0, "straight-line touches no data")
    val copy = rows.find(_._1.name == "memcpy").get._2
    assert(copy.event("dcache-miss") > 0)
  }

  test("counting stops when the machine does") {
    // The counters are read several hundred cycles after the core halts, while
    // the test bench walks the register file and the memory. A counter that
    // kept counting through that would report the test bench, not the program.
    val workload = AxiomWorkloads.straightLine
    val first = measure(workload, AxiomSim.soc)
    val second = measure(workload, AxiomSim.soc)
    assert(first.perf == second.perf, "the counters depend on something other than the program")
    assert(first.event("redirect") < first.cycles)
  }
}
