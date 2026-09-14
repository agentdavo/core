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

object GenerateAxiomAll extends App {
  AxiomSpinalConfig().generateVerilog(new AxiomSoc())
  AxiomSpinalConfig().generateVhdl(new AxiomSoc())
  println("Wrote Verilog and VHDL for AxiomSoc into generated/")
}
