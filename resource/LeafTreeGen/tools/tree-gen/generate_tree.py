#!/usr/bin/env python3
"""CLI entry point. Reads a JSON tree config list and writes .iob, .schem, and/or vanilla structure .nbt files."""

import argparse
import json
import math
import os
import random
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

from nbt import write_schematic, write_iob, write_structure_nbt
from trunk import generate_trunk_with_offsets, set_round_trunk
from canopy import generate_canopy
from decorators import apply_decorators
from roots import build_roots


def _sanitize(s: str) -> str:
    """Turn a block id or name into a safe filename fragment (drops the namespace, keeps alnum/underscore)."""
    s = s.split(":")[-1]
    return "".join(c if c.isalnum() or c == "_" else "_" for c in s)


def output_filename(entry: dict, height: int, ext: str = "iob", index: int = 0) -> str:
    """Returns filename for a generated tree file.

    If the entry contains a ``filenames`` list, the element at ``index`` is used
    (with the extension appended).  Otherwise the legacy auto-generated name is
    used: ``<name>_<profile>_<trunk>_<leaves>_h<height>_s<seed>.<ext>``.
    """
    filenames = entry.get("filenames")
    if filenames and index < len(filenames):
        base = _sanitize(filenames[index])
        return "%s.%s" % (base, ext)
    name = _sanitize(entry.get("name", ""))
    profile = _sanitize(entry.get("profile", "oak"))
    trunk = _sanitize(entry.get("trunk", "oak_log"))
    leaves = _sanitize(entry.get("leaves", "oak_leaves"))
    seed = entry.get("seed", 0)
    prefix = "%s_" % name if name else ""
    return "%s%s_%s_%s_h%d_s%d.%s" % (prefix, profile, trunk, leaves, height, seed, ext)


def _heights_for_entry(entry: dict, count_override: int | None) -> list:
    """Pick the trunk height for each variant of an entry, spread evenly between height_min and height_max."""
    h_min = int(entry.get("height_min", 8))
    h_max = int(entry.get("height_max", 12))
    count = count_override if count_override is not None else int(entry.get("count", 1))
    seed = int(entry.get("seed", 0))

    if count == 1:
        rng = random.Random(seed)
        return [rng.randint(h_min, h_max)]

    if h_min == h_max:
        return [h_min] * count

    rng = random.Random(seed)
    step = (h_max - h_min) / max(count - 1, 1)
    heights = []
    for i in range(count):
        base = h_min + step * i
        jitter = rng.uniform(-step * 0.3, step * 0.3)
        h = int(round(max(h_min, min(h_max, base + jitter))))
        heights.append(h)
    return heights


