#!/usr/bin/env python3
"""Convert our offline tree-gen JSON configs into Iris 4.0 procedural-tree JSON.

Iris 4.0 (``art.arcane.iris.engine.object.IrisProceduralTree``) generates trees
at world-gen time from a JSON definition instead of consuming a pre-baked ``.iob``
object. This tool reads one of our ``configs/*.json`` files (the same flat array
of tree definitions that ``generate_tree.py`` bakes into ``.iob``) and emits the
equivalent Iris 4.0 procedural-tree JSON.

The full field-by-field mapping is documented in
``docs/scratch/IRIS4-TREE-SCHEMA-COMPARISON.md``. In short the conversion is:
  - recase keys ``snake_case`` -> ``camelCase``
  - flatten our nested ``*_params`` objects into Iris' discrete prefixed fields
  - split ``branches.azimuth`` (string|number) into ``azimuthMode`` + ``azimuth``
  - uppercase enum string values

Pure Python 3 standard library; no server required. This converter only handles
the procedural tree model. ``.iob``-only post-processing hacks (encase, invert_y,
clear_cone, ...) have no procedural equivalent and are reported as skipped.
"""

import argparse
import json
import os
import sys


# Fields on our config that only affect the baked .iob block soup and have no
# procedural-tree equivalent. Presence triggers a warning so nothing is silently lost.
_IOB_ONLY_FIELDS = (
    "encase", "encase_targets", "encase_top_variation", "encase_open_top",
    "invert_y", "clear_cone_height", "clear_cone_expand", "trunk_round",
    "root_block",
)

# Authoring/output-naming fields with no runtime meaning in Iris.
_DROP_FIELDS = ("comment", "filenames", "out")


def _strip_state(block: str) -> str:
    """Drop a trailing ``[...]`` blockstate; Iris takes a plain material id for trunk/leaves."""
    if not isinstance(block, str):
        return block
    return block.split("[", 1)[0]


def _enum(value):
    """Uppercase an enum string value (our lowercase values map 1:1 onto Iris constants)."""
    return value.upper() if isinstance(value, str) else value


def _flatten_shape(out: dict, params: dict) -> None:
    """trunk_shape_params.* -> shape* discrete fields."""
    mapping = {
        "start": "shapeStart", "end": "shapeEnd", "steepness": "shapeSteepness",
        "base": "shapeBase", "period": "shapePeriod", "amplitude": "shapeAmplitude",
        "peak_offset": "shapePeakOffset", "floor": "shapeFloor",
    }
    for k, v in params.items():
        if k in mapping:
            out[mapping[k]] = v


def _flatten_azimuth(out: dict, params: dict) -> None:
    """lean_azimuth_params.* -> azimuth* discrete fields."""
    mapping = {
        "start": "azimuthStart", "end": "azimuthEnd", "turns": "azimuthTurns",
        "amplitude": "azimuthAmplitude", "period": "azimuthPeriod",
        "offset": "azimuthOffset", "scale": "azimuthScale",
        "whorl_count": "azimuthWhorlCount",
    }
    for k, v in params.items():
        if k in mapping:
            out[mapping[k]] = v


