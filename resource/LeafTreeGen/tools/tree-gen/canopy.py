"""Canopy geometry: layer profiles, leaf placement, and branch system."""

import math
import random
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from trunk import _log_axis


def _value_noise_1d(x: float, seed: int) -> float:
    """Deterministic noise value in [0,1] for a 1D coordinate (hash-based, no dependencies)."""
    h = hash((seed, int(x * 1000))) & 0xFFFFFFFF
    return (h ^ (h >> 16)) / 0xFFFFFFFF


def _value_noise_3d(x: int, y: int, z: int, seed: int) -> float:
    """Deterministic noise value in [0,1] for a 3D coordinate, used to thin out leaves."""
    h = hash((seed, x, y, z)) & 0xFFFFFFFF
    return (h ^ (h >> 16)) / 0xFFFFFFFF


def _resolve_leaf(leaf_block: str, secondary_leaves, secondary_fraction: float, rng: random.Random) -> str:
    """Return leaf_block or a secondary leaf based on secondary_fraction probability."""
    if secondary_leaves is None or secondary_fraction <= 0.0:
        return leaf_block
    if rng.random() >= secondary_fraction:
        return leaf_block
    if isinstance(secondary_leaves, str):
        return secondary_leaves
    # list of {block, weight} dicts
    total = sum(e.get("weight", 1) for e in secondary_leaves)
    r = rng.random() * total
    cumulative = 0.0
    for entry in secondary_leaves:
        cumulative += entry.get("weight", 1)
        if r < cumulative:
            return entry["block"]
    return secondary_leaves[-1]["block"]


def _place_leaf_disc(cx: int, cy: int, cz: int, radius: float,
                     leaf_block: str, mode: str, density: float,
                     seed: int, existing: dict,
                     secondary_leaves=None, secondary_fraction: float = 0.0) -> dict:
    """Horizontal disc of leaves at (cx, cy, cz); mode: trimmed|filled|density|noise."""
    blocks = {}
    rng = random.Random(seed)
    ir = int(math.ceil(radius))
    for dx in range(-ir, ir + 1):
        for dz in range(-ir, ir + 1):
            dist = math.sqrt(dx * dx + dz * dz)
            if mode == "trimmed":
                if dist > radius or (abs(dx) == ir and abs(dz) == ir):
                    continue
            elif mode == "filled":
                if dist > radius:
                    continue
            elif mode == "density":
                if dist > radius:
                    continue
                falloff = 1.0 - (dist / (radius + 1.0))
                if rng.random() > density * falloff:
                    continue
            elif mode == "noise":
                if dist > radius:
                    continue
                if _value_noise_3d(cx + dx, cy, cz + dz, seed) > density:
                    continue
            pos = (cx + dx, cy, cz + dz)
            if pos not in existing:
                blocks[pos] = _resolve_leaf(leaf_block, secondary_leaves, secondary_fraction, rng)
    return blocks


def _place_leaf_cluster(cx: int, cy: int, cz: int, radius: int,
                        leaf_block: str, mode: str, density: float,
                        seed: int, existing: dict,
                        secondary_leaves=None, secondary_fraction: float = 0.0) -> dict:
    """Spherical leaf cluster of given radius centered at (cx, cy, cz)."""
    blocks = {}
    rng = random.Random(seed)
    for dx in range(-radius, radius + 1):
        for dy in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                dist = math.sqrt(dx * dx + dy * dy + dz * dz)
                if mode == "trimmed":
                    if dist > radius or (abs(dx) == radius and abs(dz) == radius):
                        continue
                elif mode == "filled":
                    if dist > radius:
                        continue
                elif mode == "density":
                    if dist > radius:
                        continue
                    falloff = 1.0 - (dist / (radius + 1.0))
                    if rng.random() > density * falloff:
                        continue
                elif mode == "noise":
                    if dist > radius:
                        continue
                    if _value_noise_3d(cx + dx, cy + dy, cz + dz, seed) > density:
                        continue
                pos = (cx + dx, cy + dy, cz + dz)
                if pos not in existing:
                    blocks[pos] = _resolve_leaf(leaf_block, secondary_leaves, secondary_fraction, rng)
    return blocks


