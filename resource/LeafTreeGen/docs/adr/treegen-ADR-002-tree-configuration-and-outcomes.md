# treegen-ADR-002 - Tree Configuration: Data-Driven Definitions and Biome Outcomes

**Status:** Accepted
**Date:** 2026-06-11

## Context

leaf-treegen generates custom trees from data-driven JSON definitions rather than
hand-built schematics. Two related concerns had to be settled:

1. **How a tree is described.** Authors need a single, declarative way to specify a
   tree's trunk, canopy, branches, roots, and accent blocks that is deterministic,
   tool-agnostic, and reusable across every distribution target (Iris `.iob`,
   schematic `.schem`, and vanilla structure `.nbt`).
2. **How those trees are mapped to the world.** The demo world must give every
   relevant vanilla biome an iconic, recognizable tree silhouette, balance density
   so primary species actually appear, keep giant trees in proportion, and decide
   when to suppress vanilla trees versus layer on top of them.

Constraints and forces surfaced during the work so far:

- Generation must be **deterministic**: the same config plus `seed` always yields
  identical output, so baked structures are reproducible and reviewable.
- The same source definition must feed multiple output formats without re-authoring
  (one config -> `.iob` / `.schem` / `.nbt`, and convertible to Iris 4.0
  `IrisProceduralTree` via `to_procedural.py`).
- Authoring should favor copy-and-tune over bespoke code: an author copies an
  existing config in `tools/tree-gen/configs/` and adjusts fields.
- Predefined species shipped to the plugin live separately, under
  `common/src/main/resources/species/*.json`, and are placed via `config.yml`.
- Biome assignments must read as natural: correct height-to-trunk ratios, layered
  canopies that do not overlap, and species sparsity tuned per biome.

Prior art / discussion: this configuration model and the biome map were converged
through an extended issue thread; the per-biome rationale lives in
`docs/biome-trees.md`. The ADR concept and template follow the existing
`docs/adr/` convention (see ADR-001).

## Decision

Adopt a **flat-array JSON config schema** consumed by the offline `tree-gen`
generator as the single source of truth for tree shape, and a **per-biome
placement map** as the single source of truth for where baked species appear.

Configuration model:

1. **One config = a JSON array of tree definitions.** Each element fully describes
   one tree: trunk block/leaf block, `profile` preset, `height_min`/`height_max`,
   `seed`, `count`, trunk shaping (`trunk_width`, `trunk_shape`,
   `trunk_shape_params`, `trunk_round`), a nested `canopy` (density/volume plus a
   `branches` system with `prob_fn`, `length_fn`, azimuth/elevation, clustering,
   and `sub_branches`), optional `roots`, `decorators`, and `.iob`-only
   post-processing (`encase`, `invert_y`, `clear_cone`).
2. **Deterministic, multi-format output.** `generate_tree.py` writes `.iob`,
   `.schem`, and/or `.nbt` from the same definition; `to_procedural.py` recases the
   same config into Iris 4.0's `IrisProceduralTree` format, reporting `.iob`-only
   post-processing fields as skipped (no procedural equivalent).
3. **Two config locations with distinct roles.** Authoring/experimental configs
   live in `tools/tree-gen/configs/`; the curated set of predefined species shipped
   to the plugin lives in `common/src/main/resources/species/`. The biome demo map
   draws only from the shipped species set.
4. **Per-biome placement map** (documented in `docs/biome-trees.md`, applied in
   `config.yml`): each vanilla biome is assigned a primary species plus optional
   rarer landmark/understory overlays, each with a `Role` (Primary, Understory,
   Midstory, Canopy, Floor litter, Sprinkled accent, Rare landmark).
5. **Density tuned per biome, not globally.** Dense biomes use `spacing: 3` /
   `separation: 1`; lighter biomes `spacing: 5` / `separation: 2`; snowy
   glacierpine `spacing: 6` / `separation: 3`; intentionally sparse oak biomes
   `spacing: 8` / `separation: 4`; rare landmarks keep wide spacing. The dark forest
   spiral canopy uses the maximum jigsaw density (`spacing: 1` / `separation: 0`).
