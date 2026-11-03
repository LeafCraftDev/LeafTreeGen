# Vanilla Biome Tree Map

Iconic tree shapes assigned to each vanilla biome for the demo world. Every
species listed here ships as a predefined config in
`common/src/main/resources/species/*.json` and is placed via the `placement:`
section of `config.yml`.

## How to read this table

- "Species" is the species id (its JSON file under `species/`).
- "Iconic shape" summarizes the look authored in that species' config.
- "Role" marks whether a species is the common biome tree or a rarer landmark.

## Forest and plains

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `forest` | `forest` | Rounded oak, biased toward mid and large | Primary
 `flower_forest` | `oak-small-mid` | Small-to-mid rounded oak, sporadic | Primary
 `plains` | `oak-small-mid` | Sparse small-to-mid oak (oak only) | Primary
 `meadow` | `oak-small-mid` | Sparse small-to-mid oak (oak only) | Primary
 `meadow` | `meadow-azalea` | Small oak-log trunk with azalea / flowering-azalea crown | Sprinkled accent
 `meadow` | `rainbow-grove` | Birch trunk with a mixed multi-hue leaf canopy | Rare landmark
 `dark_forest` | `glowcap` | Lit brown-mushroom shrub emitting shroomlight; spaced wide so it only occasionally dots the otherwise dark floor | Understory
 `dark_forest` | `dark-forest-floor-lights` | Tiny shroomlight-lit glow shrooms / nubs (height 1-3) littered across the floor | Floor litter
 `dark_forest` | `dark-forest-mids` | Small-to-medium and twisted dark oak below the tall canopy | Midstory
 `dark_forest` | `dark-forest-megas` | Titan dark oak with a massive overlapping canopy | Canopy
 `birch_forest` | `birch-forest` | Tall slender white birch | Primary
 `old_growth_birch_forest` | `old-growth-birch-forest` | Towering old-growth birch | Primary
 `old_growth_birch_forest` | `maple-birch-giants` | Giant maple-and-birch landmark | Rare landmark

## Taiga and snowy

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `taiga` | `taiga` | Conical spruce | Primary
 `snowy_taiga` | `evergreen` | Snow-dusted conical spruce in two sizes (a medium and a towering tall) using a volume-layer crown (no branches) so the cone fills from the base rather than perching a small branch hat on a bare tapering trunk | Primary
 `snowy_taiga` | `christmas-tree` | Festive decorated spruce: colored-glass ornaments and glowing shroomlight / sea-lantern / multi-color froglight / glowstone baubles plus glowing end-rod icicles on the branch tips (no top star: `canopy_top` caps every column, not just the apex) | Very rare landmark
 `old_growth_pine_taiga` | `old-growth-pine-taiga` | Tall bare-trunked pine | Primary
 `old_growth_spruce_taiga` | `old-growth-pine-taiga` | Tall bare-trunked pine | Primary
 `snowy_plains` | `glacierpine` | Frost-laden spruce with snow caps and dripstone + glowing end-rod icicle tips | Primary
 `ice_spikes` | `glacierpine` | Frost-laden spruce with snow caps and dripstone + glowing end-rod icicle tips | Primary
 `grove` | `glacierpine` | Frost-laden spruce with snow caps and dripstone + glowing end-rod icicle tips | Primary
 `snowy_slopes` | `glacierpine` | Frost-laden spruce with snow caps and dripstone + glowing end-rod icicle tips | Sparse
 `frozen_peaks` | `glacierpine` | Frost-laden spruce with snow caps and dripstone + glowing end-rod icicle tips | Sparse
 `jagged_peaks` | `glacierpine` | Frost-laden spruce with snow caps and dripstone + glowing end-rod icicle tips | Sparse

## Jungle

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `jungle` | `jungle` | Jungle trees spanning small to giant | Primary
 `sparse_jungle` | `jungle` | Jungle trees spanning small to giant | Primary
 `bamboo_jungle` | `bamboo-jungle` | Jungle trees ringed by bamboo shoots at the base | Primary