def generate_tree(entry: dict, height: int) -> dict:
    """All blocks for one tree; returns {(x,y,z): blockstate}."""
    trunk_block = entry.get("trunk", "minecraft:oak_log[axis=y]")
    leaf_block = entry.get("leaves", "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")
    profile = entry.get("profile", "oak")
    seed = int(entry.get("seed", 0))
    trunk_width = int(entry.get("trunk_width", 1))
    trunk_shape = entry.get("trunk_shape", "constant")
    trunk_shape_params = entry.get("trunk_shape_params", {})
    lean_azimuth = float(entry.get("lean_azimuth", 0.0))
    lean_angle = float(entry.get("lean_angle", 0.0))
    curve_fn = entry.get("trunk_curve_fn", "linear")
    curve_params = entry.get("trunk_curve_params", {})
    azimuth_fn = entry.get("lean_azimuth_fn", "constant")
    azimuth_params = entry.get("lean_azimuth_params", {})
    canopy_cfg = entry.get("canopy", {})
    decorator_cfgs = entry.get("decorators", None)
    secondary_leaves_raw = entry.get("secondary_leaves", None)
    secondary_fraction = float(entry.get("secondary_leaf_fraction", 0.35))
    secondary_trunk_raw = entry.get("secondary_trunk", None)
    secondary_trunk_start = float(entry.get("secondary_trunk_start", 0.5))
    secondary_trunk_end = float(entry.get("secondary_trunk_end", 1.0))

    if ":" in leaf_block and "[" not in leaf_block and "leaves" in leaf_block:
        leaf_block = leaf_block + "[distance=1,persistent=true,waterlogged=false]"

    # Normalise secondary_trunk block
    secondary_trunk = None
    if isinstance(secondary_trunk_raw, str) and secondary_trunk_raw:
        st = secondary_trunk_raw
        if "[" not in st:
            st = st + "[axis=y]"
        secondary_trunk = st

    # Normalise secondary_leaves: string stays as-is; list entries get their block
    # field expanded with leaf blockstate if missing.
    secondary_leaves = None
    if isinstance(secondary_leaves_raw, str):
        sl = secondary_leaves_raw
        if ":" in sl and "[" not in sl and "leaves" in sl:
            sl = sl + "[distance=1,persistent=true,waterlogged=false]"
        secondary_leaves = sl
    elif isinstance(secondary_leaves_raw, list):
        expanded = []
        for entry_sl in secondary_leaves_raw:
            blk = entry_sl.get("block", "")
            if ":" in blk and "[" not in blk and "leaves" in blk:
                blk = blk + "[distance=1,persistent=true,waterlogged=false]"
            expanded.append({"block": blk, "weight": entry_sl.get("weight", 1)})
        secondary_leaves = expanded if expanded else None

    set_round_trunk(bool(entry.get("trunk_round", False)), seed)
    blocks, trunk_offsets = generate_trunk_with_offsets(
        height, trunk_block, trunk_width, trunk_shape, trunk_shape_params,
        lean_azimuth, lean_angle, curve_fn, curve_params,
        azimuth_fn, azimuth_params,
        secondary_trunk=secondary_trunk,
        secondary_trunk_start=secondary_trunk_start,
        secondary_trunk_end=secondary_trunk_end
    )
    trunk_positions = set(blocks.keys())
    branch_endpoints = [] if decorator_cfgs else None
    canopy_blocks = generate_canopy(height, trunk_block, leaf_block, profile, canopy_cfg, seed, blocks,
                                    trunk_offsets=trunk_offsets,
                                    secondary_leaves=secondary_leaves,
                                    secondary_fraction=secondary_fraction,
                                    collect_endpoints=branch_endpoints)
    blocks.update(canopy_blocks)
    if decorator_cfgs:
        dec_blocks = apply_decorators(blocks, decorator_cfgs, seed,
                                      branch_endpoints=branch_endpoints,
                                      trunk_positions=trunk_positions)
        blocks.update(dec_blocks)

    # Root system: extend the trunk base downward so the tree always connects to
    # the ground (Iris anchors objects by center; this bridges any residual gap).
    if entry.get("roots", True):
        base_cells = set((x, z) for (x, y, z) in trunk_positions if y == 0)
        if base_cells:
            root_block = entry.get("root_block", None)
            for pos, blk in build_roots(base_cells, height, trunk_block, seed,
                                        root_block=root_block).items():
                if pos not in blocks:
                    blocks[pos] = blk

    # Encase: wrap every exposed face of a target material (default lava) in a sheath
    # block (e.g. obsidian). This fully seals the trunk AND branches in obsidian so the
    # lava is contained and the trunk reads as a rounded obsidian-skinned column - which
    # the canopy decorators alone could not do (they only skin the canopy, not the trunk).
    encase_block = entry.get("encase", None)
    if encase_block:
        encase_targets = entry.get("encase_targets", ["minecraft:lava"])
        target_bases = set(t.split("[")[0] for t in encase_targets)
        existing = set(blocks.keys())
        shell = {}
        for (x, y, z), blk in blocks.items():
            if blk.split("[")[0] not in target_bases:
                continue
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0),
                               (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                npos = (x + dx, y + dy, z + dz)
                if npos not in existing and npos not in shell:
                    shell[npos] = encase_block
        blocks.update(shell)

        # Optional upward vertical variation of the obsidian sheath: stack a random
        # number (0..encase_top_variation) of extra sheath blocks above each column's
        # top so the crust is bumpy rather than a flat skin.
        variation = int(entry.get("encase_top_variation", 0))
        if variation > 0:
            vrng = random.Random((seed ^ 0x2A2A) & 0xFFFFFFFF)
            col_top = {}
            ebase = encase_block.split("[")[0]
            for (x, y, z), blk in blocks.items():
                if blk.split("[")[0] == ebase:
                    if (x, z) not in col_top or y > col_top[(x, z)]:
                        col_top[(x, z)] = y
            for (x, z), ty in col_top.items():
                for i in range(1, vrng.randint(0, variation) + 1):
                    npos = (x, ty + i, z)
                    if npos not in blocks:
                        blocks[npos] = encase_block

    # Invert the whole model vertically. Used for the "lava vein" objects: a normal
    # upward tree becomes an inverted trunk that drives DOWN from y=0 with branches
    # (lava) veining downward, the obsidian sheath sealing it below ground.
    if entry.get("invert_y", False):
        blocks = {(x, -y, z): blk for (x, y, z), blk in blocks.items()}

    # Open top face: expose lava ONLY on the single highest Y plane of the whole model
    # (the flat trunk mouth), not per-column. This guarantees one FLAT, level lava pool
    # at the top; everything below (branches, veins) stays fully obsidian-encased so no
    # lava is exposed at varying/lower heights.
    if encase_block and entry.get("encase_open_top", False):
        lava_ys = [y for (x, y, z), blk in blocks.items()
                   if blk.split("[")[0] in target_bases]
        if lava_ys:
            top_y = max(lava_ys)
            ebase = encase_block.split("[")[0]
            for (x, y, z), blk in list(blocks.items()):
                if y == top_y and blk.split("[")[0] in target_bases:
                    above = (x, top_y + 1, z)
                    if blocks.get(above, "").split("[")[0] == ebase:
                        del blocks[above]

    # Clear cone: bake a widening cone of void_air ABOVE the top lava pool so the
    # airspace over the pool is guaranteed clear (void_air actually removes terrain,
    # unlike plain air which Iris skips). Radius expands with height for a conic shape.
    cone_h = int(entry.get("clear_cone_height", 0))
    if cone_h > 0:
        lava_cells = [(x, y, z) for (x, y, z), blk in blocks.items()
                      if blk.split("[")[0] == "minecraft:lava"]
        if lava_cells:
            top_y = max(y for (x, y, z) in lava_cells)
            mouth = [(x, z) for (x, y, z) in lava_cells if y == top_y]
            mcx = sum(p[0] for p in mouth) / len(mouth)
            mcz = sum(p[1] for p in mouth) / len(mouth)
            base_r = max(math.hypot(px - mcx, pz - mcz) for (px, pz) in mouth) + 1.0
            expand = float(entry.get("clear_cone_expand", 1.6))
            icx, icz = int(round(mcx)), int(round(mcz))
            for h in range(1, cone_h + 1):
                r = base_r * (1.0 + (expand - 1.0) * (h / cone_h))
                ri = int(math.ceil(r))
                for dx in range(-ri, ri + 1):
                    for dz in range(-ri, ri + 1):
                        if math.hypot(dx, dz) <= r:
                            pos = (icx + dx, top_y + h, icz + dz)
                            if pos not in blocks:
                                blocks[pos] = "minecraft:void_air"

    return blocks


def parse_args():
    """Define and parse the command-line flags (--config, --out, --format, --count)."""
    parser = argparse.ArgumentParser(
        description="Generate Sponge Schematic v3 tree files for Iris."
    )
    parser.add_argument(
        "--config", required=True,
        help="Path to JSON config file containing a flat list of tree definitions."
    )
    parser.add_argument(
        "--out", default=None,
        help="Output directory for output files (overrides per-entry 'out' field). "
             "Defaults to an 'output/' folder next to the config file."
    )
    parser.add_argument(
        "--format", default="iob", choices=["iob", "schem", "nbt", "both", "all"],
        help="Output format: iob (Iris V2 IOB, default), schem (Sponge Schematic v3), "
             "nbt (vanilla structure template), both (iob+schem), or all (iob+schem+nbt)."
    )
    parser.add_argument(
        "--count", type=int, default=None,
        help="Number of schematics to generate per entry (overrides per-entry 'count' field)."
    )
    parser.add_argument(
        "--data-version", type=int, default=None,
        help="DataVersion stamped into vanilla structure .nbt output "
             "(e.g. 4325 for Minecraft 1.21.5). Defaults to the nbt module's DATA_VERSION."
    )
    return parser.parse_args()


def resolve_out_dir(cli_out: str | None, config_path: str) -> str:
    """Choose the output directory: the --out value if given, else an 'output/' folder next to the config."""
    if cli_out:
        return os.path.abspath(cli_out)
    config_dir = os.path.dirname(os.path.abspath(config_path))
    return os.path.join(config_dir, "output")


def main():
    """Read the config, generate every tree variant, and write the requested object files."""
    args = parse_args()

    with open(args.config, "r", encoding="utf-8") as f:
        entries = json.load(f)

    if not isinstance(entries, list):
        print("ERROR: config file must contain a JSON array of tree definitions.", file=sys.stderr)
        sys.exit(1)

    out_dir = resolve_out_dir(args.out, args.config)
    os.makedirs(out_dir, exist_ok=True)

    fmt = args.format
    total = 0
    grand_total = sum(len(_heights_for_entry(e, args.count)) for e in entries)
    for i, entry in enumerate(entries):
        heights = _heights_for_entry(entry, args.count)
        for idx, height in enumerate(heights):
            blocks = generate_tree(entry, height)
            xs = [p[0] for p in blocks]
            ys = [p[1] for p in blocks]
            zs = [p[2] for p in blocks]
            W = max(xs) - min(xs) + 1
            H = max(ys) - min(ys) + 1
            L = max(zs) - min(zs) + 1

            written = []
            if fmt in ("iob", "both", "all"):
                fname = output_filename(entry, height, "iob", index=idx)
                write_iob(os.path.join(out_dir, fname), blocks)
                written.append(fname)
            if fmt in ("schem", "both", "all"):
                fname = output_filename(entry, height, "schem", index=idx)
                write_schematic(
                    os.path.join(out_dir, fname),
                    blocks,
                    name=fname.replace(".schem", ""),
                    author="tree-gen",
                )
                written.append(fname)
            if fmt in ("nbt", "all"):
                fname = output_filename(entry, height, "nbt", index=idx)
                if args.data_version is not None:
                    write_structure_nbt(os.path.join(out_dir, fname), blocks,
                                        data_version=args.data_version)
                else:
                    write_structure_nbt(os.path.join(out_dir, fname), blocks)
                written.append(fname)

            print("  [%d/%d] %s  W=%d H=%d L=%d  blocks=%d" % (
                total + 1, grand_total,
                written[0], W, H, L, len(blocks)
            ))
            total += 1

    print("Done. %d file(s) written to %s" % (total, out_dir))


if __name__ == "__main__":
    main()