# Per-profile radius scale: multiplied against base_radius (height//2)
_PROFILE_RADIUS_SCALE = {
    "oak":      1.0,
    "birch":    0.7,
    "spruce":   0.55,
    "jungle":   0.45,
    "acacia":   0.6,
    "dark_oak": 1.0,
    # Flat, wide umbrella crown for vanilla dark oak (kept separate from the
    # generic "dark_oak" profile so glowcap/blightroot, which reuse "dark_oak",
    # are unaffected).
    "dark_oak_flat": 1.25,
    # Extra-wide flat umbrella for the giant roofed-forest variant: crowns this
    # broad overlap into a continuous closed canopy roof.
    "dark_oak_flat_wide": 1.8,
    "cherry":   1.0,
}

_PRESETS = {
    "oak": [
        (0.55, 0.6),
        (0.65, 0.9),
        (0.75, 1.0),
        (0.85, 0.9),
        (0.92, 0.65),
        (0.98, 0.35),
    ],
    "birch": [
        (0.6, 0.45),
        (0.7, 0.75),
        (0.8, 0.85),
        (0.88, 0.75),
        (0.94, 0.45),
        (0.99, 0.2),
    ],
    "spruce": [
        (0.3, 1.0),
        (0.42, 0.85),
        (0.54, 0.7),
        (0.65, 0.55),
        (0.75, 0.4),
        (0.84, 0.25),
        (0.91, 0.15),
        (0.97, 0.05),
    ],
    "jungle": [
        (0.82, 0.4),
        (0.88, 0.8),
        (0.93, 1.0),
        (0.97, 0.7),
        (1.0, 0.3),
    ],
    "acacia": [
        (0.9, 0.5),
        (0.95, 0.8),
        (0.99, 0.4),
    ],
    "dark_oak": [
        (0.5, 0.7),
        (0.62, 1.0),
        (0.72, 1.0),
        (0.82, 0.9),
        (0.91, 0.6),
        (0.97, 0.3),
    ],
    # Flat umbrella: layers compressed into the top ~25% so the crown reads as a
    # wide, shallow slab (vanilla dark oak) rather than a sphere.
    "dark_oak_flat": [
        (0.74, 0.62),
        (0.83, 1.0),
        (0.92, 1.0),
        (1.0, 0.6),
    ],
    # Same shallow-slab shape as dark_oak_flat (wider radius comes from the
    # radius scale above), used by the giant roofed-forest variant.
    "dark_oak_flat_wide": [
        (0.74, 0.62),
        (0.83, 1.0),
        (0.92, 1.0),
        (1.0, 0.62),
    ],
    "cherry": [
        (0.5, 0.5),
        (0.62, 0.9),
        (0.74, 1.0),
        (0.84, 1.0),
        (0.92, 0.7),
        (0.98, 0.4),
    ],
}


