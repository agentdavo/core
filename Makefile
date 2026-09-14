# Convenience wrappers around sbt. Everything here is reachable through sbt
# directly; nothing in the build depends on make.

SBT ?= sbt
VERILATOR ?= verilator
LINT_FLAGS := --lint-only -Wall -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM

.PHONY: help test test-core test-axiom verilog vhdl generate lint demo demo-axiom clean distclean

help:
	@echo "make test        run the whole verification suite, both architectures"
	@echo "make test-core   CORE-32 suites only"
	@echo "make test-axiom  Axiom-64 suites only"
	@echo "make verilog     elaborate Core.v, CoreSoc.v and AxiomSoc.v into generated/"
	@echo "make vhdl        elaborate Verilog and VHDL for both architectures"
	@echo "make lint        run verilator --lint-only -Wall over the generated Verilog"
	@echo "make demo        assemble, disassemble and run a CORE-32 program"
	@echo "make demo-axiom  the same for Axiom-64"
	@echo "make clean       remove build output"
	@echo "make distclean   also remove the simulation workspace"
	@echo ""
	@echo "CORE_WAVE=1 or AXIOM_WAVE=1 captures FST waveforms into simWorkspace/"

test:
	$(SBT) test

test-core:
	$(SBT) "testOnly core.*"

test-axiom:
	$(SBT) "testOnly axiom.*"

verilog:
	$(SBT) "runMain core.GenerateCoreSoc" "runMain axiom.GenerateAxiomSoc"

vhdl generate:
	$(SBT) "runMain core.GenerateAll" "runMain axiom.GenerateAxiomAll"

demo:
	$(SBT) "Test/runMain core.Demo"

demo-axiom:
	$(SBT) "Test/runMain axiom.AxiomDemo"

lint: verilog
	$(VERILATOR) $(LINT_FLAGS) --top-module CoreSoc generated/CoreSoc.v
	$(VERILATOR) $(LINT_FLAGS) --top-module AxiomSoc generated/AxiomSoc.v

clean:
	rm -rf target project/target project/project generated

distclean: clean
	rm -rf simWorkspace
