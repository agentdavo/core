// =============================================================================
//  core — a CPU and instruction set designed from scratch in SpinalHDL
// =============================================================================

ThisBuild / organization := "com.fathomtree"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.12"

val spinalVersion = "1.15.0"

lazy val root = (project in file("."))
  .settings(
    name := "core",

    // SpinalHDL-idiomatic source layout: RTL under hw/spinal, testbenches under hw/test.
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal",
    Test / scalaSource    := baseDirectory.value / "hw" / "test" / "scala",

    libraryDependencies ++= Seq(
      "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
      "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion,
      compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
      "org.scalatest"        %% "scalatest"      % "3.2.19" % Test
    ),

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:postfixOps"
    ),

    // SpinalSim shells out to Verilator and then to a JNI shared object; forking keeps
    // each test run in a clean JVM and makes native library loading reliable.
    fork := true,
    Test / fork := true,
    Test / parallelExecution := false,
    Test / testForkedParallel := false,
    run / connectInput := true,
    outputStrategy := Some(StdoutOutput)
  )
