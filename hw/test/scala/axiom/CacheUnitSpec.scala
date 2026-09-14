package axiom

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite

/** The cache on its own, driven through its two ports.
  *
  * Through a core, a cache fault looks like a wrong register several hundred
  * cycles later. Here it looks like a wrong word on the cycle it comes back.
  */
class CacheUnitSpec extends AnyFunSuite {

  private val MemoryBytes = 32768
  private val Words = MemoryBytes / 8

  private def compiled(bytes: Int, lineBytes: Int, latency: Int) =
    SimConfig.withVerilator.workspacePath("simWorkspace")
      .workspaceName(s"CacheUnit_${bytes}_${lineBytes}_$latency")
      .compile(CacheUnit(bytes = bytes, lineBytes = lineBytes,
        memoryBytes = MemoryBytes, writes = true))

  /** Drive the memory side: accept a command, wait, answer. One at a time,
    * which is what the cache is entitled to assume.
    */
  private class Memory(dut: CacheUnit, latency: Int, contents: Array[Long]) {
    var countdown = 0
    var reading = false
    var address = 0

    def tick(): Unit = {
      dut.io.memory.ready #= countdown == 0
      dut.io.memory.rvalid #= false
      if (countdown > 0) {
        countdown -= 1
        if (countdown == 0 && reading) {
          dut.io.memory.rvalid #= true
          dut.io.memory.rdata #= BigInt(contents(address)) & ((BigInt(1) << 64) - 1)
        }
      } else if (dut.io.memory.enable.toBoolean) {
        address = (dut.io.memory.address.toBigInt.toInt / 8) % Words
        reading = !dut.io.memory.write.toBoolean
        if (!reading) {
          val data = dut.io.memory.wdata.toBigInt
          val mask = dut.io.memory.mask.toBigInt.toInt
          var value = BigInt(contents(address)) & ((BigInt(1) << 64) - 1)
          for (b <- 0 until 8 if (mask & (1 << b)) != 0) {
            val shift = b * 8
            value = (value & ~(BigInt(0xff) << shift)) | (((data >> shift) & 0xff) << shift)
          }
          contents(address) = value.toLong
        }
        countdown = latency
      }
    }
  }

  /** Issue a read and return the word, however many cycles it takes. */
  private def read(dut: CacheUnit, memory: Memory, byteAddress: Int, limit: Int = 500): BigInt = {
    dut.io.core.enable #= true
    dut.io.core.write #= false
    dut.io.core.address #= byteAddress
    dut.io.core.mask #= 0
    dut.io.core.wdata #= 0

    var accepted = false
    var answer: BigInt = null
    var spent = 0
    while (answer == null) {
      memory.tick()
      dut.clockDomain.waitSampling()
      if (!accepted && dut.io.core.ready.toBoolean) {
        accepted = true
        dut.io.core.enable #= false
      }
      if (accepted && dut.io.core.rvalid.toBoolean) answer = dut.io.core.rdata.toBigInt
      spent += 1
      assert(spent < limit, f"no answer for 0x$byteAddress%x after $spent cycles")
    }
    answer
  }

  private def write(dut: CacheUnit, memory: Memory, byteAddress: Int, value: BigInt): Unit = {
    dut.io.core.enable #= true
    dut.io.core.write #= true
    dut.io.core.address #= byteAddress
    dut.io.core.mask #= 0xff
    dut.io.core.wdata #= value

    var spent = 0
    var accepted = false
    while (!accepted) {
      memory.tick()
      dut.clockDomain.waitSampling()
      if (dut.io.core.ready.toBoolean) accepted = true
      spent += 1
      assert(spent < 500, "the cache never took the store")
    }
    dut.io.core.enable #= false
    memory.tick()
    dut.clockDomain.waitSampling()
  }

  private def scenario(name: String, bytes: Int, lineBytes: Int, latency: Int)
                      (body: (CacheUnit, Memory, Array[Long]) => Unit): Unit =
    test(s"$name (${bytes}B cache, ${lineBytes}B line, $latency cycle memory)") {
      compiled(bytes, lineBytes, latency).doSim(seed = 42) { dut =>
        val contents = Array.tabulate(Words)(i => 0x1000L + i)
        val memory = new Memory(dut, latency, contents)
        dut.io.core.enable #= false
        dut.io.core.write #= false
        dut.io.core.address #= 0
        dut.io.core.wdata #= 0
        dut.io.core.mask #= 0
        dut.io.memory.ready #= true
        dut.io.memory.rvalid #= false
        dut.io.memory.rdata #= 0
        dut.clockDomain.forkStimulus(10)
        SimTimeout(200000)
        dut.clockDomain.waitSampling(4)
        body(dut, memory, contents)
      }
    }

  for (line <- Seq(16, 32, 64); latency <- Seq(1, 8); size <- Seq(1024, 4096)) {
    scenario(s"every word of several lines reads back [$size]", size, line, latency) { (dut, memory, contents) =>
      for (i <- 0 until 24) {
        val got = read(dut, memory, i * 8)
        assert(got == BigInt(contents(i)), f"word $i: got 0x$got%x want 0x${contents(i)}%x")
      }
    }

    scenario(s"reading the same line twice hits the second time [$size]", size, line, latency) { (dut, memory, contents) =>
      for (_ <- 0 until 3; i <- 0 until 4) {
        val got = read(dut, memory, i * 8)
        assert(got == BigInt(contents(i)), f"word $i: got 0x$got%x")
      }
    }

    scenario(s"a store is visible to a later load [$size]", size, line, latency) { (dut, memory, _) =>
      write(dut, memory, 0, 0xdeadbeefL)
      assert(read(dut, memory, 0) == BigInt(0xdeadbeefL), "store then load")
      write(dut, memory, 0, 0xfeedfaceL)
      assert(read(dut, memory, 0) == BigInt(0xfeedfaceL), "store to a line already present")
    }

    scenario(s"lines are replaced when the cache wraps [$size]", size, line, latency) { (dut, memory, contents) =>
      val stride = 1024
      for (round <- 0 until 2; i <- 0 until 3) {
        val address = i * stride
        val got = read(dut, memory, address)
        assert(got == BigInt(contents(address / 8)),
          f"round $round address 0x$address%x: got 0x$got%x want 0x${contents(address / 8)}%x")
      }
    }
  }
}
