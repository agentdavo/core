#!/usr/bin/env bash
#
# Synthesise and place-and-route a generated design for the Lattice ECP5 with
# yosys and nextpnr, then write a machine-readable report.
#
# The default is an out-of-context build: no IO buffers are inserted and
# nothing is bound to package pins. That is what you want when the question is
# "how big and how fast is this logic", because binding to pins adds IO delay
# that has nothing to do with the design and forces a pinout you do not have
# yet. Pass --with-pins plus an LPF when you are actually building a bitstream.

set -euo pipefail

TOP=AxiomSoc
VERILOG=""
DEVICE=45k
PACKAGE=CABGA381
FREQ=100
OUT_OF_CONTEXT=1
LPF=""
SEED=1
SPEED=""
ABC9=0
NODSP=0
OUTDIR=""
PNR=1

usage() {
  cat <<'USAGE'
usage: synth/synth.sh [options]

  --top NAME          top module name                      (default AxiomSoc)
  --verilog FILE      input Verilog                        (default generated/<top>.v)
  --device D          25k | 45k | 85k | um5g-85k           (default 45k)
  --package NAME      package for pin binding              (default CABGA381)
  --freq MHZ          timing target, also the fmax probe   (default 100)
  --with-pins         insert IO buffers and bind to pins; needs --lpf
  --lpf FILE          pin constraint file, implies --with-pins
  --seed N            place and route seed                 (default 1)
  --speed N           ECP5 speed grade, 6 | 7 | 8      (default: the part's)
  --abc9              use the abc9 technology mapping flow
  --nodsp             keep multipliers in fabric instead of DSP blocks
  --synth-only        stop after yosys
  --outdir DIR        output directory                     (default synth/out/<top>)
  -h, --help          this message

Raising --freq past what the design achieves is the point: nextpnr reports the
fmax it reached either way, and a target it cannot meet makes the placer work
harder on the real critical path rather than stopping once it is satisfied.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --top) TOP="$2"; shift 2 ;;
    --verilog) VERILOG="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --freq) FREQ="$2"; shift 2 ;;
    --with-pins) OUT_OF_CONTEXT=0; shift ;;
    --lpf) LPF="$2"; OUT_OF_CONTEXT=0; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --speed) SPEED="$2"; shift 2 ;;
    --abc9) ABC9=1; shift ;;
    --nodsp) NODSP=1; shift ;;
    --synth-only) PNR=0; shift ;;
    --outdir) OUTDIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -z "$VERILOG" ]] && VERILOG="generated/${TOP}.v"
[[ -z "$OUTDIR" ]] && OUTDIR="synth/out/${TOP}"

if [[ ! -f "$VERILOG" ]]; then
  echo "no such Verilog file: $VERILOG" >&2
  echo "generate it first, for example: sbt \"runMain axiom.GenerateAxiomSoc\"" >&2
  exit 1
fi

mkdir -p "$OUTDIR"

SYNTH_FLAGS="-top ${TOP} -json ${OUTDIR}/${TOP}.json"
[[ $ABC9 -eq 1 ]] && SYNTH_FLAGS="$SYNTH_FLAGS -abc9"
[[ $NODSP -eq 1 ]] && SYNTH_FLAGS="$SYNTH_FLAGS -nodsp"
# yosys only inserts IO buffers when asked, so an out-of-context build is the
# default here and --with-pins opts back in.
[[ $OUT_OF_CONTEXT -eq 0 ]] && SYNTH_FLAGS="$SYNTH_FLAGS -iopad"

echo "== yosys: ${TOP} from ${VERILOG}"
yosys -q -l "${OUTDIR}/yosys.log" \
  -p "read_verilog -sv ${VERILOG}; synth_ecp5 ${SYNTH_FLAGS}; tee -o ${OUTDIR}/stat.txt stat -top ${TOP}"

if [[ $PNR -eq 0 ]]; then
  echo "== stopped after synthesis, see ${OUTDIR}/stat.txt"
  exit 0
fi

PNR_FLAGS=(--json "${OUTDIR}/${TOP}.json" --top "${TOP}" "--${DEVICE}"
           --package "${PACKAGE}" --freq "${FREQ}" --seed "${SEED}"
           --report "${OUTDIR}/report.json" --textcfg "${OUTDIR}/${TOP}.config"
           --timing-allow-fail)

# The speed grade is a property of the part you actually buy, not of the
# design, so it is off by default: comparing two revisions of the RTL means
# holding it fixed. It is here because the difference between a -6 and an -8 is
# real money and worth knowing before promising a frequency.
[[ -n "$SPEED" ]] && PNR_FLAGS+=(--speed "$SPEED")

if [[ $OUT_OF_CONTEXT -eq 1 ]]; then
  PNR_FLAGS+=(--out-of-context)
elif [[ -n "$LPF" ]]; then
  PNR_FLAGS+=(--lpf "$LPF")
else
  PNR_FLAGS+=(--lpf-allow-unconstrained)
fi

echo "== nextpnr-ecp5: ${DEVICE} at ${FREQ} MHz, seed ${SEED}"
nextpnr-ecp5 "${PNR_FLAGS[@]}" -l "${OUTDIR}/nextpnr.log" 2>&1 | tail -5 || true

echo "== report"
python3 synth/report.py "${OUTDIR}"
