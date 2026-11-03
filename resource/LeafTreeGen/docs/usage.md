# LeafTreeGen - Usage Guide

Procedural, biome-aware custom trees that generate through vanilla worldgen.

This file is bundled inside the JAR as `META-INF/leaftreegen/usage.md` so it travels with the plugin even when the project wiki or Modrinth page is offline.

---

## Requirements

- Java 21+ (Java 25 on Minecraft 26.x)
- Paper or Folia 1.21+, **or** Fabric 26.1+ / NeoForge 26.1+
- Zero external plugin dependencies (`commands-api`, `server-api`, `yaml-api`, and `predict-api` are bundled inside `LeafTreeGen-<version>.jar`)

---

## Installation

1. Drop `LeafTreeGen-<version>.jar` into your `plugins/` (Paper/Folia) or `mods/` (Fabric/NeoForge) folder.
2. Start the server once to generate `config.yml`, the default `species/` folder, and `datapacks/leaf-treegen-generated/`.
3. Edit `config.yml` and the species JSON files to taste, then run `/leaftree reload`.

---

## Commands

All administrative commands require `leaftreegen.admin` (`op` by default); planting custom saplings requires `leaftreegen.sapling` (`true` by default).

| Command | What it does |
|---|---|
| `/leaftree generate species=<id> [world=<name>] [x=<~\|n>] [y=<~\|n>] [z=<~\|n>]` | Places a tree at your cursor position or target coordinates. Used by admins to test a species in-world without waiting for worldgen. |
| `/leaftree give species=<id> [player=<name>] [amount=<1..64>] [variant=<key>]` | Gives tagged saplings for the named species (random or pinned to a specific variant) to a player (defaults to yourself, amount defaults to 1). |
| `/leaftree list [species=<id>]` | Lists all registered species. If `species` is given, lists the variants for that species. |
| `/leaftree reload` | Re-reads `config.yml` and all species JSON files, rebakes NBT variants, and regenerates the datapack. Run this after any config or species change. |
| `/leaftree debug` | Dumps internal state (active species, generation mode, loaded worlds, and per-species biome/worldgen status). |
| `/leaftree stats [reset=<true\|false>]` | Prints or resets `TreeBlockBatcher` tick-budget statistics (blocks placed, total time spent, batches, and budget-exceeded count). |

### Command parameters

| Parameter | Type | Description |
|---|---|---|
| `species` | string | The species ID as it appears in the species JSON `name` field (e.g. `glacierpine`, `dark-forest`). Case-insensitive. |
| `player` | string | An online player name. Defaults to the command sender when omitted. |
| `amount` | integer | How many sapling items to give (`1..64`). Defaults to 1. |
| `variant` | string | Optional specific variant or child definition key to bind to the sapling item. |
| `world` / `x` / `y` / `z` | string / coordinate | Optional target world and absolute or relative (`~`, `~n`) coordinates for `/leaftree generate`. |
| `reset` | boolean | When `true` on `/leaftree stats reset=true`, resets accumulated block-batcher counters. |

---

## config.yml

`config.yml` lives in the plugin data folder. Every key is optional; the default value is shown in the example below.

```yaml
generation-mode: DATAPACK

time-allocation-ms: 5.0
max-blocks-per-tick: 1000
sapling-drop-chance: 0.05
water-growth-trees:
  - swamp

datapack:
  name: leaf-treegen-generated
  description: LeafTreeGen generated worldgen.

placement:
  glacierpine:
    biomes:
      - minecraft:snowy_taiga
      - minecraft:grove
    spacing: 6
    separation: 3
```

### Top-level keys

| Key | Type | Default | Plain-language meaning |
|---|---|---|---|
| `generation-mode` | string | `DATAPACK` | Controls how trees are placed in the world. See the mode table below. |
| `time-allocation-ms` | decimal | `5.0` | Maximum milliseconds per server tick that LeafTreeGen may spend on procedural tree generation. Lower values reduce lag spikes; higher values speed up generation. |
| `max-blocks-per-tick` | integer | `1000` | Hard cap on how many blocks a single procedural tree placement may set in one tick. Prevents runaway lag from very large trees. |
| `sapling-drop-chance` | decimal | `0.05` | Probability (0.0-1.0) that a leaf block drops a custom sapling when broken. `0.05` means a 5% chance. |
| `water-growth-trees` | list of strings | `[swamp]` | Species IDs whose saplings are allowed to grow when placed in or next to water. All other species require dry land. |

### `datapack` section

