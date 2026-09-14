#!/usr/bin/env bash
#
# Run place and route several times with different seeds and aggregate the
# results into a Pareto of critical paths.
#
# One run does not give you a Pareto. nextpnr reports a single critical path
# per clock domain, and which path that is moves with the placement seed. A
# path that is critical under every seed is logic to restructure; a path that
# is critical under one seed lost a routing lottery, and reseeding is cheaper
# than redesigning. Only the aggregate separates the two.

set -euo pipefail

TOP=AxiomSoc
SEEDS=4
FREQ=100
DEVICE=45k
EXTRA=()

usage() {
  cat <<'USAGE'
usage: synth/sweep.sh [options] [-- extra options for synth.sh]

  --top NAME     top module                     (default AxiomSoc)
  --seeds N      how many placement seeds       (default 4)
  --freq MHZ     timing target                  (default 100)
  --device D     25k | 45k | 85k                (default 45k)
  -h, --help     this message

Anything after -- is passed through to synth/synth.sh, so for example
  synth/sweep.sh --seeds 6 -- --abc9 --nodsp
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --top) TOP="$2"; shift 2 ;;
    --seeds) SEEDS="$2"; shift 2 ;;
    --freq) FREQ="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

DIRS=()
for ((seed = 1; seed <= SEEDS; seed++)); do
  OUT="synth/out/${TOP}-seed${seed}"
  echo "== seed ${seed} of ${SEEDS}"
  ./synth/synth.sh --top "$TOP" --device "$DEVICE" --freq "$FREQ" \
    --seed "$seed" --outdir "$OUT" "${EXTRA[@]}" > "${OUT}.log" 2>&1 || true
  DIRS+=("$OUT")
done

echo
echo "== aggregate over ${SEEDS} seeds"
python3 synth/report.py "${DIRS[@]}"
