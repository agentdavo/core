package core

import spinal.core._

/** Shared elaboration settings. Synchronous, active-high reset, which is what
  * both Verilator and most FPGA flows are happiest with.
  */
object CoreSpinalConfig {
  def apply(targetDirectory: String = "generated"): SpinalConfig = SpinalConfig(
    targetDirectory = targetDirectory,
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH,
      resetKind = SYNC
    ),
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )
}

/** Emit the bare CPU, for dropping into a larger system. */
object GenerateCore extends App {
  CoreSpinalConfig().generateVerilog(new Core(CoreConfig()))
  println("Wrote generated/Core.v")
}

/** Emit the CPU plus its tightly coupled memory. */
object GenerateCoreSoc extends App {
  CoreSpinalConfig().generateVerilog(new CoreSoc(CoreConfig(), memWords = 4096))
  println("Wrote generated/CoreSoc.v")
}

/** Emit both, in Verilog and VHDL. */
object GenerateAll extends App {
  CoreSpinalConfig().generateVerilog(new Core(CoreConfig()))
  CoreSpinalConfig().generateVerilog(new CoreSoc(CoreConfig(), memWords = 4096))
  CoreSpinalConfig().generateVhdl(new Core(CoreConfig()))
  CoreSpinalConfig().generateVhdl(new CoreSoc(CoreConfig(), memWords = 4096))
  println("Wrote Verilog and VHDL for Core and CoreSoc into generated/")
}