| Key | Type | Default | Plain-language meaning |
|---|---|---|---|
| `datapack.name` | string | `leaf-treegen-generated` | Internal name of the generated datapack folder written to the world's `datapacks/` directory. Change this only if you need to avoid a name collision with another datapack. |
| `datapack.description` | string | `LeafTreeGen generated worldgen.` | Human-readable description shown in `/datapack list`. |

### `generation-mode` values

| Value | Behaviour |
|---|---|
| `DATAPACK` | Trees are baked into vanilla `.nbt` structure templates (`425` active / `829` total variants across `26` `structure_set`s) and placed via jigsaw worldgen (`0 ms` plugin batcher time; adds `+13 to +20 s` to cold server boot while Minecraft indexes the structures). **Default (recommended on Paper & Folia).** |
| `PROCEDURAL` | Trees are generated at runtime as chunks load and paced across `TreeBlockBatcher` (`<= 5.0 ms/tick`, `[0..15, 0..15]` chunk slicing). Ties `DATAPACK` generation speed on Paper/Folia with vanilla-speed server startup, and runs **`1.7x to 3.5x` faster than `DATAPACK` on Fabric & NeoForge** by avoiding vanilla `ServerChunkCache` structure-reference neighbor cascades. |
| `BOTH` | Both pipelines run simultaneously. Useful when comparing datapack output against procedural output during development. |
| `NONE` | All automatic tree generation is disabled (and any existing `datapacks/leaf-treegen-generated/` folder is removed on startup). |

### `placement` section

The `placement` section lets you override per-species worldgen settings without editing the species JSON files. Each key under `placement` is a species ID.

| Key | Type | Default | Plain-language meaning |
|---|---|---|---|
| `biomes` | list of strings | (from species JSON) | Biome IDs where this species may generate. Accepts short names (`snowy_taiga`) or namespaced IDs (`minecraft:snowy_taiga`). Replaces the species default entirely when present. |
| `worldgen` | boolean | `true` when biomes are set | Whether this species participates in worldgen at all. Set to `false` to disable a species without removing its JSON file. |
| `replace-vanilla` | boolean | (from species JSON) | When `true`, LeafTreeGen suppresses vanilla tree features in the same biomes so only custom trees appear. |
| `spacing` | integer | (from species JSON) | Minimum chunk distance between two placements of this species. Larger values = sparser trees. |
| `separation` | integer | (from species JSON) | Minimum chunk distance between a placement of this species and any other structure. Must be less than `spacing`. |
| `salt` | integer | (auto) | Random seed offset used to decorrelate this species' placement grid from other species. Leave unset to use an automatic value derived from the species ID. |
| `spread-type` | string | `linear` | Algorithm used to scatter placements within the spacing grid cell. `linear` is the standard vanilla-style spread. |
| `step` | string | `surface_structures` | Worldgen step at which the structure is injected. Matches vanilla worldgen step names. Change only if you know what you are doing. |
| `weight` | integer | `1` | Relative selection weight when multiple species compete for the same placement slot. Higher = more likely to be chosen. |
| `trees` | map of id to weight | (from species JSON) | Weighted list of child species IDs to draw from when this entry acts as a group. Example: `birch: 3` means birch is three times as likely as a species with weight 1. |
| `trees-per-placement` | integer | (from species JSON) | When `feature-trees` is also set, how many vanilla tree features to scatter per structure placement. Produces vanilla-like density (e.g. old-growth pine taiga). |
| `feature-trees` | list of strings | (from species JSON) | Vanilla configured-feature IDs (e.g. `minecraft:mega_pine`) to use for density placement. Each entry may optionally end with a space and an integer weight (e.g. `minecraft:mega_pine 3`). |
| `definitions` | map | (from species JSON) | Per-variant weight overrides. Keys are variant definition names; values are integer weights. |

---

## Species JSON

Species files live in `species/<id>.json` (or `species/procedural_<id>.json` for procedural variants). Each file is a JSON array; each element describes one size tier or variant of the species.

### Top-level fields per variant object

