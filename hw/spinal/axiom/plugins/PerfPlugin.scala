package axiom.plugins

import axiom._
import spinal.core._
import scala.collection.mutable

/** Cycle accounting for the whole core.
  *
  * Every claim about performance in this project is supposed to be measured
  * rather than argued, and until now the only measurement available was the
  * total cycle count. That says a program got slower without saying where the
  * cycles went, which is exactly the information needed to decide what to fix
  * next. This plugin collects a counter per stall reason from the plugin that
  * causes it, so the next optimisation is chosen by the numbers.
  *
  * The counters leave through one indexed port rather than one output each:
  * the debug interface has a fixed shape shared by both top levels, and a
  * counter per event would put the event list into it. Selecting by index
  * keeps the interface the same size however many events there are, and the
  * index is fixed by [[PerfEvent]] so the test bench and the design agree
  * without either reaching into the other.
  *
  * Counting stops when the machine does, so that the numbers describe the
  * program rather than however long the test bench took to notice.
  */
class PerfPlugin extends AxiomPlugin with PerfService {

  private val events = mutable.Map[Int, Bool]()

  override def newCounter(name: String): Bool = {
    val at = PerfEvent.index(name)
    require(!events.contains(at), s"the '$name' counter already has an owner")
    val flag = Bool()
    events(at) = flag
    flag
  }

  val logic = during build new Area {
    val io = host[InterfaceService].io

    // The same gate the cycle counter uses. An event raised while the machine
    // is halted is an artefact of the stages draining, not part of the run.
    val running = !io.halted

    val counters = Vec(Seq.tabulate(PerfEvent.COUNT) { index =>
      events.get(index) match {
        case Some(flag) =>
          val count = Reg(UInt(32 bits)) init 0
          when(running && flag) { count := count + 1 }
          count
        case None => U(0, 32 bits)
      }
    })

    // Registered, so that the selection multiplexer is not in series with
    // whatever the test bench does with the value. It costs a cycle of latency
    // on a signal nothing in the design reads.
    io.dbgPerfCount := RegNext(counters.read(io.dbgPerfSelect)) init 0
  }
}