def _convert_branches(branch_cfg: dict) -> dict:
    """canopy.branches -> IrisTreeBranches (flatten prob_/length_params, split azimuth)."""
    out = {}

    out["probabilityFunction"] = _enum(branch_cfg.get("prob_fn", "top_heavy"))
    prob = branch_cfg.get("prob_params", {})
    prob_map = {
        "p": "probabilityConstant", "base_p": "probabilityBase",
        "crown_p": "probabilityCrown", "steepness": "probabilitySteepness",
        "midpoint": "probabilityMidpoint", "exponent": "probabilityExponent",
        "mean": "probabilityMean", "std": "probabilityStd", "scale": "probabilityScale",
        "periods": "probabilityPeriods",
    }
    for k, v in prob.items():
        if k in prob_map:
            out[prob_map[k]] = v

    out["lengthFunction"] = _enum(branch_cfg.get("length_fn", "linear"))
    length = branch_cfg.get("length_params", {})
    length_map = {
        "length": "lengthConstant", "base": "lengthBase", "crown": "lengthCrown",
        "max_len": "lengthMax", "steepness": "lengthSteepness",
    }
    for k, v in length.items():
        if k in length_map:
            out[length_map[k]] = v

    azimuth = branch_cfg.get("azimuth", "random")
    if isinstance(azimuth, str):
        out["azimuthMode"] = _enum(azimuth)
    else:
        out["azimuthMode"] = "CONSTANT"
        out["azimuth"] = azimuth

    if "elevation" in branch_cfg:
        out["elevation"] = branch_cfg["elevation"]
    if "leaf_start_up" in branch_cfg:
        out["leafStartUp"] = branch_cfg["leaf_start_up"]
    if "cluster_radius" in branch_cfg:
        out["clusterRadius"] = branch_cfg["cluster_radius"]
    if "cluster_mode" in branch_cfg:
        out["clusterMode"] = _enum(branch_cfg["cluster_mode"])
    if "cluster_density" in branch_cfg:
        out["clusterDensity"] = branch_cfg["cluster_density"]

    sub = branch_cfg.get("sub_branches")
    if isinstance(sub, dict):
        out["subBranches"] = _convert_sub_branches(sub)
    return out


def _convert_sub_branches(sub: dict) -> dict:
    """sub_branches -> IrisTreeSubBranches (casing only)."""
    out = {}
    rename = {
        "count": "count", "pitch_delta": "pitchDelta", "yaw_delta": "yawDelta",
        "length_scale": "lengthScale", "cluster_radius": "clusterRadius",
        "cluster_density": "clusterDensity",
    }
    for k, v in sub.items():
        if k == "cluster_mode":
            out["clusterMode"] = _enum(v)
        elif k in rename:
            out[rename[k]] = v
    return out


def _convert_canopy(canopy_cfg: dict) -> dict:
    """canopy -> IrisTreeCanopy (casing + recurse into branches/layers)."""
    out = {}
    if "start_angle" in canopy_cfg:
        out["startAngle"] = canopy_cfg["start_angle"]
    if "squish" in canopy_cfg:
        out["squish"] = canopy_cfg["squish"]
    if "mode" in canopy_cfg:
        out["mode"] = _enum(canopy_cfg["mode"])
    if "leaf_density" in canopy_cfg:
        out["leafDensity"] = canopy_cfg["leaf_density"]
    layers = canopy_cfg.get("layers")
    if isinstance(layers, list):
        out["layers"] = [
            {"yOffset": ly.get("y_offset", 0), "radius": ly.get("radius", 0)}
            for ly in layers
        ]
    branch_cfg = canopy_cfg.get("branches")
    if isinstance(branch_cfg, dict):
        out["branches"] = _convert_branches(branch_cfg)
    return out


def _convert_decorators(decorators: list) -> list:
    """decorators[] -> IrisTreeDecorator[] (casing only; palette/length left to Iris defaults)."""
    out = []
    for dec in decorators:
        d = {}
        if "target" in dec:
            d["target"] = _enum(dec["target"])
        if "block" in dec:
            d["block"] = _strip_state(dec["block"])
        if "chance" in dec:
            d["chance"] = dec["chance"]
        if "axis_aware" in dec:
            d["axisAware"] = dec["axis_aware"]
        out.append(d)
    return out


