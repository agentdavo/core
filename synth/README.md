# ECP5 backend

Synthesis, place and route, and reporting for the Lattice ECP5, using yosys and
nextpnr. The point is not to produce a bitstream; it is to answer two questions
with numbers instead of opinion:

- where is the area going
- what is the critical path, and is it logic or routing

Both feed straight back into the RTL, which is why the reports attribute
everything to the plugin that created it.

## Usage

```sh
sbt "runMain axiom.GenerateAxiomSoc"     # produce generated/AxiomSoc.v first

synth/synth.sh                            # out-of-context build, 45k, 100 MHz
synth/synth.sh --top CoreSoc --device 25k
synth/synth.sh --synth-only               # area only, skips place and route
synth/sweep.sh --seeds 6                  # Pareto of critical paths
python3 synth/report.py synth/out/AxiomSoc
```

`synth/synth.sh --help` lists every option.

## Out of context by default

Nothing is bound to package pins unless you ask. Yosys only inserts IO buffers
with `-iopad`, and nextpnr is given `--out-of-context`, which disables both IO
buffer insertion and global promotion.

That is the right default when the question is how big and how fast the logic
is. Binding to pins adds IO delay that has nothing to do with the design, and
forces a pinout you do not have yet. Pass `--with-pins` together with `--lpf`
when you are actually building a bitstream for a board.

## What the reports tell you

**Primitives by owner.** Yosys keeps the original signal name as a prefix on
the cells it builds, and SpinalHDL puts the plugin name at the front of every
signal, so a flat netlist can still be read hierarchically. Cells sitting on a
pipeline node are split further by the payload they carry, because
`PipelinePlugin` on its own would hide the fact that the cost is a register
file read multiplexer.

**Critical path, split into logic and routing.** If routing dominates, the
design is congested and the answer is placement, or fewer wires. If logic
dominates, the answer is pipeline balancing, and the per-plugin attribution
says which stage to cut.

**A Pareto across seeds.** See the comment at the top of `sweep.sh`.

## Reading the result

A first pass over Axiom-64 said the following, and it is a fair example of what
this flow is for:

| Owner | LUT4 | Share |
| --- | --- | --- |
| Register file read and forwarding | ~13,500 | 40% |
| Load/store unit | 6,291 | 18% |
| ALU | 4,720 | 14% |
| Everything else | ~9,600 | 28% |

Forty per cent of the core in one register file read path is not a tuning
problem, it is a structural one: thirty-two 64-bit registers held in flip-flops
with three asynchronous read ports become three 32-to-1 multiplexers 64 bits
wide. Distributed RAM does the same job in a fraction of the area. That is the
kind of conclusion this flow exists to produce.
