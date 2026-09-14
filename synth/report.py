#!/usr/bin/env python3
"""Report primitive utilisation, achieved frequency and the slowest paths from
a nextpnr-ecp5 run.

Two things here are not just pretty-printing.

The first is plugin attribution. SpinalHDL flattens the plugin hierarchy into
one Verilog module, but it keeps the plugin name as a prefix on every signal,
so each segment of a critical path can be charged back to the plugin that
created it. That turns "the critical path is 11 ns" into "6 ns of it is in the
register file read and the forwarding muxes", which is the form you can act on.

The second is that a single run does not give you a Pareto. nextpnr reports one
critical path per clock domain, and which path that is moves around with the
placement seed. Aggregating several seeds separates the paths that are
genuinely too long, which show up every time, from the ones that were unlucky
in routing, which show up once. Use synth/sweep.sh to produce the inputs.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict

PLUGIN = re.compile(r"([A-Za-z0-9]+Plugin)")
NUMBERS = re.compile(r"^\s*([0-9.]+)\s+([0-9.]+)\s+(.*)$")


def load_report(path):
    if not os.path.isfile(path):
        return None
    try:
        with open(path) as handle:
            return json.load(handle)
    except json.JSONDecodeError:
        return None


def owner(name: str) -> str:
    """Charge a signal back to the plugin that created it.

    The last plugin name in the string wins, not the first. A payload sitting on
    a pipeline node is named after the node and then after the plugin that
    declared the payload, so taking the first match would charge every payload
    in the core to the pipeline.
    """
    matches = PLUGIN.findall(name)
    if matches:
        return matches[-1]
    if name.startswith("PipelinePlugin") or "_ctrl_" in name:
        return "PipelinePlugin"
    return "unattributed"


def area_by_owner(path, top):
    """Charge every synthesised primitive back to the plugin that created it.

    Yosys keeps the original signal name as a prefix on the cells it builds, and
    SpinalHDL puts the plugin name at the front of every signal, so a flat
    netlist can still be read hierarchically. Cells sitting on a pipeline node
    are split further by the payload they carry, because "PipelinePlugin" on its
    own would hide the fact that the cost is a register file read mux.
    """
    if not os.path.isfile(path):
        return None
    with open(path) as handle:
        design = json.load(handle)
    module = design.get("modules", {}).get(top)
    if module is None:
        modules = design.get("modules", {})
        if not modules:
            return None
        module = next(iter(modules.values()))

    payload = re.compile(r"_(Global_[A-Z_0-9]+?)_(?:LUT4|PFUMX|L6MUX21|TRELLIS|CCU2C|MULT|DP16KD)")
    totals = defaultdict(lambda: defaultdict(int))
    for name, cell in module.get("cells", {}).items():
        kind = cell.get("type", "?").lstrip("$")
        who = owner(name)
        if who == "PipelinePlugin":
            match = payload.search(name)
            if match:
                who = f"PipelinePlugin/{match.group(1)}"
        totals[who][kind] += 1
    return totals


def print_area(totals, top_n):
    if not totals:
        print("  no netlist to attribute")
        return
    kinds = sorted({k for row in totals.values() for k in row})
    order = sorted(totals.items(), key=lambda kv: -kv[1].get("LUT4", 0))
    width = max(len(k) for k in totals)
    header = "  " + "owner".ljust(width) + "".join(f"{k:>12}" for k in kinds)
    print(header)
    print("  " + "-" * (len(header) - 2))
    shown = order[:top_n]
    for who, row in shown:
        print("  " + who.ljust(width) + "".join(f"{row.get(k, 0):>12}" for k in kinds))
    if len(order) > top_n:
        rest = defaultdict(int)
        for _, row in order[top_n:]:
            for k, v in row.items():
                rest[k] += v
        print("  " + f"(+{len(order) - top_n} more)".ljust(width)
              + "".join(f"{rest.get(k, 0):>12}" for k in kinds))
    grand = defaultdict(int)
    for _, row in order:
        for k, v in row.items():
            grand[k] += v
    print("  " + "-" * (len(header) - 2))
    print("  " + "total".ljust(width) + "".join(f"{grand.get(k, 0):>12}" for k in kinds))


def parse_log(path):
    """Pull the critical path breakdown and the achieved frequency out of the
    nextpnr log. The JSON report carries utilisation and fmax but not the path
    detail, which only appears in the text."""
    if not os.path.isfile(path):
        return {}, []

    frequencies = {}
    paths = []
    current = None

    with open(path, errors="replace") as handle:
        for raw in handle:
            line = raw.replace("Info: ", "", 1).rstrip()

            match = re.search(r"Max frequency for clock\s+'([^']+)':\s+([0-9.]+)\s+MHz", line)
            if match:
                frequencies[match.group(1)] = float(match.group(2))
                continue

            match = re.search(r"Critical path report for clock '([^']+)'", line)
            if match:
                current = {"clock": match.group(1), "segments": [], "logic": 0.0, "routing": 0.0}
                paths.append(current)
                continue

            if current is None:
                continue

            match = re.search(r"([0-9.]+)\s+ns logic,\s+([0-9.]+)\s+ns routing", line)
            if match:
                current["logic"] = float(match.group(1))
                current["routing"] = float(match.group(2))
                current = None
                continue

            fields = NUMBERS.match(line)
            if fields:
                delay = float(fields.group(1))
                total = float(fields.group(2))
                what = fields.group(3).strip()
                kind = "net" if what.startswith("Net ") else "cell"
                name = what.split()[1] if len(what.split()) > 1 else what
                current["segments"].append(
                    {"delay": delay, "total": total, "kind": kind, "name": name})

    return frequencies, paths


UTIL = re.compile(r"^\s*([A-Za-z_0-9]+):\s*(\d+)\s*/\s*(\d+)\s")


def parse_utilisation(log_path, report):
    """Placed utilisation.

    Preferred from the JSON report, but nextpnr-ecp5 does not always write one,
    and the log carries the same table in a form that is easy to read.
    """
    rows = []
    utilisation = (report or {}).get("utilization", {})
    for resource, counts in sorted(utilisation.items()):
        used = counts.get("used", 0)
        available = counts.get("available", 0)
        if used or available:
            rows.append((resource, used, available))
    if rows:
        return rows

    if not os.path.isfile(log_path):
        return rows
    inside = False
    with open(log_path, errors="replace") as handle:
        for raw in handle:
            line = raw.replace("Info: ", "", 1).rstrip()
            if line.strip().startswith("Device utilisation"):
                inside = True
                continue
            if inside:
                match = UTIL.match(line.replace("\t", " "))
                if match:
                    rows.append((match.group(1), int(match.group(2)), int(match.group(3))))
                elif rows:
                    break
    return [row for row in rows if row[1] > 0]


def print_utilisation(rows):
    rows = [(name, used, available, (100.0 * used / available) if available else 0.0)
            for name, used, available in rows]
    if not rows:
        print("  no utilisation found")
        return
    width = max(len(row[0]) for row in rows)
    for resource, used, available, percent in rows:
        bar = "#" * int(percent / 4)
        print(f"  {resource:<{width}}  {used:>7} / {available:<7}  {percent:5.1f}%  {bar}")


def print_paths(paths, top, group):
    for path in paths:
        total = path["logic"] + path["routing"]
        if total <= 0 and path["segments"]:
            total = path["segments"][-1]["total"]
        share = (100.0 * path["routing"] / total) if total else 0.0
        print(f"\n  clock {path['clock']}: {total:.3f} ns "
              f"({path['logic']:.3f} logic, {path['routing']:.3f} routing, "
              f"{share:.0f}% routing)")

        if group:
            charged = defaultdict(float)
            for segment in path["segments"]:
                charged[owner(segment["name"])] += segment["delay"]
            print("\n  where the time goes:")
            width = max((len(k) for k in charged), default=10)
            for plugin, delay in sorted(charged.items(), key=lambda kv: -kv[1]):
                percent = 100.0 * delay / total if total else 0.0
                print(f"    {plugin:<{width}}  {delay:7.3f} ns  {percent:5.1f}%  "
                      + "#" * int(percent / 3))

        ordered = sorted(path["segments"], key=lambda s: -s["delay"])[:top]
        if ordered:
            print(f"\n  slowest {len(ordered)} segments:")
            for segment in ordered:
                print(f"    {segment['delay']:7.3f} ns  {segment['kind']:<4}  {segment['name'][:88]}")


def aggregate(directories, top):
    """Combine several seeds into a Pareto.

    A path that is critical under every seed is a real problem in the logic. A
    path that is critical under one seed lost a routing lottery, and rerunning
    is cheaper than restructuring."""
    seen = defaultdict(lambda: {"runs": 0, "worst": 0.0, "best": 1e9, "logic": 0.0})
    frequencies = []

    for directory in directories:
        freqs, paths = parse_log(os.path.join(directory, "nextpnr.log"))
        frequencies.extend(freqs.values())
        for path in paths:
            total = path["logic"] + path["routing"]
            endpoints = (path["segments"][0]["name"] if path["segments"] else "?",
                         path["segments"][-1]["name"] if path["segments"] else "?")
            key = f"{owner(endpoints[0])} -> {owner(endpoints[1])}"
            entry = seen[key]
            entry["runs"] += 1
            entry["worst"] = max(entry["worst"], total)
            entry["best"] = min(entry["best"], total)
            entry["logic"] = max(entry["logic"], path["logic"])

    if frequencies:
        print(f"\nfmax over {len(frequencies)} runs: "
              f"worst {min(frequencies):.2f} MHz, best {max(frequencies):.2f} MHz, "
              f"median {sorted(frequencies)[len(frequencies) // 2]:.2f} MHz")

    if not seen:
        print("no critical paths found; were the runs completed?")
        return

    print("\nPareto of critical paths, worst first:")
    print(f"  {'runs':>4}  {'worst ns':>9}  {'best ns':>8}  {'logic ns':>9}  path")
    for key, entry in sorted(seen.items(), key=lambda kv: -kv[1]["worst"])[:top]:
        print(f"  {entry['runs']:>4}  {entry['worst']:>9.3f}  {entry['best']:>8.3f}  "
              f"{entry['logic']:>9.3f}  {key}")
    print("\n  A path critical in every run is logic to restructure.")
    print("  A path critical in one run lost a routing lottery; reseed instead.")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directories", nargs="+", help="nextpnr output directories")
    parser.add_argument("--top", type=int, default=8, help="how many entries to show")
    parser.add_argument("--no-group", action="store_true", help="skip plugin attribution")
    args = parser.parse_args()

    if len(args.directories) == 1:
        directory = args.directories[0]
        report = load_report(os.path.join(directory, "report.json"))
        frequencies, paths = parse_log(os.path.join(directory, "nextpnr.log"))

        print(f"\n=== {directory} ===")

        netlist = None
        for candidate in os.listdir(directory):
            if candidate.endswith(".json") and candidate != "report.json":
                netlist = os.path.join(directory, candidate)
                break
        if netlist:
            top = os.path.basename(netlist)[:-5]
            print(f"\nprimitives by owner ({top}):")
            print_area(area_by_owner(netlist, top), args.top)

        print("\nplaced utilisation (non-zero only):")
        print_utilisation(parse_utilisation(os.path.join(directory, "nextpnr.log"), report))

        fmax = (report or {}).get("fmax", {})
        if fmax or frequencies:
            print("\nachieved frequency:")
            for clock, values in fmax.items():
                achieved = values.get("achieved", 0.0)
                constraint = values.get("constraint", 0.0)
                verdict = "meets" if achieved >= constraint else "MISSES"
                print(f"  {clock}: {achieved:.2f} MHz, target {constraint:.2f} MHz, {verdict}")
            for clock, achieved in frequencies.items():
                if clock not in fmax:
                    print(f"  {clock}: {achieved:.2f} MHz")

        if paths:
            print("\ncritical path:")
            print_paths(paths, args.top, not args.no_group)
        else:
            print("\nno critical path in the log")
        print()
    else:
        aggregate(args.directories, args.top)


if __name__ == "__main__":
    sys.exit(main())
