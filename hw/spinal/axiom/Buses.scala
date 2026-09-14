package axiom

import spinal.core._
import spinal.lib._

/** Instruction fetch bus.
  *
  * A tightly coupled memory with a fixed one-cycle latency: the word at
  * `address` appears on `data` on the following cycle, and the memory must hold
  * `data` unchanged while `enable` is low. The pipeline relies on that hold to
  * keep a fetched instruction stable across a stall, which is exactly how a
  * block RAM with a read enable behaves.
  *
  * The address is a byte address; instructions are 32 bits and the memory is 64
  * bits wide, so the memory selects the half.
  */
case class IBus(addressWidth: Int) extends Bundle with IMasterSlave {
  val enable  = Bool()
  val address = UInt(addressWidth bits)
  val data    = Bits(Isa.INSTR_BITS bits)

  override def asMaster(): Unit = {
    out(enable, address)
    in(data)
  }
}

/** Data bus. Same fixed-latency contract as [[IBus]], one mask bit per byte. */
case class DBus(addressWidth: Int, dataWidth: Int) extends Bundle with IMasterSlave {
  val enable  = Bool()
  val write   = Bool()
  val address = UInt(addressWidth bits)
  val mask    = Bits(dataWidth / 8 bits)
  val wdata   = Bits(dataWidth bits)
  val rdata   = Bits(dataWidth bits)

  override def asMaster(): Unit = {
    out(enable, write, address, mask, wdata)
    in(rdata)
  }
}
