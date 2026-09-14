package axiom

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite

/** A cache wired to the memory it is meant to sit in front of.
  *
  * The cache is tested on its own against a hand-written memory, and the
  * memory is simple enough to look right. Between those two facts is the
  * handshake that joins them, and that is where the faults were: this pair is
  * the smallest thing that has one.
  */
class CacheMemorySpec extends AnyFunSuite {

  private val Words = 4096

  case class Pair(cacheBytes: Int, lineBytes: Int, latency: Int) extends Component {
    val cache = CacheUnit(bytes = cacheBytes, lineBytes = lineBytes,
      memoryBytes = Words * 8, writes = true)
    val ram = BackingRam(words = Words, latency = latency, ports = 1)

    val io = new Bundle {
      val core = slave(DBus(Isa.XLEN, Isa.XLEN))
      val debug = new Bundle {
        val enable = in Bool ()
        val write = in Bool ()
        val address = in UInt (log2Up(Words) bits)
        val wdata = in Bits (Isa.XLEN bits)
      }
    }

    io.core <> cache.io.core
    ram.io.port(0).enable := cache.io.memory.enable
    ram.io.port(0).write := cache.io.memory.write
    ram.io.port(0).address := cache.io.memory.address
    ram.io.port(0).mask := cache.io.memory.mask
    ram.io.port(0).wdata := cache.io.memory.wdata
    cache.io.memory.ready := ram.io.port(0).ready
    cache.io.memory.rvalid := ram.io.port(0).rvalid
    cache.io.memory.rdata := ram.io.port(0).rdata

    ram.io.debug.enable := io.debug.enable
    ram.io.debug.write := io.debug.write
    ram.io.debug.address := io.debug.address
    ram.io.debug.wdata := io.debug.wdata
  }

  /** Two caches on one memory, which is what the core has. */
  case class Both(lineBytes: Int, latency: Int) extends Component {
    val a = CacheUnit(4096, lineBytes, Words * 8, writes = false)
    val b = CacheUnit(4096, lineBytes, Words * 8, writes = true)
    val ram = BackingRam(words = Words, latency = latency, ports = 2)

    val io = new Bundle {
      val coreA = slave(DBus(Isa.XLEN, Isa.XLEN))
      val coreB = slave(DBus(Isa.XLEN, Isa.XLEN))
      val debug = new Bundle {
        val enable = in Bool ()
        val write = in Bool ()
        val address = in UInt (log2Up(Words) bits)
        val wdata = in Bits (Isa.XLEN bits)
      }
    }

    io.coreA <> a.io.core
    io.coreB <> b.io.core
    for ((cache, port) <- Seq(a, b).zip(ram.io.port)) {
      port.enable := cache.io.memory.enable
      port.write := cache.io.memory.write
      port.address := cache.io.memory.address
      port.mask := cache.io.memory.mask
      port.wdata := cache.io.memory.wdata
      cache.io.memory.ready := port.ready
      cache.io.memory.rvalid := port.rvalid
      cache.io.memory.rdata := port.rdata
    }
    ram.io.debug.enable := io.debug.enable
    ram.io.debug.write := io.debug.write
    ram.io.debug.address := io.debug.address
    ram.io.debug.wdata := io.debug.wdata
  }

  private def read(dut: Pair, byteAddress: Int): BigInt = {
    dut.io.core.enable #= true
    dut.io.core.write #= false
    dut.io.core.address #= byteAddress
    dut.io.core.mask #= 0xff
    dut.io.core.wdata #= 0

    var accepted = false
    var answer: BigInt = null
    var spent = 0
    while (answer == null) {
      dut.clockDomain.waitSampling()
      if (!accepted && dut.io.core.ready.toBoolean) {
        accepted = true
        dut.io.core.enable #= false
      }
      if (accepted && dut.io.core.rvalid.toBoolean) answer = dut.io.core.rdata.toBigInt
      spent += 1
      assert(spent < 400, f"no answer for 0x$byteAddress%x")
    }
    dut.clockDomain.waitSampling()
    answer
  }