6. **Layered canopies by height tier.** The dark forest is split into stacked,
   non-overlapping layers: `glowcap` understory, `dark-forest-floor-lights` floor
   litter, `dark-forest-mids` midstory, and `dark-forest-megas` tall spiral canopy.
7. **Proportional height caps.** Outside the dark forest and the dense taigas,
   giant trees were shrunk to top out around 48 blocks, with the tallest tiers made
   rare; cherry grove keeps a rare ~60-block landmark; jungle keeps a rare emergent
   giant. Trunk widths were raised on the tallest tiers so height-to-trunk ratios
   read correctly.
8. **Vanilla suppression is opt-in per placement.** Primary biome placements set
   `replace-vanilla: true`, generating placed-feature overrides that suppress
   vanilla tree features; landmark overlays and cave species stay additive.
9. **Deprecation is recorded in-config, not deleted.** Superseded definitions
   (e.g. `lavatree`, replaced by the Iris 4.0 cave carver) are kept in-repo,
   unreferenced, with a `comment` explaining the supersession.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Hand-build each tree as a schematic | Not deterministic or reviewable; no shared shaping logic; every variant is bespoke. |
| One output format only (e.g. `.iob`) | Locks out schematic editing and vanilla `/place template`; the same shape must serve Iris, editors, and vanilla. |
| Single global density for all biomes | Primary species (notably swamp) were far too sparse; sparsity must be tuned per biome. |
| Let dark oak height tiers overlap | Tall and short dark oaks intermixed and broke the layered-canopy read; stacked tiers keep the floor dark and legible. |
| Leave giant trees at full height everywhere | Giants towered over the world and read out of proportion; capped most non-exempt species near 48 with rare exceptions. |
| Always suppress vanilla trees | Landmark overlays and cave species should add to the world, not replace it; suppression is opt-in per placement. |
| Delete deprecated configs (e.g. lavatree) | Loses design history; kept unreferenced with an explanatory `comment` instead. |
| Author Iris 4.0 procedural trees directly | Duplicates authoring; one config converts to procedural via `to_procedural.py`, keeping a single source. |

## Consequences

- **Positive:**
  - Single source of truth per tree: one JSON definition feeds `.iob`, `.schem`,
    `.nbt`, and the Iris 4.0 procedural format.
  - Deterministic output makes baked structures reproducible and reviewable.
  - Copy-and-tune authoring lowers the barrier to adding or adjusting species.
  - Per-biome density, layering, and height caps produce natural, legible biomes
    with iconic silhouettes and correct proportions.
  - Opt-in vanilla suppression cleanly separates "replace the biome's trees" from
    "add a landmark/understory on top."
  - Deprecated definitions retain their design history in-repo.
- **Negative / Trade-offs:**
  - Two config locations (`tools/tree-gen/configs/` vs. `species/`) require keeping
    the shipped species set curated and in sync.
  - `.iob`-only post-processing (encase, invert_y, clear_cone) has no procedural
    equivalent and is silently dropped on conversion.
  - Per-biome tuning is manual and lives across `docs/biome-trees.md` and
    `config.yml`; changes must be reflected in both.
  - Some intent is deferred: rainbow-grove per-biome recoloring and the cave
    species (`blightroot`, `cloudcap`) are out of the current demo placement.

## References

- `tools/tree-gen/README.md` - generator CLI, layout, and authoring overview.
- `tools/tree-gen/configs/*.json` - authoring/experimental tree definitions
  (e.g. `lavatree.json`, which documents its own deprecation).
- `common/src/main/resources/species/*.json` - curated predefined species shipped
  to the plugin and placed via `config.yml`.
- `docs/biome-trees.md` - the per-biome tree map, roles, density, height caps, and
  pending work.
- `docs/usage/TREE-GENERATION.md` - full field-by-field config schema and output
  format descriptions.
- `tools/tree-gen/to_procedural.py` - converts a config to the Iris 4.0
  `IrisProceduralTree` format.
- ADR-001 (tree growth mechanic) - consumes the same baked structures this
  configuration produces.
