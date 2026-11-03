"""Trunk geometry: column blocks, width shaping, and lean/curve."""

import math
import random


# Width shaping functions: t in [0,1] -> multiplier applied to trunk_width

def _shape_constant(t: float, params: dict) -> float:
    """Constant width: same multiplier at every height."""
    return 1.0


def _shape_linear(t: float, params: dict) -> float:
    """Width ramps straight from start (base) to end (top)."""
    start = params.get("start", 1.0)
    end = params.get("end", 1.0)
    return start + (end - start) * t


def _shape_sigmoid(t: float, params: dict) -> float:
    """S-curve taper: wide near the base, narrowing through the middle to the top."""
    steepness = params.get("steepness", 5.0)
    return 1.0 / (1.0 + math.exp(steepness * (t - 0.5)))


def _shape_log(t: float, params: dict) -> float:
    """Logarithmic taper: quick narrowing low down that flattens out higher up."""
    base = params.get("base", math.e)
    if t <= 0.0:
        return 1.0
    return max(0.0, 1.0 - math.log(1.0 + t * (base - 1.0)) / math.log(base))


def _shape_sine(t: float, params: dict) -> float:
    """Wavy width: a sine ripple added along the trunk for a knobbly look."""
    period = params.get("period", 1.0)
    amplitude = params.get("amplitude", 0.2)
    return 1.0 + amplitude * math.sin(2.0 * math.pi * t / period)


def _shape_parabolic(t: float, params: dict) -> float:
    """Pinched waist: narrowest at peak_offset, widening toward both ends."""
    # floor keeps the waist from dropping below half the base width.
    peak_offset = params.get("peak_offset", 0.5)
    floor_val = params.get("floor", 0.5)
    denom = max(peak_offset, 1.0 - peak_offset) ** 2
    raw = (t - peak_offset) ** 2 / denom
    return floor_val + (1.0 - floor_val) * raw


_SHAPE_FNS = {
    "constant": _shape_constant,
    "linear": _shape_linear,
    "sigmoid": _shape_sigmoid,
    "log": _shape_log,
    "sine": _shape_sine,
    "parabolic": _shape_parabolic,
}


def width_at(y: int, height: int, trunk_width: int, shape: str, shape_params: dict) -> int:
    """Trunk width (in blocks) at layer y, applying the chosen shape function."""
    fn = _SHAPE_FNS.get(shape, _shape_constant)
    t = y / max(height - 1, 1)
    multiplier = fn(t, shape_params)
    return max(1, round(trunk_width * multiplier))


# Azimuth functions: t in [0,1] -> azimuth in degrees
def _noise_azimuth(t: float, params: dict) -> float:
    """Pseudo-random lean direction at height t, from a hash so output stays deterministic."""
    scale = params.get("scale", 1.0)
    seed = params.get("seed", 0)
    h1 = hash((seed, int(t * scale * 100))) & 0xFFFFFFFF
    h2 = hash((seed + 1, int(t * scale * 50))) & 0xFFFFFFFF
    v = ((h1 ^ (h1 >> 16)) / 0xFFFFFFFF + (h2 ^ (h2 >> 16)) / 0xFFFFFFFF) / 2.0
    return v * 360.0


_AZIMUTH_FNS = {
    "constant": lambda t, p: p.get("azimuth", 0.0),
    "linear":   lambda t, p: p.get("start", 0.0) + (p.get("end", 0.0) - p.get("start", 0.0)) * t,
    "spiral":   lambda t, p: p.get("start", 0.0) + p.get("turns", 1.0) * 360.0 * t,
    "sine":     lambda t, p: p.get("offset", 0.0) + p.get("amplitude", 90.0) * math.sin(2.0 * math.pi * t / max(p.get("period", 1.0), 1e-6)),
    "noise":    lambda t, p: _noise_azimuth(t, p),
}


# Curve functions: t in [0,1] -> progress in [0,1] scaling lateral offset
_CURVE_FNS = {
    "linear":    lambda t, p: t,
    "log":       lambda t, p: math.log(1.0 + t * (math.e - 1.0)),
    "sigmoid":   lambda t, p: 1.0 / (1.0 + math.exp(-p.get("steepness", 8.0) * (t - 0.5))),
    "constant":  lambda t, p: t,
    "parabolic": lambda t, p: 3.0 * t ** 2 - 2.0 * t ** 3,
}


