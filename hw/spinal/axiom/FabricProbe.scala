package axiom

import spinal.core._

/** What one clock period buys on this part, measured rather than assumed.
  *
  * Every structure the pipeline is built out of, each on its own register to
  * register path, with nothing else to share routing with. The numbers that
  * come back are a floor: in the core these blocks have real fanout and real
  * distance, so they can only be slower. A floor is still the thing to design
  * against, because a stage that cannot hold one of these at the target clock
  * cannot hold it at all, however it is written.
  *
  * Synthesised on its own with `GenerateFabricProbe`, one structure per build,
  * because nextpnr reports the worst path in a netlist and nothing else.
  *
  * What it measured on an LFE5U-45F at a 400 MHz target, with abc9:
  *
  * {{{
  *   one LUT level                     1.6 ns    637 MHz
  *   two LUT levels                    1.8 ns    584 MHz
  *   distributed RAM read              4.0 ns    249 MHz
  *   one forwarding leg, compare + mux 4.2 ns    241 MHz
  *   64-bit add                        4.8 ns    209 MHz
  *   32-bit add                        3.6 ns    274 MHz
  *   add then multiplex                5.9 ns    172 MHz
  *   block RAM read                    6.7 ns    150 MHz
  *   64-bit add as two carry-selects   6.7 ns    151 MHz
  *   18x18 multiply                    7.2 ns    140 MHz
  *   64-bit funnel shift               7.3 ns    137 MHz
  *   forwarding leg then add           8.0 ns    125 MHz
  *   four forwarding legs in series   12.0 ns     83 MHz
  * }}}
  *
  * Three of those are worth stopping at.
  *
  * **Carry select is slower than the ripple it replaces.** The textbook fix
  * for a long carry chain buys two shorter chains and a multiplexer, and on
  * this part the chain is dedicated silicon at fifty picoseconds a bit while
  * the multiplexer is a LUT plus a wire. It measured 6.7 ns against the plain
  * adder's 4.8. The carry chain is not what is slow here.
  *
  * **The bypass network is what is slow here.** One leg is 4.2 ns and four in
  * series are 12.0, two thirds of it routing, which puts a hard 83 MHz on any
  * stage that reads an operand through the whole forwarding network. That is
  * the shape of the read stage.
  *
  * **A block RAM read is 6.7 ns whatever you put around it**, nearly all of it
  * inside the primitive, so 150 MHz is the ceiling for a design that reads one
  * in a cycle. Adding a register behind it did not move the number, so the
  * cell's own output register is not being used and would have to be asked for
  * explicitly.
  */
