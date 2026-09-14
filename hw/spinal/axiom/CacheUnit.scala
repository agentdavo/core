package axiom

import spinal.core._
import spinal.lib._

/** One direct-mapped cache, as a component rather than as part of a plugin.
  *
  * A component so it can be driven on its own in simulation. A cache that can
  * only be tested through a core is a cache whose faults are diagnosed through
  * a core, which is several layers too many: the first version of this one had
  * a bug that took a whole afternoon of program-level bisection and about a
  * minute of poking the ports directly.
  *
  * Direct mapped, not set associative, and that is a decision about the part
  * rather than about hit rates. A set associative cache needs a tag comparison
  * per way, a multiplexer on the data path behind it and a replacement policy
  * to keep; on an ECP5 where the core measures sixty per cent routing, all
  * three land on the wrong side of the trade.
  *
  * Write-through with no allocation on a store miss. There are no dirty bits,
  * no writeback machine and no eviction: a line is only ever filled or
  * replaced. What it costs is that every store goes to the memory behind, so a
  * store is as slow as that memory. A store buffer would hide that and is
  * deliberately not here yet, because the first thing worth knowing is how
  * much it actually costs.
  *
  * A hit is one cycle, the same as a tightly coupled memory, and the lookup is
  * pipelined so a hit every cycle is a command every cycle.
  *
  * @param bytes      how much this cache holds
  * @param lineBytes  bytes filled per miss
  * @param memoryBytes how much memory exists behind it, which is all the tag
  *        has to distinguish; a sixty-four bit tag on a small memory would be
  *        most of the cache
  * @param writes     whether stores pass through it
  */
