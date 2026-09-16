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

  private val Mask64 = (BigInt(1) << 64) - 1

  /** Drive the memory side: accept a command, wait, answer.
    *
    * Called once per cycle immediately after the clock edge. It looks at the
    * command the cache is offering in this cycle against the ready it was
    * given for this cycle, and then presents what the memory offers in the
    * next one. Sampling the command before the edge reads the previous
    * cycle's value instead, which did not matter while the cache held a
    * command up until it was answered, and did the moment it stopped: a
    * refill that asks for its words back to back had every second command
    * ignored, and waited forever for answers to commands the memory never saw.
    *
    * One command at a time, which is the least a memory may do. The cache asks
    * for a whole line without waiting for the words in front, so it has to
    * cope with a memory that takes them one by one; the pipelined case is
    * [[BackingRam]]'s to prove.
    */
  private class Memory(dut: CacheUnit, latency: Int, contents: Array[Long]) {
    private var remaining = 0
    private var reading = false
    private var address = 0

    /** The ready the cache is being shown in the cycle now running. */
    private var offering = false

    present()

    private def present(): Unit = {
      offering = remaining <= 1
      dut.io.memory.ready #= offering
      val responds = remaining == 1 && reading
      dut.io.memory.rvalid #= responds
      if (responds) dut.io.memory.rdata #= BigInt(contents(address)) & Mask64
    }

    def tick(): Unit = {
      if (offering && dut.io.memory.enable.toBoolean) {
        address = (dut.io.memory.address.toBigInt.toInt / 8) % Words
        reading = !dut.io.memory.write.toBoolean
        if (!reading) {
          val data = dut.io.memory.wdata.toBigInt
          val mask = dut.io.memory.mask.toBigInt.toInt
          var value = BigInt(contents(address)) & Mask64
          for (b <- 0 until 8 if (mask & (1 << b)) != 0) {
            val shift = b * 8
            value = (value & ~(BigInt(0xff) << shift)) | (((data >> shift) & 0xff) << shift)
          }
          contents(address) = value.toLong
        }
        remaining = latency
      } else if (remaining > 0) {
        remaining -= 1
      }
      present()
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
      dut.clockDomain.waitSampling()
      memory.tick()
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
      dut.clockDomain.waitSampling()
      memory.tick()
      if (dut.io.core.ready.toBoolean) accepted = true
      spent += 1
      assert(spent < 500, "the cache never took the store")
    }
    dut.io.core.enable #= false
    dut.clockDomain.waitSampling()
    memory.tick()
  }

  private def scenario(name: String, bytes: Int, lineBytes: Int, latency: Int)
                      (body: (CacheUnit, Memory, Array[Long]) => Unit): Unit =
    test(s"$name (${bytes}B cache, ${lineBytes}B line, $latency cycle memory)") {
      compiled(bytes, lineBytes, latency).doSim(seed = 42) { dut =>
        val contents = Array.tabulate(Words)(i => 0x1000L + i)
        dut.io.core.enable #= false
        dut.io.core.write #= false
        dut.io.core.address #= 0
        dut.io.core.wdata #= 0
        dut.io.core.mask #= 0
        dut.io.memory.rdata #= 0
        // Presents the memory's idle state as it is built, so nothing is
        // driven twice and nothing is left undriven.
        val memory = new Memory(dut, latency, contents)
        dut.clockDomain.forkStimulus(10)
        SimTimeout(200000)
        for (_ <- 0 until 4) { dut.clockDomain.waitSampling(); memory.tick() }
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