def _lean_offset(y: int, height: int, lean_azimuth_deg: float,
                 lean_angle_deg: float, curve_fn: str, curve_params: dict,
                 azimuth_fn: str = "constant", azimuth_params: dict = None):
    """(dx, dz) lateral offset of trunk center at layer y."""
    if lean_angle_deg == 0.0 or height <= 1:
        return 0.0, 0.0
    if azimuth_params is None:
        azimuth_params = {}
    t = y / max(height - 1, 1)
    fn = _CURVE_FNS.get(curve_fn, _CURVE_FNS["linear"])
    progress = fn(t, curve_params)
    max_reach = height * math.tan(math.radians(lean_angle_deg))
    reach = max_reach * progress
    if azimuth_fn != "constant" or not azimuth_params.get("azimuth"):
        az_fn = _AZIMUTH_FNS.get(azimuth_fn, _AZIMUTH_FNS["constant"])
        az_deg = az_fn(t, {"azimuth": lean_azimuth_deg, **azimuth_params})
    else:
        az_deg = lean_azimuth_deg
    az_rad = math.radians(az_deg)
    dx = reach * math.sin(az_rad)
    dz = reach * math.cos(az_rad)
    return dx, dz


def _log_axis(dx: float, dy: float, dz: float, rng: random.Random = None) -> str:
    """Pick the log axis (x/y/z) from a direction vector, leaning toward vertical so trunks read as upright."""
    ax, ay, az = abs(dx), abs(dy), abs(dz)
    # Weight the vertical component so near-vertical segments stay axis=y.
    ay_biased = ay * 1.8
    if ay_biased >= ax and ay_biased >= az:
        return "y"
    if ax >= az:
        return "x"
    return "z"


_LOG_TO_WOOD = {
    "minecraft:oak_log":      "minecraft:oak_wood",
    "minecraft:birch_log":    "minecraft:birch_wood",
    "minecraft:spruce_log":   "minecraft:spruce_wood",
    "minecraft:jungle_log":   "minecraft:jungle_wood",
    "minecraft:acacia_log":   "minecraft:acacia_wood",
    "minecraft:dark_oak_log": "minecraft:dark_oak_wood",
    "minecraft:cherry_log":   "minecraft:cherry_wood",
    "minecraft:mangrove_log": "minecraft:mangrove_wood",
}


def _log_to_wood(trunk_block: str) -> str:
    """Map a log block to its all-bark wood variant (used for exposed trunk ends)."""
    base = trunk_block.split("[")[0]
    return _LOG_TO_WOOD.get(base, base)


# Block ids that support an `axis` property (pillar-like). Non-orientable trunk
# blocks (e.g. lava, obsidian for the inverted lava-vein objects) must NOT get an
# [axis=...] suffix appended, which would corrupt their blockstate.
_ORIENTABLE_TOKENS = ("log", "wood", "stem", "hyphae", "basalt", "pillar", "bone_block")


def _axis_block(block: str, axis: str) -> str:
    """Append [axis=..] only to orientable blocks that don't already carry a state."""
    if "[" in block:
        return block
    base = block.split(":")[-1]
    if any(tok in base for tok in _ORIENTABLE_TOKENS):
        return block + "[axis=%s]" % axis
    return block


# When True, trunk cross-sections are filled as a DISC (rounded) instead of a square.
# Toggled per-config by generate_tree via set_round_trunk(); default keeps the original
# square behaviour so existing tree configs are unaffected.
_ROUND_TRUNK = False
_ROUND_PHASES = (0.0, 0.0, 0.0)


def set_round_trunk(flag: bool, seed: int = 0):
    """Enable/disable rounded (disc) trunk cross-sections for subsequent generation.

    When enabled, the disc radius is modulated by a few angular sine harmonics (seeded
    per object) so the cross-section is an IRREGULAR rounded blob rather than a perfect
    circle. The phases are fixed per object so the irregular outline stays consistent up
    the whole trunk.
    """
    global _ROUND_TRUNK, _ROUND_PHASES
    _ROUND_TRUNK = bool(flag)
    r = random.Random((seed ^ 0x6F2A) & 0xFFFFFFFF)
    _ROUND_PHASES = (r.uniform(0, math.tau), r.uniform(0, math.tau), r.uniform(0, math.tau))


def _irregular_radius(rad: float, dx: float, dz: float) -> float:
    """Angular-noise modulated radius so the round trunk edge is irregular, not circular."""
    p1, p2, p3 = _ROUND_PHASES
    theta = math.atan2(dz, dx)
    factor = (1.0
              + 0.20 * math.sin(3 * theta + p1)
              + 0.13 * math.sin(5 * theta + p2)
              + 0.09 * math.sin(2 * theta + p3))
    return rad * factor


def _square_positions(cx: float, cz: float, width: int):
    """Yield (x, z) positions for a square (or irregular disc, if round trunk is on)."""
    if width % 2 == 1:
        half = width // 2
        icx = int(round(cx))
        icz = int(round(cz))
        rad = half + 0.5
        for dx in range(-half, half + 1):
            for dz in range(-half, half + 1):
                if _ROUND_TRUNK and math.hypot(dx, dz) > _irregular_radius(rad, dx, dz):
                    continue
                yield icx + dx, icz + dz
    else:
        ox = int(math.floor(cx)) - width // 2 + 1
        oz = int(math.floor(cz)) - width // 2 + 1
        rad = width / 2.0
        ccx = ox + (width - 1) / 2.0
        ccz = oz + (width - 1) / 2.0
        for dx in range(width):
            for dz in range(width):
                ddx = (ox + dx) - ccx
                ddz = (oz + dz) - ccz
                if _ROUND_TRUNK and math.hypot(ddx, ddz) > _irregular_radius(rad, ddx, ddz):
                    continue
                yield ox + dx, oz + dz