def _bake_preset_layers(out: dict, entry: dict) -> None:
    """Inject our canopy.py preset silhouette as explicit Iris ``layers``.

    Iris' native ``IrisProceduralTree`` has its OWN built-in per-profile layer
    fractions / radius scales, which are NOT the same numbers as our tuned
    ``canopy.py`` ``_PRESETS`` / ``_PROFILE_RADIUS_SCALE``. So if we convert a tree
    that relies on our presets and emit no ``layers``, Iris substitutes its own
    silhouette and the tree no longer matches what ``canopy.py`` would have grown.

    To stay faithful we reuse ``canopy.preset_layers`` directly (the same code path
    ``generate_canopy`` uses) and write the resulting ``(y_offset, radius)`` pairs as
    explicit Iris ``layers``. Two rules mirror ``generate_canopy`` exactly:
      * branch-driven trees place ONLY the top crown layer as volume (Iris' branch
        system builds the rest), so we keep just ``layers[-1]``;
      * the canopy ``mode`` defaults to our ``canopy.py`` default (``trimmed``) so the
        crown is solid (not the sparse ``density`` falloff).

    Layers are baked for a single representative height (``height_max``); callers that
    need per-height variety should emit one tree per fixed height (``heightMin ==
    heightMax``) so the absolute ``yOffset`` always caps that trunk (Iris places
    canopy ``yOffset`` absolutely from the tree base, it does not rescale it).
    """
    canopy_cfg = entry.get("canopy")
    if not isinstance(canopy_cfg, dict):
        return
    canopy_out = out.get("canopy")
    if not isinstance(canopy_out, dict) or "layers" in canopy_out:
        return  # explicit layers already won; don't override authored geometry
    # Lazy import: canopy.py lives beside this module in iris/tree-gen.
    from canopy import preset_layers
    profile = entry.get("profile", "oak")
    height = int(entry.get("height_max", entry.get("height_min", 8)))
    branch_driven = isinstance(canopy_cfg.get("branches"), dict)
    layers = preset_layers(profile, height, branch_driven=branch_driven)
    if branch_driven and layers:
        # Mirror generate_canopy: "crown_volume_fraction" keeps the upper
        # fraction of layers as a solid cone; otherwise only the top layer.
        crown_frac = canopy_cfg.get("crown_volume_fraction", None)
        if crown_frac is not None:
            n = max(1, int(round(len(layers) * float(crown_frac))))
            layers = layers[-n:]
        else:
            layers = [layers[-1]]
    canopy_out["layers"] = [
        {"yOffset": int(y_off), "radius": round(float(radius), 3)}
        for (y_off, radius) in layers
    ]
    if "mode" not in canopy_cfg:
        canopy_out["mode"] = "TRIMMED"


