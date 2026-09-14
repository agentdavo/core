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
  * It answers `latency` cycles after taking a command and refuses commands
  * while it is working, which is what an off-chip part or a shared
  * interconnect does and what makes a cache worth having. Each port gets its
  * own read on the array rather than sharing one through an arbiter; that is
  * what an on-chip dual-port SRAM gives for free, and an arbiter here would
  * measure the arbiter rather than the cache.
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
    val counter = Reg(UInt(log2Up(latency + 1) bits)) init 0
    val busy = counter =/= 0
    val reading = Reg(Bool()) init False

    // The debug access takes the array over while it is enabled, which is only
    // meant to happen with the core in reset or halted.
    val accepted = bus.enable && !busy && !debugActive
    bus.ready := !busy && !debugActive

    when(accepted) {
      counter := latency
      reading := !bus.write
    }
    when(busy) { counter := counter - 1 }

    bus.rvalid := (counter === 1) && reading

    val writing = accepted && bus.write
    val reads = accepted && !bus.write
    val index_ = wordIndex(bus.address)

    // Channel zero's address and read enable are shared with the debug access,
    // which takes the array over while it is enabled.
    val borrowed = withDebug && index == 0
    val readAddress = if (borrowed) Mux(debugActive, io.debug.address, index_) else index_
    val readEnable = if (borrowed) debugActive || reads else reads

    val readData = ram.readSync(address = readAddress, enable = readEnable)
    bus.rdata := readData
    if (borrowed) io.debug.rdata := readData
  }

  /** The single write port, driven by whichever channel is writing, or by the
    * debug access when it has the array.
    */
  val writer = new Area {
    val fromChannel = channel.map(_.writing).reduce(_ || _)
    val address = UInt(wordAddressBits bits)
    val value = Bits(dataWidth bits)
    val mask = Bits(dataWidth / 8 bits)

    address := 0
    value := 0
    mask := B(dataWidth / 8 bits, default -> True)
    for ((c, bus) <- channel.zip(io.port)) {
      when(c.writing) {
        address := wordIndex(bus.address)
        value := bus.wdata
        mask := bus.mask
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
