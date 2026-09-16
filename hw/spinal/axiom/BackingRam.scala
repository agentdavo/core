package axiom

import spinal.core._
import spinal.lib._

/** The memory a cache sits in front of.
  *
  * A component, like [[CacheUnit]], so the pair can be simulated together
  * without a core around them. The interesting faults live in the handshake
  * between the two, and a handshake is much easier to watch from outside than
  * to infer from a wrong register three hundred cycles later.
  *
  * It answers `latency` cycles after taking a command. Reads are pipelined:
  * a command may be accepted every cycle and the answers come back in order,
  * `latency` cycles behind. That is what an off-chip part with a fixed access
  * time does, and it is the difference between a line refill costing the
  * latency once and costing it per word. The first version of this memory
  * refused a command while it was working, and the cache measurements made
  * with it said longer lines were worse, which was a fact about this model
  * rather than about caches.
  *
  * A write takes the memory to itself. It is accepted like anything else, held
  * in a register, and applied once the reads already in flight have been
  * answered; nothing further is accepted until it has finished. A write that
  * overtook a read in flight would answer that read with data from after it,
  * and the point of a memory model is that it is obviously right.
  *
  * Holding the write rather than refusing it is not a detail. Whether a
  * command is accepted must not depend on what the command is: the cache asks
  * to write only while the memory says it may, so a ready that looked at the
  * write bit closed a combinational loop through both.
  *
  * Each port gets its own read on the array rather than sharing one through an
  * arbiter; that is what an on-chip dual-port SRAM gives for free, and an
  * arbiter here would measure the arbiter rather than the cache.
  *
  * There is one write port, and whichever channel is writing drives it. Two
  * write statements on one array are two write ports, which a block RAM does
  * not have, and picking a channel by position instead would make correctness
  * depend on the order two plugins happen to ask for their ports in. Only one
  * channel can be writing at a time anyway: each takes one command and refuses
  * the next until it is finished.
  */
case class BackingRam(
    words: Int,
    latency: Int,
    ports: Int = 2,
    addressWidth: Int = Isa.XLEN,
    dataWidth: Int = Isa.XLEN,
    withDebug: Boolean = true
) extends Component {

  require(latency >= 1, "a memory answers no sooner than the next cycle")
  require(ports >= 1)

  val wordAddressBits = log2Up(words)

  val io = new Bundle {
    val port = Vec(slave(DBus(addressWidth, dataWidth)), ports)
    val debug = withDebug generate new Bundle {
      val enable = in Bool ()
      val write = in Bool ()
      val address = in UInt (wordAddressBits bits)
      val wdata = in Bits (dataWidth bits)
      val rdata = out Bits (dataWidth bits)
    }
  }

  val ram = Mem(Bits(dataWidth bits), words)
  ram.init(Seq.fill(words)(B(0, dataWidth bits)))

  def wordIndex(byteAddress: UInt): UInt =
    byteAddress(addressWidth - 1 downto 3).resize(wordAddressBits)

  val debugActive = if (withDebug) io.debug.enable else False

  val channel = for ((bus, index) <- io.port.zipWithIndex) yield new Area {

    /** Reads in flight, youngest at the top. Entry zero answers this cycle.
      *
      * The address travels down the pipeline rather than the data, because the
      * data is sixty-four bits and the address is twelve: delaying the answer
      * would cost seven registers of data per cycle of latency, and delaying
      * the question costs seven registers of address. The array read is issued
      * one cycle before the answer is due, which is what a synchronous memory
      * needs, and the rest of the wait is just the address sitting still.
      */
    val inFlight = Vec.fill(latency)(Reg(Bool()) init False)
    val pending = Vec.fill(latency)(Reg(UInt(wordAddressBits bits)) init 0)
    val anyInFlight = inFlight.reduce(_ || _)

    /** The write waiting for the reads in front of it, and then for the
      * latency a write costs.
      */
    val held = new Area {
      val valid = Reg(Bool()) init False
      val address = Reg(UInt(wordAddressBits bits)) init 0
      val value = Reg(Bits(dataWidth bits)) init 0
      val mask = Reg(Bits(dataWidth / 8 bits)) init 0
    }
    val writeLeft = Reg(UInt(log2Up(latency + 1) bits)) init 0
    val writeBusy = held.valid || writeLeft =/= 0
    when(writeLeft =/= 0) { writeLeft := writeLeft - 1 }

    // The debug access takes the array over while it is enabled, which is only
    // meant to happen with the core in reset or halted.
    val blocked = writeBusy || debugActive
    val accepted = bus.enable && !blocked
    bus.ready := !blocked

    val writing = accepted && bus.write
    val reads = accepted && !bus.write
    val index_ = wordIndex(bus.address)

    when(writing) {
      held.valid := True
      held.address := index_
      held.value := bus.wdata
      held.mask := bus.mask
    }

    /** Applied once nothing older is still on its way back. */
    val applying = held.valid && !anyInFlight
    when(applying) {
      held.valid := False
      writeLeft := latency
    }

    for (slot <- 0 until latency - 1) {
      inFlight(slot) := inFlight(slot + 1)
      pending(slot) := pending(slot + 1)
    }
    inFlight(latency - 1) := reads
    when(reads) { pending(latency - 1) := index_ }

    bus.rvalid := inFlight(0)

    // With a latency of one there is no pipeline to walk: the answer is due
    // the cycle after the command, so the array read is issued with it.
    val issuing = if (latency > 1) inFlight(1) else reads
    val issueAddress = if (latency > 1) pending(1) else index_

    // Channel zero's address and read enable are shared with the debug access,
    // which takes the array over while it is enabled.
    val borrowed = withDebug && index == 0
    val readAddress =
      if (borrowed) Mux(debugActive, io.debug.address, issueAddress) else issueAddress
    val readEnable = if (borrowed) debugActive || issuing else issuing

    val readData = ram.readSync(address = readAddress, enable = readEnable)
    bus.rdata := readData
    if (borrowed) io.debug.rdata := readData
  }

  /** The single write port, driven by whichever channel is writing, or by the
    * debug access when it has the array.
    */
  val writer = new Area {
    val fromChannel = channel.map(_.applying).reduce(_ || _)
    val address = UInt(wordAddressBits bits)
    val value = Bits(dataWidth bits)
    val mask = Bits(dataWidth / 8 bits)

    address := 0
    value := 0
    mask := B(dataWidth / 8 bits, default -> True)
    for (c <- channel) {
      when(c.applying) {
        address := c.held.address
        value := c.held.value
        mask := c.held.mask
      }
    }

    val enable = Bool()
    if (withDebug) {
      enable := Mux(debugActive, io.debug.write, fromChannel)
      when(debugActive) {
        address := io.debug.address
        value := io.debug.wdata
        mask := B(dataWidth / 8 bits, default -> True)
      }
    } else {
      enable := fromChannel
    }

    ram.write(address = address, data = value, enable = enable, mask = mask)
  }

  /** The debug read borrows channel zero's port rather than having one of its
    * own.
    *
    * Its own port reads better and costs three times the memory. An ECP5 block
    * RAM has two ports; a third read makes yosys replicate the whole array, so
    * a thirty-two kilobyte memory with two channels and a debug read was
    * forty-eight block RAMs where the arithmetic says sixteen. The debug
    * access only happens with the core in reset or halted, which is exactly
    * when channel zero is idle, so there is nothing to disturb.
    */
}
