"""Decorator block placement: fences, vines, snow, saplings, and other accents."""

import random


def _facing_away(cx: float, cz: float, x: int, z: int) -> str:
    """Cardinal direction facing away from trunk center (for fence/gate orientation)."""
    dx = x - cx
    dz = z - cz
    if abs(dx) >= abs(dz):
        return "east" if dx >= 0 else "west"
    return "south" if dz >= 0 else "north"


def _expand_block(block: str, facing: str = None) -> str:
    """Append blockstate if not already present. Injects facing when axis_aware."""
    if "[" in block:
        return block
    if facing:
        return "%s[facing=%s]" % (block, facing)
    return block


def _apply_branch_tip(blocks: dict, existing: dict, decorator: dict,
                      branch_endpoints: list, rng: random.Random) -> dict:
    """Place decorator at each branch endpoint with given chance."""
    result = {}
    chance = float(decorator.get("chance", 0.5))
    block_base = decorator.get("block", "")
    axis_aware = bool(decorator.get("axis_aware", False))

    for (ex, ey, ez, ox, oz) in branch_endpoints:
        if rng.random() > chance:
            continue
        pos = (ex, ey, ez)
        if pos in existing or pos in blocks:
            continue
        facing = _facing_away(ox, oz, ex, ez) if axis_aware else None
        result[pos] = _expand_block(block_base, facing)
    return result


def _apply_trunk_surface(blocks: dict, existing: dict, decorator: dict,
                         trunk_positions: set, rng: random.Random) -> dict:
    """Place decorator on air-facing sides of trunk blocks (vine, moss)."""
    result = {}
    chance = float(decorator.get("chance", 0.3))
    block_base = decorator.get("block", "")
    all_pos = set(existing.keys()) | set(blocks.keys())

    for (x, y, z) in trunk_positions:
        for dx, dz, facing in ((1, 0, "west"), (-1, 0, "east"), (0, 1, "north"), (0, -1, "south")):
            neighbor = (x + dx, y, z + dz)
            if neighbor in all_pos:
                continue
            if rng.random() > chance:
                continue
            if neighbor in result:
                continue
            result[neighbor] = _expand_block(block_base, facing)
    return result


def _apply_canopy_top(blocks: dict, existing: dict, decorator: dict,
                      rng: random.Random) -> dict:
    """Place decorator on top of the highest leaf/block in each (x,z) column."""
    result = {}
    chance = float(decorator.get("chance", 0.5))
    block_base = decorator.get("block", "")
    all_pos = set(existing.keys()) | set(blocks.keys())

    # Find topmost occupied y per (x,z)
    col_top = {}
    for (x, y, z) in all_pos:
        key = (x, z)
        if key not in col_top or y > col_top[key]:
            col_top[key] = y

    for (x, z), top_y in col_top.items():
        above = (x, top_y + 1, z)
        if above in all_pos:
            continue
        if rng.random() > chance:
            continue
        result[above] = _expand_block(block_base)
    return result


def _apply_canopy_bottom(blocks: dict, existing: dict, decorator: dict,
                         trunk_positions: set, rng: random.Random) -> dict:
    """Place decorator one block below the lowest canopy (non-trunk) block in each
    (x,z) column, when that space is air. Forms a single glowing underside layer
    on the cap that is viewable from below, leaving the cap itself solid."""
    result = {}
    chance = float(decorator.get("chance", 0.85))
    block_base = decorator.get("block", "")
    trunk = trunk_positions or set()
    all_pos = set(existing.keys()) | set(blocks.keys())

    # Lowest canopy (leaf) block per (x,z), ignoring trunk columns/blocks.
    col_bottom = {}
    for (x, y, z) in existing.keys():
        if (x, y, z) in trunk:
            continue
        key = (x, z)
        if key not in col_bottom or y < col_bottom[key]:
            col_bottom[key] = y

    for (x, z), bot_y in col_bottom.items():
        below = (x, bot_y - 1, z)
        if below in all_pos:
            continue
        if rng.random() > chance:
            continue
        result[below] = _expand_block(block_base)
    return result


def _apply_trunk_base(blocks: dict, existing: dict, decorator: dict,
                      trunk_positions: set, rng: random.Random) -> dict:
    """Place decorator around the base of the trunk (y=0 ring)."""
    result = {}
    chance = float(decorator.get("chance", 0.3))
    block_base = decorator.get("block", "")
    all_pos = set(existing.keys()) | set(blocks.keys())

    base_xz = set((x, z) for (x, y, z) in trunk_positions if y == 0)
    for (x, z) in base_xz:
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            pos = (x + dx, 0, z + dz)
            if pos in all_pos or pos in result:
                continue
            if rng.random() > chance:
                continue
            result[pos] = _expand_block(block_base)
    return result


def apply_decorators(all_blocks: dict, decorator_cfgs: list, seed: int,
                     branch_endpoints: list = None,
                     trunk_positions: set = None) -> dict:
    """
    Apply all decorator configs to the assembled tree.

    Parameters
    ----------
    all_blocks      : existing trunk + canopy blocks (read-only reference)
    decorator_cfgs  : list of decorator dicts from the tree config
    seed            : rng seed
    branch_endpoints: list of (ex, ey, ez, origin_x, origin_z) tuples from branch system
    trunk_positions : set of (x, y, z) trunk block positions

    Returns
    -------
    dict of new decorator blocks (does not include all_blocks)
    """
    if not decorator_cfgs:
        return {}

    rng = random.Random(seed + 77777)
    result = {}

    for dec in decorator_cfgs:
        target = dec.get("target", "branch_tip")
        if target == "branch_tip":
            if branch_endpoints:
                new = _apply_branch_tip(result, all_blocks, dec, branch_endpoints, rng)
                result.update(new)
        elif target == "trunk_surface":
            if trunk_positions:
                new = _apply_trunk_surface(result, all_blocks, dec, trunk_positions, rng)
                result.update(new)
        elif target == "canopy_top":
            new = _apply_canopy_top(result, all_blocks, dec, rng)
            result.update(new)
        elif target == "canopy_bottom":
            new = _apply_canopy_bottom(result, all_blocks, dec, trunk_positions, rng)
            result.update(new)
        elif target == "trunk_base":
            if trunk_positions:
                new = _apply_trunk_base(result, all_blocks, dec, trunk_positions, rng)
                result.update(new)

    return result
