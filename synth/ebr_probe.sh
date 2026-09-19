#!/usr/bin/env bash
# Price the block RAM's own output register.
#
# yosys 0.33 maps every inferred memory to DP16KD with REGMODE=NOREG, and in
# that mode nextpnr charges 5.8 ns from clock to data out. The cell has an
# output register of its own; this instantiates it both ways and reports the
# clock-to-out for each, which is the number that decides whether a memory
# access is one stage or two.
set -euo pipefail
cd "$(dirname "$0")"
for mode in NOREG OUTREG; do
  sed "s/OUTREG/$mode/g" ebr_outreg.v > out/ebr_$mode.v
  yosys -q -p "read_verilog -lib /usr/share/yosys/ecp5/cells_bb.v; read_verilog out/ebr_$mode.v; synth_ecp5 -top ebr_$mode -json out/ebr_$mode.json"
  nextpnr-ecp5 --json out/ebr_$mode.json --45k --package CABGA381 --freq 400 \
    --out-of-context --timing-allow-fail -q --log out/ebr_$mode.log
  echo "$mode: $(grep -a 'Max frequency' out/ebr_$mode.log | tail -1 | sed 's/.*clock/clock/')"
  grep -a -A3 "Critical path report" out/ebr_$mode.log | grep -E "Source" | head -1
done
