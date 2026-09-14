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

    val ex = ctrl(Stages.EXECUTE)
    val me = ctrl(Stages.MEMORY)
    val wb = ctrl(Stages.WRITEBACK)

    def valueAt(node: NodeApi): Bool = {
      val value = False
      for (writer <- writers) when(node(writer.sel)) { value := node(writer.value) }
      value
    }

    val wbValue = valueAt(wb.down)
    val memValue = valueAt(me.down)
    val exValue = valueAt(ex.down)

    val wbWrites = wb.down.isFiring && wb.down(Global.WRITES_PD)
    when(wbWrites) { preds(wb.down(Global.PD_ADDR)) := wbValue }

    // Read at execute, where select and branch both live, so all three
    // in-flight producers have to be forwarded. Oldest first, newest wins.
    reader.load((address: UInt) => {
      val value = Bool()
      value := preds(address)
      when(wbWrites && wb.down(Global.PD_ADDR) === address) { value := wbValue }
      when(me.isValid && me.down(Global.WRITES_PD) && me.down(Global.PD_ADDR) === address) { value := memValue }
      when(ex.isValid && ex.down(Global.WRITES_PD) && ex.down(Global.PD_ADDR) === address) { value := exValue }
      value
    })

    // All eight predicates at once, which is cheaper than an address port and
    // more useful in a trace.
    host[InterfaceService].io.dbgPredicates := preds.asBits
  }
}