case class FabricProbe(xlen: Int = 64, only: Int = -1) extends Component {

  val io = new Bundle {
    val a = in Bits (xlen bits)
    val b = in Bits (xlen bits)
    val sel = in UInt (5 bits)
    val result = out Bits (20 * xlen bits)
  }

  /** One path: registers in, the structure, a register out.
    *
    * `only` picks a single one, because nextpnr reports the worst path in the
    * design and nothing else: sixteen structures in one netlist answer one
    * question sixteen times over. Each gets its own build.
    */
  def path(index: Int)(body: (Bits, Bits) => Bits): Unit = {
    if (only >= 0 && index != only) { io.result(index * xlen, xlen bits) := 0; return }
    val a = RegNext(io.a).setName(s"p${index}_a")
    val b = RegNext(io.b).setName(s"p${index}_b")
    val value = RegNext(body(a, b)).setName(s"p${index}_r")
    io.result(index * xlen, xlen bits) := value
  }

  // 0: one LUT level. The floor of the floor: clock to Q, one LUT, setup.
  path(0)((a, b) => a & b)

  // 1: two LUT levels.
  path(1)((a, b) => (a & b) | (~a & ~b))

  // 2: four LUT levels, which is what a small decoder or a four input
  // multiplexer chain comes to.
  path(2) { (a, b) =>
    val l1 = a & b
    val l2 = l1 | (~a)
    val l3 = l2 & (b | a)
    l3 ^ (l1 & l2)
  }

  // 3: the full width ripple add, which is what every address and every ALU
  // result costs today.
  path(3)((a, b) => (a.asUInt + b.asUInt).asBits)

  // 4: the same add as two halves with a carry select on the top, which is the
  // standard way of buying back a carry chain with area.
  path(4) { (a, b) =>
    val half = xlen / 2
    val lowSum = a(half - 1 downto 0).asUInt +^ b(half - 1 downto 0).asUInt
    val highA = a(xlen - 1 downto half).asUInt
    val highB = b(xlen - 1 downto half).asUInt
    val high0 = highA + highB
    val high1 = highA + highB + 1
    val high = Mux(lowSum.msb, high1, high0)
    (high ## lowSum(half - 1 downto 0)).asBits
  }

  // 5: four way carry select, sixteen bits of chain and two multiplexer levels.
  path(5) { (a, b) =>
    val chunk = xlen / 4
    val parts = for (i <- 0 until 4) yield {
      val pa = a(i * chunk, chunk bits).asUInt
      val pb = b(i * chunk, chunk bits).asUInt
      (pa +^ pb, pa +^ pb + 1)
    }
    var carry = False
    val pieces = for ((zero, one) <- parts) yield {
      val picked = Mux(carry, one, zero)
      carry = picked.msb
      picked(chunk - 1 downto 0).asBits
    }
    pieces.reverse.reduce((high: Bits, low: Bits) => high ## low)
  }

  // 6: a thirty-two to one multiplexer of full width words, which is the shape
  // of a register file read built out of flip-flops, and of any table indexed
  // by a register.
  path(6) { (a, b) =>
    val entries = Vec.tabulate(32)(i => (a.asUInt + i).asBits)
    entries.read(RegNext(io.sel))
  }

  // 7: an equality compare against a full width value feeding a multiplexer,
  // which is one leg of the forwarding network.
  path(7) { (a, b) =>
    val hit = a(4 downto 0).asUInt === RegNext(io.sel)
    Mux(hit, b, a)
  }

  // 8: four of those in series, which is what a consumer reading an operand
  // through the whole bypass network walks.
  path(8) { (a, b) =>
    val sel = RegNext(io.sel)
    var value = a
    for (stage <- 0 until 4) {
      val hit = value(4 downto 0).asUInt === (sel + stage)
      value = Mux(hit, b ^ B(stage, xlen bits), value)
    }
    value
  }

  // 9: a shift by a variable amount, the funnel the shifter is built from.
  path(9)((a, b) => (a.asUInt |>> b(5 downto 0).asUInt).asBits)

  // 10: a distributed RAM read, which is how the register file is built.
  path(10) { (a, b) =>
    val mem = Mem(Bits(xlen bits), 32)
    mem.write(address = RegNext(io.sel), data = b, enable = a.lsb)
    mem.readAsync(RegNext(io.sel))
  }

  // 11: a block RAM read, which is the memory and the caches.
  path(11) { (a, b) =>
    val mem = Mem(Bits(xlen bits), 1024)
    mem.write(address = RegNext(io.sel).resized, data = b, enable = a.lsb)
    mem.readSync(address = RegNext(io.sel).resized)
  }

  // 12: an 18 by 18 multiply, one DSP cell.
  path(12) { (a, b) =>
    val product = a(17 downto 0).asSInt * b(17 downto 0).asSInt
    product.resize(xlen).asBits
  }

  // 13: a full width equality compare feeding a wide fan of consumers, which
  // is the shape of the generation compare and of the trap arbitration.
  path(13) { (a, b) =>
    val same = a === b
    Mux(same, b, a)
  }

  // 14: an eight way priority multiplexer of full width words, which is what a
  // chain of conditional assignments elaborates to.
  path(14) { (a, b) =>
    val sel = RegNext(io.sel)
    var value = a
    for (i <- 0 until 8) when(sel === i) { value = b ^ B(i, xlen bits) }
    value
  }

  // 16: the same block RAM with its output register used, which is what the
  // primitive offers to split its own access across two cycles.
  path(16) { (a, b) =>
    val mem = Mem(Bits(xlen bits), 1024)
    mem.write(address = RegNext(io.sel).resized, data = b, enable = a.lsb)
    RegNext(mem.readSync(address = RegNext(io.sel).resized))
  }

  // 17: the multiplier with a register on its output, so the cell's own
  // pipeline register can be used instead of the fabric's.
  path(17) { (a, b) =>
    RegNext((a(17 downto 0).asSInt * b(17 downto 0).asSInt).resize(xlen).asBits)
  }

  // 18: a thirty-two bit add, for the question of what the width itself costs.
  path(18) { (a, b) =>
    (a(31 downto 0).asUInt + b(31 downto 0).asUInt).resize(xlen).asBits
  }

  // 19: one forwarding leg feeding an add, which is the execute stage as it
  // stands: an operand chosen from the bypass network and then added.
  path(19) { (a, b) =>
    val sel = RegNext(io.sel)
    val chosen = Mux(a(4 downto 0).asUInt === sel, b, a)
    (chosen.asUInt + b.asUInt).asBits
  }

  // 15: add then multiplex, the ALU result path in miniature.
  path(15) { (a, b) =>
    val sum = (a.asUInt + b.asUInt).asBits
    val sel = RegNext(io.sel)
    Mux(sel(0), sum, a & b)
  }
}

object GenerateFabricProbe extends App {
  val which = if (args.nonEmpty) args(0).toInt else -1
  AxiomSpinalConfig().generateVerilog(FabricProbe(only = which).setDefinitionName("FabricProbe"))
  println("Wrote generated/FabricProbe.v")
}