## Savanna

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `savanna` | `savanna` | Flat-topped leaning acacia | Primary
 `savanna_plateau` | `savanna` | Flat-topped leaning acacia | Primary
 `windswept_savanna` | `savanna` | Flat-topped leaning acacia | Primary

## Cherry

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `cherry_grove` | `cherry-grove` | Layered pink blossom crown | Primary
 `cherry_grove` | `jacaranda-grove` | Giant weeping cherry-wood jacaranda with petal litter | Rare landmark

## Windswept

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `windswept_hills` | `ashwood` | Gaunt gray pale-oak dead tree with cobweb tips and glow lichen | Primary
 `windswept_forest` | `ashwood` | Gaunt gray pale-oak dead tree with cobweb tips and glow lichen | Primary
 `windswept_gravelly_hills` | `ashwood` | Gaunt gray pale-oak dead tree with cobweb tips and glow lichen | Primary

## Swamp

 Biome | Species | Iconic shape | Role
-------|---------|--------------|-----
 `swamp` | `swamp` | Vine-draped weeping oak + mangrove-wood trees | Primary
 `mangrove_swamp` | `swamp` | Vine-draped weeping oak + mangrove-wood trees | Primary

## Not mapped to vanilla biomes

These predefined species are intentionally left out of the demo biome map:

- `blightroot`, `cloudcap` - cave / deep-dark species; caves are being
  handled separately, so they are not part of the current demo placement.
  (`glowcap` is the exception: it now also lights the `dark_forest` understory,
  see the Forest and plains table above.)
- `embervine` - volcanic magma-and-shroomlight jungle tree; reserved for custom
  volcanic biomes, not a vanilla fit.
- `lavatree` - DEPRECATED obsidian-encased lava object, superseded by the Iris
  4.0 cave carver. Do not wire into new biomes.

## Notes and pending work

- Birch: the `birch-forest` small variants and the one branchless
  `old-growth-birch-forest` variant now grow short top branches, so birches
  are no longer bare single-block trunks.
- Ashwood: re-themed from basalt/soul-fire (read as lava) to gray
  `pale_oak_log` wood with cobweb tips and glow lichen. `pale_oak_log` /
  `pale_oak_leaves` require a Minecraft version with the Pale Garden content
  (1.21.4+); on older servers swap the trunk block in `ashwood.json`.
- Savanna is intentionally left as-is: the `savanna` species already varies
  across small, medium, tall, and wide-umbrella acacia scales.
- Cherry grove: capped to a rare ~60-block tallest landmark (`cherry giant B`,
  `count: 3`) with wider trunks (`trunk_width: 5`) so the height-to-trunk ratio
  reads correctly; the other tiers top out around 44-54.
- Dark forest is layered into three stacked canopies: `glowcap` understory,
  `dark-forest-mids` midstory, and `dark-forest-megas` tall spiral canopy, so
  dark oak height tiers never overlap. The tall spiral canopy is the densest
  layer (`spacing: 1` / `separation: 0`, the maximum jigsaw density, attempting
  a placement in every chunk so the spiral trees are placed as often as
  possible), keeping the forest floor dark, while
  the `glowcap` understory is spaced wide (`spacing: 12` / `separation: 6`) so
  the glowing shrooms appear only occasionally across the floor. The floor is
  further littered with the new `dark-forest-floor-lights` species (tiny
  height 1-3 shroomlight glowsprouts/glowtuft) placed additively at
  `spacing: 4` / `separation: 2`, so small flecks of light occasionally dot the
  otherwise dark ground. To darken the
  floor further, the spiral mega definitions (`spiral_mega_A`, `spiral_mega_B`,
  `spiral_mega` in `dark-forest-megas.json`) were given thicker crowns: wider
  reach (`cluster_radius` 5 -> 7, longer branch `crown`), taller fill (`squish`
  ~0.48-0.5, `leaf-vertical-scale` 0.45 -> 0.6), and a lower `top_heavy`
  exponent plus more sub-branches so the canopy fills in lower and casts denser
  shade.