def preset_layers(profile: str, height: int, branch_driven: bool = False) -> list:
    """(y_offset, radius) pairs for the given profile scaled to height."""
    scale = _PROFILE_RADIUS_SCALE.get(profile, 1.0)
    base_radius = max(3, int(round(height // 2 * scale)))
    fractions = _PRESETS.get(profile, _PRESETS["oak"])

    # Branch-driven trees anchor volume layers at the trunk tip (no floating crown).
    if branch_driven:
        crown_extra = 0
    else:
        crown_extra = max(2, height // 4)
    # y_frac=1.0 maps to height - 1 + crown_extra
    y_top = (height - 1) + crown_extra

    layers = []
    for y_frac, r_frac in fractions:
        y_off = int(round(y_frac * y_top))
        radius = max(1.5, base_radius * r_frac)
        layers.append((y_off, radius))
    return layers


def _layer_half_height(radius: float, start_angle_deg: float, squish: float) -> int:
    """Upward half-height of a dome layer (0=sphere, 90=hemisphere, 180=disc)."""
    if start_angle_deg >= 180.0:
        return 0
    return int(math.ceil(radius * squish))


def generate_volume_canopy(height: int, layers: list, leaf_block: str,
                           start_angle: float, squish: float,
                           mode: str, leaf_density: float, seed: int,
                           existing: dict,
                           trunk_offsets: list = None,
                           secondary_leaves=None, secondary_fraction: float = 0.0) -> dict:
    """Build the solid leaf volume by stacking dome-shaped leaf discs at each preset layer."""
    blocks = {}
    if trunk_offsets and len(trunk_offsets) > 0:
        tip_cx, tip_cz = trunk_offsets[-1]
    else:
        tip_cx, tip_cz = 0.0, 0.0
    ocx = int(round(tip_cx))
    ocz = int(round(tip_cz))

    for y_off, radius in layers:
        y_center = y_off
        half_h = _layer_half_height(radius, start_angle, squish)
        # down_h: angle=0 -> full sphere, angle>=90 -> upper hemisphere only
        if start_angle < 90.0:
            angle_rad = math.radians(start_angle)
            down_h = int(math.ceil(radius * math.cos(angle_rad) * squish))
        else:
            down_h = 0

        skirt = down_h
        for y in range(y_center - skirt, y_center + half_h + 1):
            dy = y - y_center
            if half_h > 0 and dy >= 0:
                layer_r = radius * math.sqrt(max(0.0, 1.0 - (dy / half_h) ** 2))
            elif skirt > 0 and dy < 0:
                layer_r = radius * math.sqrt(max(0.0, 1.0 - (dy / skirt) ** 2))
            else:
                layer_r = radius
            if layer_r < 0.5:
                continue
            new = _place_leaf_disc(ocx, y, ocz, layer_r, leaf_block, mode, leaf_density, seed + y, {**existing, **blocks},
                                   secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction)
            blocks.update(new)

    return blocks


def _prob_constant(t: float, params: dict) -> float:
    """Same branch chance at every height."""
    return params.get("p", 0.5)


def _prob_linear(t: float, params: dict) -> float:
    """Branch chance ramps straight from base_p at the bottom to crown_p at the top."""
    base_p = params.get("base_p", 0.0)
    crown_p = params.get("crown_p", 1.0)
    return base_p + (crown_p - base_p) * t


def _prob_sigmoid(t: float, params: dict) -> float:
    """Branch chance follows an S-curve, switching on around the midpoint height."""
    steepness = params.get("steepness", 10.0)
    midpoint = params.get("midpoint", 0.7)
    return 1.0 / (1.0 + math.exp(-steepness * (t - midpoint)))


def _prob_top_heavy(t: float, params: dict) -> float:
    """Branch chance rises with height raised to a power, so branches cluster near the crown."""
    exponent = params.get("exponent", 2.0)
    return t ** exponent


def _prob_gaussian(t: float, params: dict) -> float:
    """Branch chance peaks in a band around mean height and falls off above and below it."""
    mean = params.get("mean", 0.7)
    std = params.get("std", 0.15)
    return math.exp(-0.5 * ((t - mean) / max(std, 1e-6)) ** 2)


def _prob_noise(t: float, params: dict) -> float:
    """Branch chance varies irregularly with height using deterministic noise."""
    scale = params.get("scale", 1.0)
    nseed = params.get("seed", 0)
    return _value_noise_1d(t * scale, nseed)


_PROB_FNS = {
    "constant": _prob_constant,
    "linear": _prob_linear,
    "sigmoid": _prob_sigmoid,
    "top_heavy": _prob_top_heavy,
    "gaussian": _prob_gaussian,
    "noise": _prob_noise,
}

_LENGTH_FNS = {
    "constant": lambda t, p: p.get("length", 3),
    "linear": lambda t, p: p.get("base", 1) + (p.get("crown", 4) - p.get("base", 1)) * t,
    "sigmoid": lambda t, p: p.get("max_len", 4) / (1.0 + math.exp(-p.get("steepness", 5.0) * (t - 0.5))),
    "log": lambda t, p: p.get("max_len", 4) * math.log(1.0 + t * (math.e - 1.0)),
    "parabolic": lambda t, p: p.get("max_len", 4) * (1.0 - (2.0 * t - 1.0) ** 2),
}


def _branch_length(t: float, length_fn: str, length_params: dict) -> float:
    """Branch length at height t from the chosen length function (never below 1 block)."""
    fn = _LENGTH_FNS.get(length_fn, _LENGTH_FNS["linear"])
    return max(1.0, fn(t, length_params))


def _branch_endpoint(ox: int, oy: int, oz: int,
                     azimuth_deg: float, elevation_deg: float, length: float):
    """Project a branch tip from origin given a compass azimuth, upward elevation, and length."""
    az_rad = math.radians(azimuth_deg)
    el_rad = math.radians(elevation_deg)
    dx = length * math.cos(el_rad) * math.sin(az_rad)
    dy = length * math.sin(el_rad)
    dz = length * math.cos(el_rad) * math.cos(az_rad)
    return (ox + int(round(dx)), oy + int(round(dy)), oz + int(round(dz)))


# Block ids that support an `axis` property. Non-orientable branch blocks (e.g. lava
# for the inverted lava-vein objects) must NOT get an [axis=..] suffix appended.
_ORIENTABLE_TOKENS = ("log", "wood", "stem", "hyphae", "basalt", "pillar", "bone_block")


def _axis_block(block: str, axis: str) -> str:
    """Append [axis=..] only to orientable blocks that don't already carry a state."""
    if "[" in block:
        return block
    base = block.split(":")[-1]
    if any(tok in base for tok in _ORIENTABLE_TOKENS):
        return block + "[axis=%s]" % axis
    return block


def _rasterize_branch(ox: int, oy: int, oz: int,
                      ex: int, ey: int, ez: int,
                      trunk_block: str) -> dict:
    """Rasterize branch from origin to endpoint; always includes origin block."""
    blocks = {}
    steps = max(abs(ex - ox), abs(ey - oy), abs(ez - oz), 1)
    axis = _log_axis(ex - ox, ey - oy, ez - oz)
    block = _axis_block(trunk_block, axis)
    for i in range(steps + 1):
        t = i / steps
        x = int(round(ox + (ex - ox) * t))
        y = int(round(oy + (ey - oy) * t))
        z = int(round(oz + (ez - oz) * t))
        blocks[(x, y, z)] = block
    blocks[(ox, oy, oz)] = block
    return blocks


def generate_branch_canopy(height: int, trunk_block: str, leaf_block: str,
                           branch_cfg: dict, seed: int, existing: dict,
                           trunk_offsets: list = None,
                           secondary_leaves=None, secondary_fraction: float = 0.0,
                           collect_endpoints: list = None) -> dict:
    """Canopy blocks from the branch system; existing trunk blocks are never overwritten.

    If collect_endpoints is a list, each branch endpoint is appended as
    (ex, ey, ez, origin_x, origin_z) for use by the decorator system.
    """
    blocks = {}
    rng = random.Random(seed)

    prob_fn_name = branch_cfg.get("prob_fn", "top_heavy")
    prob_params = branch_cfg.get("prob_params", {})
    length_fn_name = branch_cfg.get("length_fn", "linear")
    length_params = branch_cfg.get("length_params", {})
    azimuth_cfg = branch_cfg.get("azimuth", "random")
    elevation_deg = branch_cfg.get("elevation", 0.0)
    leaf_start_up = branch_cfg.get("leaf_start_up", False)
    cluster_radius = branch_cfg.get("cluster_radius", 2)
    cluster_mode = branch_cfg.get("cluster_mode", "trimmed")
    cluster_density = branch_cfg.get("cluster_density", 0.85)
    sub_cfg = branch_cfg.get("sub_branches", None)

    prob_fn = _PROB_FNS.get(prob_fn_name, _prob_top_heavy)

    for y in range(height):
        t = y / max(height - 1, 1)
        p = prob_fn(t, prob_params)
        if rng.random() > p:
            continue

        if trunk_offsets and y < len(trunk_offsets):
            ox = int(round(trunk_offsets[y][0]))
            oz = int(round(trunk_offsets[y][1]))
        else:
            ox, oz = 0, 0

        if azimuth_cfg == "random":
            az = rng.uniform(0.0, 360.0)
        else:
            az = float(azimuth_cfg)

        branch_len = _branch_length(t, length_fn_name, length_params)
        eff_elevation = max(0.0, elevation_deg) if leaf_start_up else elevation_deg
        ex, ey, ez = _branch_endpoint(ox, y, oz, az, eff_elevation, branch_len)

        branch_blocks = _rasterize_branch(ox, y, oz, ex, ey, ez, trunk_block)
        branch_az = az
        branch_el = eff_elevation
        for pos, state in branch_blocks.items():
            if pos not in existing:
                blocks[pos] = state

        if collect_endpoints is not None:
            collect_endpoints.append((ex, ey, ez, ox, oz))

        cluster = _place_leaf_cluster(ex, ey, ez, cluster_radius, leaf_block,
                                      cluster_mode, cluster_density, seed + y,
                                      {**existing, **blocks},
                                      secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction)
        blocks.update(cluster)

        if sub_cfg:
            sub_count = sub_cfg.get("count", 1)
            pitch_delta = sub_cfg.get("pitch_delta", 0.0)
            yaw_delta = sub_cfg.get("yaw_delta", 45.0)
            length_scale = sub_cfg.get("length_scale", 0.5)
            sub_cluster_r = sub_cfg.get("cluster_radius", 1)
            sub_cluster_mode = sub_cfg.get("cluster_mode", "trimmed")
            sub_cluster_density = sub_cfg.get("cluster_density", 0.85)

            for si in range(sub_count):
                yaw_offset = yaw_delta * (si - (sub_count - 1) / 2.0)
                sub_az = branch_az + yaw_offset
                sub_el = branch_el + pitch_delta
                sub_len = branch_len * length_scale
                sx, sy, sz = _branch_endpoint(ex, ey, ez, sub_az, sub_el, sub_len)

                sub_branch_blocks = _rasterize_branch(ex, ey, ez, sx, sy, sz, trunk_block)
                for pos, state in sub_branch_blocks.items():
                    if pos not in existing:
                        blocks[pos] = state

                sub_cluster = _place_leaf_cluster(sx, sy, sz, sub_cluster_r, leaf_block,
                                                  sub_cluster_mode, sub_cluster_density,
                                                  seed + y + si + 1000,
                                                  {**existing, **blocks},
                                                  secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction)
                blocks.update(sub_cluster)

    return blocks


def _cap_trunk_tip(leaf_block: str, mode: str, density: float, seed: int,
                   trunk_blocks: dict, canopy_blocks: dict,
                   secondary_leaves=None, secondary_fraction: float = 0.0) -> None:
    """Enclose an exposed trunk apex so the trunk never pokes above its canopy.

    Only acts when the highest leaf sits below the highest trunk log (a visible
    poke). Builds a short pointed leaf cap over the trunk's top footprint,
    tapering to a single leaf above the tip, so the result reads as a natural
    conifer point rather than a bare log spike. No-op when the canopy already
    covers the tip.
    """
    def _is_log(b):
        return "log" in b or "wood" in b

    combined = {**trunk_blocks, **canopy_blocks}
    # Consider every log (trunk column AND branch logs) so branch tips can't poke
    # either; cap above the highest log so nothing wooden is left uncovered.
    log_positions = [(x, y, z) for (x, y, z), b in combined.items() if _is_log(b)]
    if not log_positions:
        return
    top_y = max(y for (_x, y, _z) in log_positions)
    # Top logs that are actually exposed upward (no block directly above). If the
    # canopy already encloses every top log this leaves nothing to do.
    exposed = [(x, y, z) for (x, y, z) in log_positions
               if y >= top_y - 1 and (x, y + 1, z) not in combined]
    if not exposed:
        return

    cx = int(round(sum(x for x, _y, _z in exposed) / len(exposed)))
    cz = int(round(sum(z for _x, _y, z in exposed) / len(exposed)))
    half = max((max(abs(x - cx), abs(z - cz)) for x, _y, z in exposed), default=0)

    # Lay a full footprint leaf layer directly above the highest log layer so no
    # log top face is left exposed, then taper to a single-leaf point. Only empty
    # cells are filled, so this is a no-op wherever the crown already covers.
    cap_rows = [(top_y + 1, half + 0.5),
                (top_y + 2, max(1.0, half - 0.5)), (top_y + 3, 0.0)]
    for y, r in cap_rows:
        if r < 0.5:
            pos = (cx, y, cz)
            if pos not in combined:
                leaf = _resolve_leaf(leaf_block, secondary_leaves, secondary_fraction,
                                     random.Random(seed + 7000 + y))
                canopy_blocks[pos] = leaf
                combined[pos] = leaf
            continue
        disc = _place_leaf_disc(cx, y, cz, r, leaf_block, mode, density,
                                seed + 7000 + y, combined,
                                secondary_leaves=secondary_leaves,
                                secondary_fraction=secondary_fraction)
        canopy_blocks.update(disc)
        combined.update(disc)


def generate_canopy(height: int, trunk_block: str, leaf_block: str,
                    profile: str, canopy_cfg: dict, seed: int,
                    existing: dict, trunk_offsets: list = None,
                    secondary_leaves=None, secondary_fraction: float = 0.0,
                    collect_endpoints: list = None) -> dict:
    """All canopy blocks: volume layers plus optional branch system."""
    start_angle = canopy_cfg.get("start_angle", 90.0)
    squish = canopy_cfg.get("squish", 1.0)
    mode = canopy_cfg.get("mode", "trimmed")
    leaf_density = canopy_cfg.get("leaf_density", 0.85)
    branch_cfg = canopy_cfg.get("branches", None)

    explicit_layers = canopy_cfg.get("layers", None)
    if explicit_layers:
        layers = [(int(l["y_offset"]), float(l["radius"])) for l in explicit_layers]
    else:
        layers = preset_layers(profile, height, branch_driven=branch_cfg is not None)

    blocks = {}
    # For branch-driven trees only place the top crown layer(s) via volume;
    # the rest of the canopy is built by the branch system. Setting
    # "crown_volume_fraction" keeps the upper fraction of preset layers as a
    # solid cone (so a conifer reads as a filled, tapering crown at every scale)
    # while still adding the branch system below for silhouette. Default
    # behaviour (no key) is unchanged: only the single topmost layer is volume.
    if branch_cfg:
        crown_frac = canopy_cfg.get("crown_volume_fraction", None)
        if crown_frac is not None and layers:
            n = max(1, int(round(len(layers) * float(crown_frac))))
            crown_layers = layers[-n:]
        else:
            crown_layers = [layers[-1]] if layers else []
        vol = generate_volume_canopy(height, crown_layers, leaf_block,
                                     start_angle, squish, mode, leaf_density,
                                     seed, {**existing}, trunk_offsets=trunk_offsets,
                                     secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction)
    else:
        vol = generate_volume_canopy(height, layers, leaf_block,
                                     start_angle, squish, mode, leaf_density,
                                     seed, {**existing}, trunk_offsets=trunk_offsets,
                                     secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction)
    blocks.update(vol)

    if branch_cfg:
        br = generate_branch_canopy(height, trunk_block, leaf_block,
                                    branch_cfg, seed + 9999,
                                    {**existing, **blocks},
                                    trunk_offsets=trunk_offsets,
                                    secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction,
                                    collect_endpoints=collect_endpoints)
        blocks.update(br)

    # Guarantee the trunk apex is enclosed so it never pokes above its canopy.
    _cap_trunk_tip(leaf_block, mode, leaf_density, seed + 13337,
                   existing, blocks,
                   secondary_leaves=secondary_leaves, secondary_fraction=secondary_fraction)

    return blocks
