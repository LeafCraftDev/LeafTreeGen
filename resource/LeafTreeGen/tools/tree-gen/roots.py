"""Root system geometry.

Generates a downward root structure beneath a tree's trunk base so the trunk
always connects to the ground when Iris places the object. Iris anchors objects
by their center, and the lowest model blocks are often drooping leaves/decorators,
which can leave the trunk base floating a few blocks above the surface on uneven
terrain. A taproot + flared buttress legs extending below y=0 bridge that gap (and
simply bury harmlessly when the base already sits on the ground).

The structure is proportional to tree size: deeper/wider roots for taller trees.
"""

import math
import random


def _log_y(trunk_block: str) -> str:
    """Return the trunk log block forced to vertical orientation (axis=y) for root columns."""
    base = trunk_block.split("[")[0]
    return base + "[axis=y]"


def root_depth_for(height: int) -> int:
    """Vertical reach of the root system, proportional to tree height."""
    return max(2, min(16, int(round(0.18 * height))))


def build_roots(base_cells, height: int, trunk_block: str, seed: int = 0,
                root_block: str = None) -> dict:
    """Return {(x, y, z): blockstate} of root blocks at y < 0.

    base_cells: iterable of (x, z) trunk footprint positions at the base layer (y==0).
    root_block: optional override for the root material. When given it is used verbatim
        (e.g. "minecraft:fire" or "minecraft:air") instead of the vertical trunk log -
        used by the inverted lava-vein objects whose flipped "roots" reach up through the
        surface to carve / scorch an irregular mountaintop pattern.
    """
    cells = list(base_cells)
    if not cells:
        return {}

    log = root_block if root_block else _log_y(trunk_block)
    depth = root_depth_for(height)
    rng = random.Random((seed ^ 0x5009) & 0xFFFFFFFF)

    # Footprint centroid and radius.
    cx = sum(c[0] for c in cells) / len(cells)
    cz = sum(c[1] for c in cells) / len(cells)
    base_radius = max(1.0, max(math.hypot(x - cx, z - cz) for (x, z) in cells) + 0.5)

    blocks = {}

    # 1) Central taproot: the base footprint extended straight down, tapering inward
    #    so deeper layers are narrower. Guarantees a solid vertical connection.
    for k in range(1, depth + 1):
        frac = k / depth
        keep_r = base_radius * (1.0 - 0.6 * frac)
        for (x, z) in cells:
            if math.hypot(x - cx, z - cz) <= keep_r + 1e-6:
                blocks[(x, -k, z)] = log
        # Always keep the single most central cell so the taproot never breaks.
        icx, icz = int(round(cx)), int(round(cz))
        blocks[(icx, -k, icz)] = log

    # 2) Buttress legs: a ring of roots flaring outward as they descend, anchoring
    #    the tree on slopes. Each leg steps out and down together (kept contiguous).
    n_legs = max(4, min(12, len(cells) + 2))
    leg_len = depth
    flare = base_radius + max(2.0, depth * 0.6)
    for li in range(n_legs):
        ang = 2.0 * math.pi * li / n_legs + rng.uniform(-0.25, 0.25)
        ca, sa = math.cos(ang), math.sin(ang)
        prev = (int(round(cx + base_radius * ca)), int(round(cz + base_radius * sa)))
        for k in range(1, leg_len + 1):
            frac = k / leg_len
            r = base_radius + (flare - base_radius) * frac
            x = int(round(cx + r * ca))
            z = int(round(cz + r * sa))
            blocks.setdefault((x, -k, z), log)
            # Bridge horizontal jumps so legs stay connected (1 block per step).
            if x != prev[0] or z != prev[1]:
                blocks.setdefault((prev[0], -k, prev[1]), log)
            prev = (x, z)

    return blocks
