package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib.misc.pipeline._
import scala.collection.mutable.ArrayBuffer

/** The eight predicate registers.
  *
  * Predicates are renamed and forwarded exactly like general registers, which
  * is the whole argument for having them instead of a condition code register.
  * Note that this is more state to track, not less: eight renameable bits
  * against one four-bit flags word. What it buys is independent condition
  * chains, so two unrelated comparisons never serialise against each other.
  *
  * Only compare instructions write here and only select and branch read, so
  * the file is tiny and its forwarding network is three comparators.
  */
class PredicateFilePlugin extends AxiomPlugin with PredicateService {

  private case class Writer(sel: Payload[Bool], value: Payload[Bool])

  private val writers = ArrayBuffer[Writer]()

  override def addWriter(sel: Payload[Bool], value: Payload[Bool]): Unit =
    writers += Writer(sel, value)

  /** A Handle rather than a plain field, so a plugin that calls `read` before
    * this one has built simply suspends until the reader exists instead of
    * depending on plugin construction order.
    */
  private val reader = spinal.core.fiber.Handle[UInt => Bool]()

  override def read(address: UInt): Bool = reader.get.apply(address)

  val logic = during build new Area {
    val preds = Vec.fill(AxiomParam.PRED_COUNT.get)(Reg(Bool()) init False)

    val me = ctrl(Stages.MEMORY)
    val wb = ctrl(Stages.WRITEBACK)

    def valueAt(node: NodeApi): Bool = {
      val value = False
      for (writer <- writers) when(node(writer.sel)) { value := node(writer.value) }
      value
    }

    val wbValue = valueAt(wb.down)
    val memValue = valueAt(me.down)

    val wbWrites = wb.down.isFiring && wb.down(Global.WRITES_PD)
    when(wbWrites) { preds(wb.down(Global.PD_ADDR)) := wbValue }

    // Read at execute, forwarding from memory and writeback only.
    //
    // Deliberately not from execute. A predicate read happens at execute, and
    // the only instruction at execute is the reader itself, so an execute-stage
    // forward could only ever feed an instruction its own result. It is dead
    // logic, but it is not free: it chains the compare unit's 64-bit
    // comparison straight into the select unit's read, and that false path
    // measured at 14 ns of a 35 ns critical path.
    //
    // Oldest first, newest wins.
    reader.load((address: UInt) => {
      val value = Bool()
      value := preds(address)
      when(wbWrites && wb.down(Global.PD_ADDR) === address) { value := wbValue }
      when(me.isValid && me.down(Global.WRITES_PD) && me.down(Global.PD_ADDR) === address) { value := memValue }
      value
    })

    // All eight predicates at once, which is cheaper than an address port and
    // more useful in a trace.
    host[InterfaceService].io.dbgPredicates := preds.asBits
  }
}
