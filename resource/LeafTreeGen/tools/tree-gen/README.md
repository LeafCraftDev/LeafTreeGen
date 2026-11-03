# tree-gen

A self-contained, offline tree generator. It reads data-driven JSON tree
definitions and writes Minecraft object files you can drop into an Iris
world-gen pack (`.iob`), open in a schematic editor / WorldEdit (`.schem`), or
place as a vanilla structure template (`.nbt`) via `/place template`, structure
blocks, or jigsaw `template_pool` pieces.

Pure Python 3 standard library: no external packages, no PowerShell, and no
Minecraft server required.

## Layout

Everything needed to generate trees lives in this directory:

```
tree-gen/
  generate_tree.py   CLI entry point (reads a config, writes objects)
  trunk.py           trunk column: width shaping, lean/curve, bark faces
  canopy.py          canopy: species presets, volume layers, branch system
  decorators.py      accent blocks (vines, fruit, snow, ...)
  roots.py           downward taproot + buttress legs
  nbt.py             .iob (Iris V2 IOB), .schem (Sponge v3), and .nbt (vanilla structure) writers
  configs/           tree definition JSON files (edit / add your own here)
```

## Requirements

- Python 3.10+ (uses the `X | None` type-hint syntax). No `pip install` needed.

## Generate trees

Run from this directory (or pass full paths from anywhere):

```
python generate_tree.py --config configs/embervine.json
```

By default the output is written next to the config, in `configs/output/`.

### Options

| Flag | Default | Description |
|---|---|---|
| `--config <path>` | required | JSON file containing a flat array of tree definitions |
| `--out <dir>` | `<config-dir>/output` | Output directory (overrides per-entry defaults) |
| `--format iob\|schem\|nbt\|both\|all` | `iob` | `.iob` for Iris, `.schem` for editors, `.nbt` for vanilla structures, `both` (iob+schem), or `all` (iob+schem+nbt) |
| `--count <N>` | per-entry `count` | Override the number of variants generated per entry |

Example writing schematics to a custom folder:

```
python generate_tree.py --config configs/embervine.json --format schem --out out/embervine
```

Each generated file prints its bounding box and block count:

```
  [1/3] embervine_small_1.iob  W=11 H=18 L=11  blocks=412
```

Generation is deterministic: the same config + `seed` always produces identical
output.

## Authoring a config

A config is a JSON array; each element is one tree definition (trunk/leaf
blocks, sizing, trunk shaping, canopy, decorators, roots). Copy an existing file
in `configs/` and adjust it, or read the full field-by-field schema in the
project guide:

- `docs/usage/TREE-GENERATION.md` - full config schema, worked example, and a
  description of the `.iob` / `.schem` output formats.

## Converting to Iris 4.0 procedural trees

Iris 4.0 can generate trees at world-gen time from a JSON definition
(`IrisProceduralTree`) instead of a pre-baked `.iob`. `to_procedural.py` converts
the same configs into that format:

```
python to_procedural.py --config configs/embervine.json
# -> configs/embervine.procedural.json   (or pass --out <file>)
```

The conversion recases keys to camelCase, flattens our nested `*_params` objects
into Iris' discrete fields, splits `branches.azimuth` into `azimuthMode`+`azimuth`,
and uppercases enum values. `.iob`-only post-processing fields (encase, invert_y,
clear_cone, ...) have no procedural equivalent and are reported as skipped.

The full field-by-field mapping is in
`docs/scratch/IRIS4-TREE-SCHEMA-COMPARISON.md`.

## Using the output in the pack

The generated `.iob` files are plain object files. To make an Iris biome place
them, reference them from a biome's `objects` block by their path-relative key
(e.g. `trees/embervine/embervine_small_1`) after staging them into the pack. The
pack staging/deploy step lives outside this directory; see the project docs for
the end-to-end Iris world-building workflow.
