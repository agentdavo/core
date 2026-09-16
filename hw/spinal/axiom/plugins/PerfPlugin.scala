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

    val present = AxiomParam.WITH_PERF_COUNTERS.get

    val counting = present generate new Area {
      val counters = Vec(Seq.tabulate(PerfEvent.COUNT) { index =>
        events.get(index) match {
          case Some(flag) =>
            // Sampled, not counted directly.
            //
            // The event comes from whatever the plugin that owns it happens to
            // be computing, and the counter it feeds is somewhere else
            // entirely; measured on an ECP5, that wire plus the thirty-two bit
            // carry chain behind it was the critical path of the whole core.
            // A register in between costs a cycle of reporting delay on a
            // number nothing in the design reads, and the count is the same.
            val sampled = RegNext(running && flag) init False
            val count = Reg(UInt(32 bits)) init 0
            when(sampled) { count := count + 1 }
            count
          case None => U(0, 32 bits)
        }
      })

      // Registered for the same reason: the selection multiplexer is not in
      // series with whatever reads the value.
      io.dbgPerfCount := RegNext(counters.read(io.dbgPerfSelect)) init 0
    }

    // A build without the counters answers zero, which is what a counter that
    // was never built has counted.
    val silent = (!present) generate new Area {
      io.dbgPerfCount := U(0, 32 bits)
    }
  }
}