  for (line <- Seq(16, 32); latency <- Seq(1, 8)) {
    test(s"two caches sharing one ${latency} cycle memory (${line}B line)") {
      SimConfig.withVerilator.workspacePath("simWorkspace")
        .workspaceName(s"Both_${line}_$latency")
        .compile(Both(line, latency))
        .doSim(seed = 7) { dut =>
          for (c <- Seq(dut.io.coreA, dut.io.coreB)) {
            c.enable #= false; c.write #= false; c.address #= 0; c.wdata #= 0; c.mask #= 0
          }
          dut.io.debug.enable #= false
          dut.io.debug.write #= false
          dut.io.debug.address #= 0
          dut.io.debug.wdata #= 0
          dut.clockDomain.forkStimulus(10)
          SimTimeout(500000)
          dut.clockDomain.waitSampling(4)

          dut.io.debug.enable #= true
          dut.io.debug.write #= true
          for (i <- 0 until 128) {
            dut.io.debug.address #= i
            dut.io.debug.wdata #= 0x100 + i
            dut.clockDomain.waitSampling()
          }
          dut.io.debug.enable #= false
          dut.io.debug.write #= false
          dut.clockDomain.waitSampling(2)

          // Port A reads continuously in the background, the way an
          // instruction port does, while port B reads its own addresses.
          val failures = scala.collection.mutable.ArrayBuffer[String]()
          val busy = fork {
            var i = 0
            while (true) {
              dut.io.coreA.enable #= true
              dut.io.coreA.address #= (i % 64) * 8
              dut.clockDomain.waitSampling()
              if (dut.io.coreA.ready.toBoolean) {
                val want = BigInt(0x100 + (i % 64))
                val expectAt = i
                fork {
                  var spent = 0
                  var done = false
                  while (!done && spent < 400) {
                    dut.clockDomain.waitSampling()
                    if (dut.io.coreA.rvalid.toBoolean) {
                      val got = dut.io.coreA.rdata.toBigInt
                      if (got != want) failures += f"A word $expectAt: got 0x$got%x want 0x$want%x"
                      done = true
                    }
                    spent += 1
                  }
                }
                i += 1
              }
            }
          }

          for (round <- 0 until 2; i <- 0 until 24) {
            dut.io.coreB.enable #= true
            dut.io.coreB.write #= false
            dut.io.coreB.address #= (64 + i) * 8
            dut.io.coreB.mask #= 0xff
            var accepted = false
            var answer: BigInt = null
            var spent = 0
            while (answer == null && spent < 400) {
              dut.clockDomain.waitSampling()
              if (!accepted && dut.io.coreB.ready.toBoolean) {
                accepted = true
                dut.io.coreB.enable #= false
              }
              if (accepted && dut.io.coreB.rvalid.toBoolean) answer = dut.io.coreB.rdata.toBigInt
              spent += 1
            }
            assert(answer != null, s"B round $round word $i never answered")
            assert(answer == BigInt(0x100 + 64 + i),
              f"B round $round word $i: got 0x$answer%x want 0x${0x100 + 64 + i}%x")
          }
          assert(failures.isEmpty, failures.take(4).mkString("; "))
        }
    }

    test(s"reads come back through a ${latency} cycle memory (${line}B line)") {
      SimConfig.withVerilator.workspacePath("simWorkspace")
        .workspaceName(s"Pair_${line}_$latency")
        .compile(Pair(4096, line, latency))
        .doSim(seed = 7) { dut =>
          dut.io.core.enable #= false
          dut.io.core.write #= false
          dut.io.core.address #= 0
          dut.io.core.wdata #= 0
          dut.io.core.mask #= 0
          dut.io.debug.enable #= false
          dut.io.debug.write #= false
          dut.io.debug.address #= 0
          dut.io.debug.wdata #= 0
          dut.clockDomain.forkStimulus(10)
          SimTimeout(500000)
          dut.clockDomain.waitSampling(4)

          // Fill memory the way the test bench fills a core's.
          dut.io.debug.enable #= true
          dut.io.debug.write #= true
          for (i <- 0 until 64) {
            dut.io.debug.address #= i
            dut.io.debug.wdata #= 0x100 + i
            dut.clockDomain.waitSampling()
          }
          dut.io.debug.enable #= false
          dut.io.debug.write #= false
          dut.clockDomain.waitSampling(2)

          for (i <- 0 until 24) {
            val got = read(dut, i * 8)
            assert(got == BigInt(0x100 + i), f"word $i: got 0x$got%x want 0x${0x100 + i}%x")
          }
        }
    }
  }
}