def convert_entry(entry: dict, warn, bake_layers: bool = False) -> dict:
    """Convert a single tree definition into an Iris 4.0 procedural-tree dict.

    When ``bake_layers`` is True and the entry has no explicit canopy ``layers``,
    the profile's ``canopy.py`` preset silhouette is baked in as explicit Iris
    ``layers`` (see ``_bake_preset_layers``) so Iris reproduces our tuned shape
    instead of falling back to its own internal presets.
    """
    skipped = [f for f in _IOB_ONLY_FIELDS if f in entry]
    if skipped:
        warn("entry '%s': dropped .iob-only field(s) with no procedural equivalent: %s"
             % (entry.get("name", "?"), ", ".join(skipped)))

    out = {}

    if "name" in entry:
        out["name"] = entry["name"]
    if "trunk" in entry:
        out["trunk"] = _strip_state(entry["trunk"])
    if "leaves" in entry:
        out["leaves"] = _strip_state(entry["leaves"])
    if "profile" in entry:
        out["profile"] = _enum(entry["profile"])
    if "height_min" in entry:
        out["heightMin"] = entry["height_min"]
    if "height_max" in entry:
        out["heightMax"] = entry["height_max"]
    if "count" in entry:
        out["variants"] = entry["count"]
    if "seed" in entry:
        out["seed"] = entry["seed"]
    # Roots. Our offline `roots` flag drove an .iob-only *underground* bridging
    # structure (see iris/tree-gen/roots.py): a taproot + buttress legs placed at
    # y < 0 that stay buried on flat ground and only surface to connect the trunk
    # to the ground on uneven terrain. It is NOT the same concept as Iris 4.0's
    # *visible* procedural root system (rootStyle BUTTRESS/TAPROOT/STILT), which
    # Iris turns on by default (`roots = true`). Passing our flag straight through
    # made every converted tree sprout unwanted, decorative buttress roots -- the
    # "unnecessary roots" regression. There is no procedural equivalent of the
    # subtle underground bridging, so we disable Iris' procedural roots by default
    # and only enable them when a config explicitly opts in via `iris_roots`.
    out["roots"] = bool(entry.get("iris_roots", False))
    if out["roots"]:
        if "iris_root_style" in entry:
            out["rootStyle"] = _enum(entry["iris_root_style"])
        if "iris_root_depth" in entry:
            out["rootDepth"] = entry["iris_root_depth"]
        if "iris_root_flare" in entry:
            out["rootFlare"] = entry["iris_root_flare"]

    # Trunk shaping
    if "trunk_width" in entry:
        out["trunkWidth"] = entry["trunk_width"]
    if "trunk_shape" in entry:
        out["trunkShape"] = _enum(entry["trunk_shape"])
    _flatten_shape(out, entry.get("trunk_shape_params", {}))
    if "lean_angle" in entry:
        out["leanAngle"] = entry["lean_angle"]
    if "lean_azimuth" in entry:
        out["leanAzimuth"] = entry["lean_azimuth"]
    if "lean_azimuth_fn" in entry:
        out["leanAzimuthMode"] = _enum(entry["lean_azimuth_fn"])
    _flatten_azimuth(out, entry.get("lean_azimuth_params", {}))
    if "trunk_curve_fn" in entry:
        out["trunkCurve"] = _enum(entry["trunk_curve_fn"])
    curve_params = entry.get("trunk_curve_params", {})
    if "steepness" in curve_params:
        out["curveSteepness"] = curve_params["steepness"]

    # Secondary trunk
    if "secondary_trunk" in entry:
        out["secondaryTrunk"] = _strip_state(entry["secondary_trunk"])
    if "secondary_trunk_start" in entry:
        out["secondaryTrunkStart"] = entry["secondary_trunk_start"]
    if "secondary_trunk_end" in entry:
        out["secondaryTrunkEnd"] = entry["secondary_trunk_end"]

    # Secondary leaves: string -> secondaryLeaves; list -> weightedSecondaryLeaves
    sl = entry.get("secondary_leaves")
    if isinstance(sl, str):
        out["secondaryLeaves"] = _strip_state(sl)
    elif isinstance(sl, list):
        out["weightedSecondaryLeaves"] = [
            {"block": _strip_state(e.get("block", "")), "weight": e.get("weight", 1)}
            for e in sl
        ]
    if "secondary_leaf_fraction" in entry:
        out["secondaryLeafFraction"] = entry["secondary_leaf_fraction"]

    # Canopy + decorators
    canopy_cfg = entry.get("canopy")
    if isinstance(canopy_cfg, dict):
        out["canopy"] = _convert_canopy(canopy_cfg)
    decorators = entry.get("decorators")
    if isinstance(decorators, list):
        out["decorators"] = _convert_decorators(decorators)

    if bake_layers:
        _bake_preset_layers(out, entry)

    return out


def parse_args():
    """Define and parse the command-line flags (--config, --out)."""
    parser = argparse.ArgumentParser(
        description="Convert tree-gen configs into Iris 4.0 procedural-tree JSON."
    )
    parser.add_argument(
        "--config", required=True,
        help="Path to a JSON config file containing a flat list of tree definitions."
    )
    parser.add_argument(
        "--out", default=None,
        help="Output file path. Defaults to '<config-stem>.procedural.json' next to the config."
    )
    return parser.parse_args()


def main():
    """Read a config, convert every entry, and write the procedural-tree JSON array."""
    args = parse_args()

    with open(args.config, "r", encoding="utf-8") as f:
        entries = json.load(f)

    if not isinstance(entries, list):
        print("ERROR: config file must contain a JSON array of tree definitions.", file=sys.stderr)
        sys.exit(1)

    def warn(msg):
        print("  WARN: %s" % msg, file=sys.stderr)

    converted = [convert_entry(e, warn) for e in entries]

    out_path = args.out
    if not out_path:
        config_dir = os.path.dirname(os.path.abspath(args.config))
        stem = os.path.splitext(os.path.basename(args.config))[0]
        out_path = os.path.join(config_dir, "%s.procedural.json" % stem)

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(converted, f, indent=2)
        f.write("\n")

    print("Converted %d tree definition(s) -> %s" % (len(converted), out_path))


if __name__ == "__main__":
    main()