def generate_trunk(height: int, trunk_block: str, trunk_width: int,
                   trunk_shape: str, trunk_shape_params: dict,
                   lean_azimuth: float = 0.0, lean_angle: float = 0.0,
                   curve_fn: str = "linear", curve_params: dict = None) -> dict:
    """Return trunk blocks only; see generate_trunk_with_offsets for lean support."""
    blocks, _ = generate_trunk_with_offsets(
        height, trunk_block, trunk_width, trunk_shape, trunk_shape_params,
        lean_azimuth, lean_angle, curve_fn, curve_params or {}
    )
    return blocks


def _secondary_trunk_block(y: int, height: int, trunk_block: str,
                            secondary_trunk: str,
                            secondary_trunk_start: float,
                            secondary_trunk_end: float) -> str:
    """Return the trunk block to use at layer y, choosing secondary when in range."""
    if secondary_trunk is None:
        return trunk_block
    t = y / max(height - 1, 1)
    if secondary_trunk_start <= t <= secondary_trunk_end:
        return secondary_trunk
    return trunk_block


def generate_trunk_with_offsets(height: int, trunk_block: str, trunk_width: int,
                                 trunk_shape: str, trunk_shape_params: dict,
                                 lean_azimuth: float = 0.0, lean_angle: float = 0.0,
                                 curve_fn: str = "linear",
                                 curve_params: dict = None,
                                 azimuth_fn: str = "constant",
                                 azimuth_params: dict = None,
                                 secondary_trunk: str = None,
                                 secondary_trunk_start: float = 0.5,
                                 secondary_trunk_end: float = 1.0) -> tuple:
    """Return (blocks_dict, offsets_list) where offsets_list[y] = (cx, cz)."""
    if curve_params is None:
        curve_params = {}
    if azimuth_params is None:
        azimuth_params = {}
    blocks = {}
    offsets = []
    prev_cx, prev_cz = 0.0, 0.0
    for y in range(height):
        dx, dz = _lean_offset(y, height, lean_azimuth, lean_angle, curve_fn, curve_params,
                               azimuth_fn, azimuth_params)
        cx, cz = dx, dz
        offsets.append((cx, cz))
        w = width_at(y, height, trunk_width, trunk_shape, trunk_shape_params)
        step_dx = cx - prev_cx
        step_dz = cz - prev_cz
        axis = _log_axis(step_dx, 1.0, step_dz)
        active_trunk = _secondary_trunk_block(y, height, trunk_block,
                                              secondary_trunk, secondary_trunk_start,
                                              secondary_trunk_end)
        block = _axis_block(active_trunk, axis)
        for x, z in _square_positions(cx, cz, w):
            blocks[(x, y, z)] = block
        # Fill connectivity gap: if center shifted >1 block, rasterize intermediate layers
        if y > 0:
            shift = math.sqrt((cx - prev_cx) ** 2 + (cz - prev_cz) ** 2)
            if shift > 1.0:
                steps = int(math.ceil(shift))
                for s in range(1, steps):
                    t_fill = s / steps
                    icx = prev_cx + (cx - prev_cx) * t_fill
                    icz = prev_cz + (cz - prev_cz) * t_fill
                    iy = y - 1 + t_fill  # fractional y, round to nearest
                    fill_y = int(round(iy))
                    for x, z in _square_positions(icx, icz, w):
                        pos = (x, fill_y, z)
                        if pos not in blocks:
                            blocks[pos] = block
        prev_cx, prev_cz = cx, cz

    # Mark endpoint blocks (top and bottom) touching air with wood (all-bark) block
    # Use the secondary trunk's wood variant where secondary blocks were placed.
    wood_block = _log_to_wood(trunk_block)
    secondary_wood = _log_to_wood(secondary_trunk) if secondary_trunk else None
    if wood_block != trunk_block.split("[")[0] or (secondary_wood and secondary_wood != secondary_trunk.split("[")[0]):
        all_pos = set(blocks.keys())
        for (x, y, z), blk in list(blocks.items()):
            blk_base = blk.split("[")[0]
            if blk_base == trunk_block.split("[")[0]:
                if (x, y + 1, z) not in all_pos or (x, y - 1, z) not in all_pos:
                    blocks[(x, y, z)] = wood_block
            elif secondary_trunk and blk_base == secondary_trunk.split("[")[0] and secondary_wood:
                if (x, y + 1, z) not in all_pos or (x, y - 1, z) not in all_pos:
                    blocks[(x, y, z)] = secondary_wood

    return blocks, offsets