case class CacheUnit(
    bytes: Int,
    lineBytes: Int,
    memoryBytes: Int,
    writes: Boolean,
    addressWidth: Int = Isa.XLEN,
    dataWidth: Int = Isa.XLEN
) extends Component {

  require(isPow2(bytes) && isPow2(lineBytes), "a cache and its line are powers of two")
  require(bytes >= lineBytes * 2, "a cache is at least two lines")
  require(lineBytes >= 16, "a line is at least two doublewords")

  val lineWords = lineBytes / 8
  val lines = bytes / lineBytes
  val offsetBits = log2Up(lineBytes)
  val wordBits = log2Up(lineWords)
  val indexBits = log2Up(lines)
  val physicalBits = log2Up(memoryBytes)
  val tagBits = physicalBits - indexBits - offsetBits
  require(tagBits >= 1, "the cache is as large as the memory behind it")

  val io = new Bundle {
    val core = slave(DBus(addressWidth, dataWidth))
    val memory = master(DBus(addressWidth, dataWidth))
  }

  def indexOf(a: UInt): UInt = a(offsetBits + indexBits - 1 downto offsetBits)
  def tagOf(a: UInt): Bits = a(physicalBits - 1 downto offsetBits + indexBits).asBits
  def wordOf(a: UInt): UInt = a(offsetBits - 1 downto 3)

  val array = Mem(Bits(dataWidth bits), lines * lineWords)
  val tags = Mem(Bits(tagBits bits), lines)

  /** Valid bits in flip-flops rather than in the tag array, so the cache is
    * empty at reset without a clearing sequence to get wrong.
    */
  val lineValid = Vec.fill(lines)(Reg(Bool()) init False)

  // ---- the refill engine --------------------------------------------------
  val refill = new Area {
    val active = Reg(Bool()) init False
    val address = Reg(UInt(addressWidth bits)) init 0
    val word = Reg(UInt(wordBits bits)) init 0
    val issued = Reg(Bool()) init False
    val captured = Reg(Bits(dataWidth bits)) init 0
    val done = Reg(Bool()) init False

    val wanted = wordOf(address)
    val last = word === (lineWords - 1)
  }

  // ---- the lookup, one cycle behind the command ---------------------------
  val lookup = new Area {
    val valid = Reg(Bool()) init False
    val address = Reg(UInt(addressWidth bits)) init 0
    val write = Reg(Bool()) init False
    val wdata = Reg(Bits(dataWidth bits)) init 0
    val mask = Reg(Bits(dataWidth / 8 bits)) init 0

    val index = indexOf(address)
    val hit = lineValid(index) && (tags.readAsync(index) === tagOf(address))
    val miss = valid && !write && !hit
  }

  // ---- accepting a command ------------------------------------------------
  //
  // Refusing in the same cycle a miss is discovered is what keeps the lookup
  // pipelined without a replay buffer: the command that would have been taken
  // simply is not, and the core offers it again.
  //
  // A store also refuses for one cycle, because the cycle it updates the array
  // is a cycle a load must not read it: the array answers a read with what was
  // there before the write, which is the wrong answer for a load of the
  // address just written.
  val storeBusy = if (writes) lookup.valid && lookup.write else False
  val backingBusy = if (writes) io.core.enable && io.core.write && !io.memory.ready else False
  val ready = !refill.active && !refill.done && !lookup.miss && !storeBusy && !backingBusy
  val accepted = io.core.enable && ready

  val readData = array.readSync(
    address = indexOf(io.core.address) @@ wordOf(io.core.address),
    enable = accepted
  )

  lookup.valid := accepted
  when(accepted) {
    lookup.address := io.core.address
    lookup.write := io.core.write
    lookup.wdata := io.core.wdata
    lookup.mask := io.core.mask
  }

  /** One write port on the array, driven by whichever wants it.
    *
    * A refill writes a whole word and a store writes the bytes its mask names,
    * and they are exclusive: a refill only starts from a load miss and nothing
    * is accepted while one runs, so no store can be in its lookup cycle at the
    * same time. Two `write` calls would be two write ports, which a block RAM
    * does not have.
    */
  val arrayWrite = new Area {
    val enable = False
    val address = UInt(indexBits + wordBits bits)
    val value = Bits(dataWidth bits)
    val mask = Bits(dataWidth / 8 bits)
    address := 0
    value := 0
    mask := B(dataWidth / 8 bits, default -> True)
  }

  // ---- the miss -----------------------------------------------------------
  when(lookup.miss && !refill.active) {
    refill.active := True
    refill.address := lookup.address
    refill.word := 0
    refill.issued := False
    lineValid(lookup.index) := False
  }

  when(refill.active && io.memory.rvalid) {
    arrayWrite.enable := True
    arrayWrite.address := indexOf(refill.address) @@ refill.word
    arrayWrite.value := io.memory.rdata
    when(refill.word === refill.wanted) { refill.captured := io.memory.rdata }
    refill.issued := False
    refill.word := refill.word + 1
    when(refill.last) {
      refill.active := False
      tags.write(address = indexOf(refill.address), data = tagOf(refill.address))
      lineValid(indexOf(refill.address)) := True
    }
  }

  // A one-cycle pulse after the last word lands, which is when the line is
  // whole and the word that was asked for can be handed back.
  refill.done := refill.active && io.memory.rvalid && refill.last

  // A store that finds its line present updates it, so the next load of that
  // address does not go back to memory for something just written. A store
  // that misses does not allocate.
  if (writes) {
    when(lookup.valid && lookup.write && lookup.hit) {
      arrayWrite.enable := True
      arrayWrite.address := lookup.index @@ wordOf(lookup.address)
      arrayWrite.value := lookup.wdata
      arrayWrite.mask := lookup.mask
    }
  }

  array.write(
    address = arrayWrite.address,
    data = arrayWrite.value,
    enable = arrayWrite.enable,
    mask = arrayWrite.mask
  )

  // ---- the port on the memory behind --------------------------------------
  val refillAddress = refill.address(addressWidth - 1 downto offsetBits) @@
    refill.word @@ U(0, 3 bits)
  val storeNow = if (writes) io.core.enable && io.core.write && ready else False

  io.memory.enable := (refill.active && !refill.issued) || storeNow
  io.memory.write := storeNow
  io.memory.address := Mux(refill.active, refillAddress.resized, io.core.address)
  io.memory.wdata := io.core.wdata
  io.memory.mask := io.core.mask
  when(refill.active && !refill.issued && io.memory.ready) { refill.issued := True }

  // ---- the response -------------------------------------------------------
  val hitResponse = lookup.valid && !lookup.write && lookup.hit
  io.core.ready := ready
  io.core.rvalid := hitResponse || refill.done
  io.core.rdata := Mux(refill.done, refill.captured, readData)
}
