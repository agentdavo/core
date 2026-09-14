package axiom.plugins

import axiom._
import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Drives the instruction bus and hands the word to decode.
  *
  * The memory no longer promises a word on any particular cycle, so the two
  * halves of a fetch are separated: the fetch stage issues the command, and
  * decode waits for the answer.
  *
  * **The transaction leaves fetch on the cycle its command is accepted, and
  * only then.** That is the invariant everything else rests on. A redirect
  * changes the program counter whenever it likes, so a transaction that sat in
  * fetch holding an accepted command would find its own address changed
  * underneath it: it would carry the new program counter and the new fetch
  * generation, pass decode's staleness check, and execute the instruction
  * fetched from the old address. Firing on acceptance means the address on the
  * bus and the program counter in the transaction are the same thing.
  *
  * Decode therefore holds the wait, and the response is captured into a
  * one-deep buffer because `rvalid` is a pulse and decode may stall for other
  * reasons after it arrives.
  *
  * One command is outstanding at a time. Fetch will not issue while a response
  * is still owed, nor while the buffer holds a word decode has not taken, so
  * the buffer cannot be overwritten and needs no depth.
  */
class FetchPlugin extends AxiomPlugin {

  private var bus: IBus = null

  val setupLogic = during setup new Area {
    bus = host[MemoryService].newInstructionPort()
  }

  val logic = during build new Area {
    val fetchNode = ctrl(Stages.FETCH)
    val decodeNode = ctrl(Stages.DECODE)

    /** The one-deep response buffer, decode's wait on it, and the answer
      * nobody is left to take.
      *
      * A transaction thrown for a stale generation does not fire, but it does
      * leave, so `isMoving` rather than `isFiring` decides whether a word has
      * been consumed. Leaving a taken word in the buffer would hand the
      * instruction from a killed address to whatever arrived next, which is a
      * branch executing the instruction it jumped over.
      *
      * A transaction can also be thrown *before* its word arrives, and then it
      * leaves owing one. The answer turns up with nobody at decode to take it,
      * fills the buffer, and stops fetch from ever asking again: the machine
      * simply stops. That cannot happen when the memory answers the next
      * cycle, because the transaction is still there when it does. Behind a
      * cache the wait is tens of cycles and a redirect lands in the middle of
      * it, so one orphan is remembered and the response it belongs to is
      * dropped when it arrives.
      */
    val buffer = new Area {
      val data = Reg(Bits(Isa.INSTR_BITS bits)) init 0
      val full = Reg(Bool()) init False
      val orphan = Reg(Bool()) init False

      // Arriving and stored are both acceptable, which is what keeps a
      // one-cycle memory at one cycle; an answer owed to a transaction that
      // has already gone is neither.
      val usable = bus.rvalid && !orphan
      val present = full || usable
      val word = Mux(full, data, bus.data)

      val leaving = decodeNode.up.isMoving
      val taken = leaving && present
      val abandoned = leaving && !present

      when(usable) { data := bus.data }
      full := (full || usable) && !taken
      orphan := (orphan || abandoned) && !bus.rvalid
    }

    decodeNode.up(Global.INSTRUCTION) := buffer.word
    decodeNode.haltWhen(!buffer.present)

    /** The command, and the outstanding-response bookkeeping. */
    val command = new Area {
      val outstanding = Reg(Bool()) init False
      val stillOwed = outstanding && !bus.rvalid
      val bufferBusy = buffer.full && !buffer.taken

      bus.address := fetchNode.up(Global.PC)

      // Offered only when the transaction can move on this cycle, so that
      // acceptance and firing are the same event. Offering it while decode is
      // busy would let a command be accepted for an address the transaction
      // then stops carrying: a redirect would move the program counter under
      // it, and it would arrive at decode holding the new counter, the new
      // generation, and the instruction from the old address.
      bus.enable := fetchNode.up.isValid && fetchNode.down.isReady &&
        !stillOwed && !bufferBusy

      val accepted = bus.enable && bus.ready
      outstanding := (outstanding || accepted) && !bus.rvalid

      // The only reason fetch ever holds a transaction. Every other stage's
      // backpressure reaches it through the buffer being busy, which stops the
      // command being offered in the first place.
      fetchNode.haltWhen(!accepted)
    }
  }
}
