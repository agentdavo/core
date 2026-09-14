# Convenience wrappers around sbt. Everything here is also reachable directly
# through sbt; nothing in the build depends on make.

SBT ?= sbt
VERILATOR ?= verilator

.PHONY: help test verilog vhdl generate lint clean distclean

help:
	@echo "make test       run the full verification suite under Verilator"
	@echo "make verilog    elaborate Core.v and CoreSoc.v into generated/"
	@echo "make vhdl       elaborate Verilog and VHDL into generated/"
	@echo "make lint       run verilator --lint-only over the generated Verilog"
	@echo "make clean      remove build output"
	@echo "make distclean  also remove the simulation workspace and ivy caches"
	@echo ""
	@echo "CORE_WAVE=1 make test   capture FST waveforms into simWorkspace/"

test:
	$(SBT) test

verilog:
	$(SBT) "runMain core.GenerateCoreSoc"

vhdl generate:
	$(SBT) "runMain core.GenerateAll"

lint: verilog
	$(VERILATOR) --lint-only -Wall \
	  -Wno-DECLFILENAME -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM \
	  --top-module CoreSoc generated/CoreSoc.v

clean:
	rm -rf target project/target project/project generated

distclean: clean
	rm -rf simWorkspace
