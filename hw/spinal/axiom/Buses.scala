package axiom

import spinal.core._
import spinal.lib._

/** The memory contract.
  *
  * A command is offered while `enable` is high and is **accepted** on a cycle
  * where `enable` and `ready` are both high. An accepted read produces exactly
  * one response, on a later cycle, marked by `rvalid`. Responses come back in
  * the order their commands were accepted. A write needs acceptance and
  * nothing else.
  *
  * Two things are deliberately not in this contract, and their absence is the
  * whole point of it:
  *
  *  - **No fixed latency.** The previous version promised the word one cycle
  *    later, which a tightly coupled memory can do and a cache cannot. Every
  *    plugin that waits now waits on a signal rather than on a count.
  *  - **No hold.** The memory is not asked to keep a response on its output
  *    until somebody reads it. That was free for a block RAM with a read
  *    enable and impossible for anything with a queue behind it, so the core
  *    captures each response into a one-deep buffer of its own instead.
  *
  * The core keeps a bounded number of commands outstanding per port and
  * buffers that many responses: one on the data port, where a stage issues
  * its command only when the transaction holding it can move on, and a few
  * on the instruction port, where the fetch unit counts what it has out so
  * that a memory answering in two cycles is still asked every cycle.
  */
case class IBus(addressWidth: Int) extends Bundle with IMasterSlave {
  val enable  = Bool()
  val address = UInt(addressWidth bits)
  val ready   = Bool()
  val rvalid  = Bool()
  val data    = Bits(Isa.INSTR_BITS bits)

  override def asMaster(): Unit = {
    out(enable, address)
    in(ready, rvalid, data)
  }
}

/** The data bus, same contract, with one mask bit per byte.
  *
  * Only a read produces a response. A store needs to be accepted and is then
  * finished as far as the pipeline is concerned, which keeps a store to a
  * tightly coupled memory at one cycle.
  */
case class DBus(addressWidth: Int, dataWidth: Int) extends Bundle with IMasterSlave {
  val enable  = Bool()
  val write   = Bool()
  val address = UInt(addressWidth bits)
  val mask    = Bits(dataWidth / 8 bits)
  val wdata   = Bits(dataWidth bits)
  val ready   = Bool()
  val rvalid  = Bool()
  val rdata   = Bits(dataWidth bits)

  override def asMaster(): Unit = {
    out(enable, write, address, mask, wdata)
    in(ready, rvalid, rdata)
  }
}