| Field | Type | Required | Plain-language meaning |
|---|---|---|---|
| `name` | string | yes | Species ID this variant belongs to. Must match the filename stem and the ID used in `config.yml`. |
| `comment` | string | no | Human-readable note. Ignored by the engine; use it to describe the tier (e.g. "small frost pine"). |
| `filenames` | list of strings | no | NBT structure file names (without `.nbt`) that were pre-baked for this variant. Used by the datapack pipeline. |
| `trunk` | string | yes | Block ID for the trunk (e.g. `minecraft:spruce_log`). |
| `leaves` | string | yes | Block ID for the primary leaf layer (e.g. `minecraft:spruce_leaves`). |
| `secondary_leaves` | string | no | Block ID mixed into the canopy alongside the primary leaves (e.g. `minecraft:snow_block` for a snowy cap). |
| `secondary_leaf_fraction` | decimal | no | Fraction of canopy positions that use `secondary_leaves` instead of `leaves`. `0.15` means 15%. |
| `profile` | string | yes | Overall silhouette shape. Controls how the canopy layers are distributed vertically. Common values: `SPRUCE`, `OAK`, `BIRCH`, `JUNGLE`, `ACACIA`, `DARK_OAK`. |
| `height_min` | integer | yes | Minimum trunk height in blocks. |
| `height_max` | integer | yes | Maximum trunk height in blocks. The engine picks a random value in this range for each placed tree. |
| `seed` | integer | no | Random seed used when baking NBT variants. Different seeds produce different structural shapes for the same parameters. |
| `count` | integer | no | Number of distinct NBT structure files to bake for this tier. More variants = more visual variety in the world. Does not affect how often this tier is chosen. |
| `trunk_width` | integer | no | Radius of the trunk in blocks. `1` = single-block trunk; `2` = 2x2 trunk; `3` = 3x3 trunk. |
| `trunk_shape` | string | no | How the trunk width changes from base to top. Values: `CONSTANT` (same width all the way up), `LINEAR` (tapers linearly using `trunk_shape_params.start` and `trunk_shape_params.end`), `LOG` (logarithmic taper using `trunk_shape_params.base`). |
| `trunk_shape_params` | object | no | Parameters for the chosen `trunk_shape`. See trunk shape params table below. |
| `weight` | integer | no | Relative weight of this variant when the engine randomly selects among tiers. Higher = chosen more often. |

### `trunk_shape_params` fields

| Field | Used by | Plain-language meaning |
|---|---|---|
| `start` | `LINEAR` | Width multiplier at the base of the trunk (e.g. `1.0` = full `trunk_width`). |
| `end` | `LINEAR` | Width multiplier at the top of the trunk (e.g. `0.65` = 65% of `trunk_width`). |
| `base` | `LOG` | Logarithm base controlling how quickly the trunk narrows. Higher = slower taper. |

### `canopy` object

| Field | Type | Default | Plain-language meaning |
|---|---|---|---|
| `mode` | string | `TRIMMED` | Leaf fill algorithm. `TRIMMED` removes corner blocks for a rounder look; `FULL` fills the entire bounding sphere; `SPARSE` randomly thins the canopy. |
| `leaf_density` | decimal | `1.0` | Fraction of eligible canopy positions that actually receive a leaf block. `0.88` means 88% fill. |
| `start_angle` | integer | (profile default) | Angle in degrees from vertical at which the canopy cone begins. Smaller = narrower/taller cone (spruce-like); larger = wider/flatter cone. |
| `squish` | decimal | `1.0` | Vertical scale factor applied to the canopy sphere. Values below 1.0 flatten the canopy; values above 1.0 stretch it taller. |
| `layers` | list | (auto) | Explicit list of `{yOffset, radius}` objects that define each horizontal leaf ring. When present, overrides the profile-derived layer calculation. |
| `volume_layers` | boolean | `false` | When `true`, fills the interior of the canopy volume rather than just the surface shell. Produces denser, more opaque canopies. |
| `leaf_vertical_scale` | decimal | `1.0` | Additional vertical stretch applied to individual leaf layers. |
| `crown_volume_fraction` | decimal | (none) | Fraction of the canopy height that is treated as the dense crown core. Leaf density is higher inside this fraction. |
| `secondary_leaves` | string | (top-level) | Overrides the top-level `secondary_leaves` block for this canopy specifically. |
| `secondary_fraction` | decimal | (top-level) | Overrides the top-level `secondary_leaf_fraction` for this canopy specifically. |
| `underside_leaves` | string | no | Block placed on the underside (bottom layer) of the canopy, e.g. a glow block for bioluminescent trees. |
| `underside_chance` | decimal | `0.0` | Per-block probability (0.0-1.0) that a bottom-layer leaf is replaced with `underside_leaves`. |
| `branches` | object | (none) | Defines lateral branches growing from the trunk into the canopy. See branches table below. |

### `canopy.branches` object

