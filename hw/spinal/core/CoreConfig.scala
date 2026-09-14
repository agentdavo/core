package core

import spinal.core._
import spinal.lib._

/** Configuration of a CORE-32 implementation.
  *
  * @param resetVector   value of `pc` out of reset
  * @param addressWidth  width of the instruction and data bus addresses
  * @param hasMultiplier when false, MUL/MULH/MULHU decode as illegal and no
  *                      multiplier is instantiated
  */
case class CoreConfig(
    resetVector: BigInt = 0,
    addressWidth: Int = 32,
    hasMultiplier: Boolean = true
) {
  require(addressWidth >= 8 && addressWidth <= 32, "addressWidth must be between 8 and 32")
  def xlen: Int = Isa.XLEN
}

/** Instruction fetch bus.
  *
  * A tightly coupled, fixed one-cycle memory interface: the word at `address`
  * appears on `data` on the following cycle. There is no backpressure, and the
  * memory must hold `data` unchanged while `enable` is low. The pipeline relies
  * on that hold to keep the fetched instruction stable across a stall, which is
  * exactly the behaviour of a block RAM with a read-enable.
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

/** Data bus.
  *
  * Same contract as [[IBus]]: a read issued in one cycle returns on the next,
  * writes complete in the cycle they are issued, and `mask` carries one bit per
  * byte lane.
  */
case class DBus(addressWidth: Int) extends Bundle with IMasterSlave {
  val enable  = Bool()
  val write   = Bool()
  val address = UInt(addressWidth bits)
  val mask    = Bits(4 bits)
  val wdata   = Bits(Isa.XLEN bits)
  val rdata   = Bits(Isa.XLEN bits)

  override def asMaster(): Unit = {
    out(enable, write, address, mask, wdata)
    in(rdata)
  }
}