- Caves and the deep dark are being handled separately, so the cave species
  `blightroot` and `cloudcap` are not part of this demo placement (`glowcap`
  is reused above as the dark forest understory).
- Rainbow grove implies per-biome color modification; that work is deferred to
  a future session, so the existing `rainbow-grove` placement is unchanged.
- Density: the primary biome species were still far too sparse (swamp trees in
  particular barely appeared), so the dense biomes (`forest`, `birch_forest`,
  `old_growth_birch_forest`, `taiga`, `snowy_taiga`, `old_growth_pine_taiga`,
  `jungle`, `bamboo_jungle`, `cherry_grove`, `swamp`) were tightened to
  `spacing: 3` / `separation: 1` in `config.yml`. The lighter biomes
  (`flower_forest`, `savanna`, `ashwood`) use `spacing: 5` / `separation: 2`,
  `glacierpine` (snowy biomes) uses `spacing: 6` / `separation: 3`, and the
  intentionally-sparse oak biomes (`plains`, `meadow`) use `spacing: 8` /
  `separation: 4`. The rare landmarks (`jacaranda-grove`, `maple-birch-giants`,
  `rainbow-grove`) keep their wide spacing.
- Spruce shape: the tallest spruce variant ("spruce mega B") in both `taiga`
  and `old-growth-pine-taiga` used a `parabolic` trunk shape with
  `peak_offset: 0.1`, which makes the trunk widest at the very top. Since the
  conical spruce canopy tapers to a point near the top, that fat trunk top was
  left poking out into the air (and read as a leaning dark-oak titan). Both were
  switched to a `log` taper so the trunk narrows toward the top and stays
  covered by the crown.
- Tree height caps: outside the dark forest (`dark-forest-*`) and the dense
  taigas (`taiga`, `old-growth-pine-taiga`), giant trees were shrunk so they no
  longer tower over the rest of the world. Most non-exempt species now top out
  around 48 blocks (`forest` ancient oaks, `old-growth-birch-forest` giant B,
  `jacaranda-grove` giant D, the tall `savanna` acacia), and the tallest tiers
  had their counts/crowns reduced so they stay rare and in proportion. Cherry
  grove keeps a rare ~60-block landmark (see above). Jungle is treated as an
  emergent-canopy biome: its common tiers sit around 42-52, but it keeps a rare
  emergent giant (`jungle tall B` ~62, `serpentine jungle B` ~60, `count: 3`,
  `trunk_width: 5`) so the iconic giant jungle trees still rise above the dense
  surrounding canopy. The dark-forest spiral megas and taiga/pine spruces are
  intentionally left tall.
- Jungle giant crowns: the emergent giants (`jungle tall A`, `jungle tall B`)
  were re-tuned so they read as scaled-up jungle trees rather than a thin, flat
  dark-oak umbrella. Their crowns were lifted into a lush rounded dome instead
  of a wide flat sprawl: `leaf_density` raised (0.72-0.74 -> 0.88-0.9), `squish`
  raised (0.5-0.52 -> 0.7-0.72), `start_angle` raised (126-128 -> 136-138),
  branch/sub-branch `cluster_mode` switched from sparse `noise` to dense
  `density`, branch `elevation` steepened (10-12 -> 32-34), and branch
  `max_len` shortened (13/11 -> 9/8) so the canopy fills in densely at the top
  of the tall trunk.
- Vanilla trees: every primary biome placement now sets `replace-vanilla: true`,
  so the generated datapack writes placed-feature overrides that suppress the
  vanilla tree features in those biomes (the datapack approach). Landmark
  overlays and cave species are left additive (no suppression).
