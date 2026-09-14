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
  * and the narrow bypass network. Both are measured choices rather than
  * defaults, which is why they have their own target.
  */
object GenerateAxiomCoreLean extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomCore(forwardBase = false, withDebugRegFilePort = false)
      .setDefinitionName("AxiomCoreLean"))
  println("Wrote generated/AxiomCoreLean.v")
}

/** The same, keeping base forwarding, to separate the two effects. */
object GenerateAxiomCoreNoDebug extends App {
  AxiomSpinalConfig().generateVerilog(
    new AxiomCore(withDebugRegFilePort = false)
      .setDefinitionName("AxiomCoreNoDebug"))
  println("Wrote generated/AxiomCoreNoDebug.v")
}

object GenerateAxiomAll extends App {
  AxiomSpinalConfig().generateVerilog(new AxiomSoc())
  AxiomSpinalConfig().generateVerilog(new AxiomCore())
  AxiomSpinalConfig().generateVhdl(new AxiomSoc())
  println("Wrote Verilog and VHDL for AxiomSoc into generated/")
}