| Field | Type | Plain-language meaning |
|---|---|---|
| `count` | integer | How many lateral branches to attempt to grow per tree. |
| `prob_fn` | string | Function that controls whether each candidate branch position actually spawns a branch. `CONSTANT` uses a fixed probability; `LINEAR` varies probability with height. |
| `prob_params` | object | Parameters for `prob_fn`. For `CONSTANT`: `p` (probability 0-1). For `LINEAR`: `base` and `crown` probabilities at bottom and top of the branch zone. |
| `length_fn` | string | Function controlling branch length. `CONSTANT` = fixed length; `LINEAR` = varies from base to crown. |
| `length_params` | object | Parameters for `length_fn`. For `LINEAR`: `base` (length at bottom) and `crown` (length at top). |
| `azimuth` | string | Direction branches point horizontally. `RANDOM` = any direction; `SPIRAL` = evenly spaced around the trunk. |
| `elevation` | decimal | Vertical angle of branches in degrees. `0` = horizontal; negative = drooping; positive = upswept. |
| `leaf_start_up` | boolean | When `true`, leaf clusters start above the branch tip rather than at it. |
| `cluster_radius` | integer | Radius of the leaf cluster placed at each branch tip. |
| `cluster_mode` | string | Fill algorithm for branch-tip leaf clusters. Same values as canopy `mode`. |
| `cluster_density` | decimal | Leaf fill fraction for branch-tip clusters (0.0-1.0). |
| `min_length` / `max_length` | decimal | Minimum and maximum branch length in blocks (alternative to `length_fn`). |
| `min_elevation` / `max_elevation` | decimal | Elevation angle range in degrees when branches vary in angle. |
| `spacing` | decimal | Minimum angular spacing between branches around the trunk (degrees). |
| `start_height` | decimal | Fraction of trunk height at which branches begin (0.0 = base, 1.0 = top). |
| `sub_branches` | object | Smaller branches that grow off each main lateral branch. See sub-branches table below. |

### `canopy.branches.sub_branches` object

| Field | Type | Plain-language meaning |
|---|---|---|
| `count` | integer | Number of sub-branches per main branch. |
| `pitch_delta` | decimal | Vertical angle offset of sub-branches relative to the parent branch (degrees). Negative = drooping. |
| `yaw_delta` | decimal | Horizontal angle spread between sub-branches (degrees). |
| `length_scale` | decimal | Sub-branch length as a fraction of the parent branch length. `0.5` = half as long. |
| `cluster_radius` | integer | Leaf cluster radius at sub-branch tips. |
| `cluster_mode` | string | Fill algorithm for sub-branch leaf clusters. |
| `cluster_density` | decimal | Leaf fill fraction for sub-branch clusters (0.0-1.0). |

### `decorators` array

Each entry in `decorators` places accent blocks after the trunk and canopy are built.

| Field | Type | Plain-language meaning |
|---|---|---|
| `target` | string | Where to attempt placement. Values: `canopy_top` (top surface of the canopy), `canopy_bottom` (underside of the canopy), `branch_tip` (end of each lateral branch), `trunk_surface` (exposed sides of the trunk), `trunk_base` (blocks around the base of the trunk). |
| `block` | string | Block ID to place, including optional block state (e.g. `minecraft:snow[layers=2]`, `minecraft:pointed_dripstone[vertical_direction=down,thickness=tip]`). |
| `chance` | decimal | Per-candidate probability (0.0-1.0) that the block is actually placed. `0.65` means 65% of eligible positions receive the block. |
| `axis_aware` | boolean | When `true`, the block's `facing` property is set to point away from the trunk, so blocks like vines or mushrooms orient themselves naturally. |

---

## Species Reference

| Category | Species IDs |
|---|---|
| Temperate | `forest`, `birch`, `birch-forest`, `old-growth-birch-forest`, `maple-birch-giants`, `ashwood` |
| Tropical | `jungle`, `cherry-grove`, `jacaranda-grove` |
| Cold | `taiga`, `old-growth-pine-taiga`, `glacierpine` |
| Dark & Exotic | `dark-forest`, `dark-forest-giants`, `spiral-crown-forest`, `rainbow-grove`, `cloudcap` |
| Savanna | `savanna` |
| Special / Hostile | `blightroot`, `embervine`, `glowcap`, `lavatree`, `volcano` |

`expanded_*` variants (e.g. `expanded_forest`) are richer multi-tier group species that reference the base species internally.

---

## Building from Source

```powershell
# Paper plugin JAR only
./gradlew :paper:jar

# Unified JAR (all platforms, includes LICENSE and this file)
./gradlew jar
```

Output lands in `build/libs/` (unified) or `plugins/leaf-treegen/paper/build/libs/` (Paper-only).

---

## License

See `META-INF/leaftreegen/LICENSE` inside this JAR, or the `LICENSE` file at the repository root.
