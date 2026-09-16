package axiom

import spinal.core._

/** Shared elaboration settings: synchronous, active-high reset. */
object AxiomSpinalConfig {
  def apply(targetDirectory: String = "generated"): SpinalConfig = SpinalConfig(
    targetDirectory = targetDirectory,
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH,
      resetKind = SYNC
    ),
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )
}

object GenerateAxiomSoc extends App {
  AxiomSpinalConfig().generateVerilog(new AxiomSoc())
  println("Wrote generated/AxiomSoc.v")
}

/** The core on its own, buses exposed. The right thing to synthesise when the
  * question is about the core rather than about its memory.
  */
object GenerateAxiomCore extends App {
  AxiomSpinalConfig().generateVerilog(new AxiomCore())
  println("Wrote generated/AxiomCore.v")
}

/** The core as it would go on a chip: no debug read port on the register file,
  * the narrow bypass network, and no cycle accounting. All three are measured
  * choices rather than defaults, which is why they have their own target.
  */
object GenerateAxiomCoreLean extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomCore(forwardBase = false, withDebugRegFilePort = false, withPerfCounters = false)
      .setDefinitionName("AxiomCoreLean"))
  println("Wrote generated/AxiomCoreLean.v")
}

/** No multiplier and no debug port, to see what the critical path is behind
  * the DSP cascade.
  */
object GenerateAxiomCoreNoMul extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomCore(withMultiplier = false, withDebugRegFilePort = false)
      .setDefinitionName("AxiomCoreNoMul"))
  println("Wrote generated/AxiomCoreNoMul.v")
}

/** The same, keeping base forwarding, to separate the two effects. */
object GenerateAxiomCoreNoDebug extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomCore(withDebugRegFilePort = false)
      .setDefinitionName("AxiomCoreNoDebug"))
  println("Wrote generated/AxiomCoreNoDebug.v")
}

/** The Base profile with caches, for measuring what they cost. */
object GenerateAxiomCached extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomSoc(memoryLatency = 8, withDebugRegFilePort = false,
      plugins = AxiomProfile.cached).setDefinitionName("AxiomCached"))
  println("Wrote generated/AxiomCached.v")
}

/** The geometry the sizing measurements actually recommend: a small
  * instruction cache and a data cache four times its size.
  */
object GenerateAxiomCachedLean extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomSoc(memoryLatency = 8, withDebugRegFilePort = false,
      icacheBytes = 1024, dcacheBytes = 4096,
      plugins = AxiomProfile.cached).setDefinitionName("AxiomCachedLean"))
  println("Wrote generated/AxiomCachedLean.v")
}

/** The same, with the caches removed, so the difference is the caches. */
object GenerateAxiomUncached extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomSoc(memoryLatency = 8, withDebugRegFilePort = false,
      icacheBytes = 0, dcacheBytes = 0,
      plugins = AxiomProfile.cached).setDefinitionName("AxiomUncached"))
  println("Wrote generated/AxiomUncached.v")
}

object GenerateAxiomAll extends App {
  AxiomSpinalConfig().generateVerilog(new AxiomSoc())
  AxiomSpinalConfig().generateVerilog(new AxiomCore())
  AxiomSpinalConfig().generateVhdl(new AxiomSoc())
  println("Wrote Verilog and VHDL for AxiomSoc into generated/")
}
