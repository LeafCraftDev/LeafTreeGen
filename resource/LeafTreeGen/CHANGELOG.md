# Changelog

All notable changes to LeafTreeGen are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-11-05

### Added

- **Unified single-jar multi-loader distribution & shared common-API integration (`LeafTreeGen-1.0.0.jar`):**
  - A single jar runs on Paper, Folia, Fabric (`26.1.x`), and NeoForge (`26.1.x`), built on `:server-api` (`ServerAccessor`, `WorldEventHook`, `TaggedItemSpec`, `BlockPos3i`, `BookMenuSpec`), `:commands-api`, `:effects-api`, `:yaml-api` (`RtpYamlConfig`), `:predict-api` (`UnitCurve`, `PolarCurve`, `PerlinNoise`, `DistributionSampler`, `BudgetPacer`, `EwmaEstimator`), and `:anvil-api` (`io.github.dailystruggle.anvil.Nbt`) alongside the shared platform carriers (`:bukkit:bukkit-common`, `:fabric:fabric-common-unobf`, `:neoforge:neoforge-common`, `:neoforge:neoforge-v26_1_R1`).
  - Added `datapack.generate` toggle in `config.yml` (`TreeGenConfig#needsDatapack`) to allow disabling worldgen datapack emission independently of `generation-mode`.
  - Added weighted `secondary_leaves` lists (`TreeSpecies.WeightedLeaf` & `secondaryLeafList`), canopy `start_angle` cone-cutoff and vertical `squish` shaping, branch-layer leaf offsets with `leaf_start_up`, and `NOISE` value-noise curve shaping in `ProceduralGenerator`.
  - Added programmatic storefront banners, 24-cell clean-boot benchmark matrix (`Paper 26.2`, `Folia 26.2`, `NeoForge 26.1.2`, `Fabric 26.1.2` across `.mca`/`.lca` and `NONE`/`DATAPACK`/`PROCEDURAL`), and architecture/verification infographics (`docs/assets/leaftreegen-*.{png,svg}`).
- **Staged tree growth (`GrowthManager` & `GrowthPlanner`):** Planted saplings and replaced vanilla saplings can grow incrementally from the base upward across horizontal-reach tiers (`growth.default-time`, `growth.min-stages`, `growth.max-stages`, and per-species `growth` overrides):
  - Pre-flight footprint checks cancel growth cleanly when the trunk or canopy is obstructed by non-replaceable blocks.
  - Mid-growth obstruction checks stop remaining stages if blocks are placed into the upper footprint while a tree is growing.
  - Breaking a placed log of an in-progress tree cancels remaining growth stages (`TreeFarmStore`).
  - Active growth jobs persist to chunk data and resume automatically when chunks reload.
- **Spawn-by-chunk slicing, storage-agnostic saved-chunk RMW & stateless halo generation (`SegmentedTreeModel`, `TreeModel`, `ProceduralGenerator`, `TreeGenEventController`, `RegionFileResolver`):**
  - `sliceForChunk`, `placeChunkSlice`, `partitionByChunk`, `touchedChunks`, and `intersectsChunk` clip any tree model to a single `[0..15, 0..15]` chunk slice while preserving wood-first, leaf-distance-ascending placement order.
  - `ProceduralGenerator.sampleChunkHalo` and `generateChunkHalo` let world generators evaluate and stamp partial tree slices per chunk in any generation order without touching neighbouring chunks.
  - `TreeGenEventController#onChunkLoad` evaluates procedural trees originating in `(chunkX, chunkZ)` immediately on first load (no `40`-tick unload race) and routes overhanging canopy slices via `placeOrDeferChunkBlocks`: writing directly to live chunks when loaded, retroactively patching already-saved chunks on disk through `:anvil-api`'s storage-agnostic `RegionFileResolver.patchChunkBlocks` (`RegionFileReader#patchChunkBlocks` across `.lca` and `.mca`), or deferring ungenerated neighbor chunks in `pendingChunkSlices` until `onChunkLoad` (`S-005`).
- **Expanded `/leaftree` `CommandsAPI` command tree, variant pinning & interactive chat chips (`LeafTreeGenCommand`, `LeafTreeGenCommandTest`):**
  - `/leaftree generate species=<id> [world=<name>] [x=<~|n>] [y=<~|n>] [z=<~|n>]` supports explicit world and relative (`~` / `~n`) or absolute coordinates in addition to cursor ray-trace targeting.
  - `/leaftree give species=<id> [player=<name>] [amount=<1..64>] [variant=<key>]` supports explicit variant pinning and dynamic tab completion across baked `.nbt` structure templates, procedural tiers, group-species child definitions (`availableVariantsFor`), and online players across Paper, Folia, Fabric, and NeoForge.
  - `/leaftree debug` prints active generation mode, loaded worlds, and per-species biome/worldgen/replacement status.
  - `/leaftree stats [reset=<true|false>]` reports and resets lock-free `TreeBlockBatcher` tick-budget and chunk-load window telemetry (`recordChunkLoad`, `totalChunksLoaded`, `totalNewChunksGenerated`, `chunkLoadWindowNanos`, `totalCompletionWindowNanos`, `BudgetPacer` + `EwmaEstimator`).
  - Added clickable `BookMenuSpec` action chips (`[List]`, `[Stats]`, `[Debug]`, `[Reload]`, `[Give Sapling]`, `[Generate Here]`, `[Reset Stats]`) across `/leaftree`, `/leaftree list`, `/leaftree debug`, and `/leaftree stats`.
  - Added `LeafTreeGenCommandTest` verifying pure `CommandsAPI` dispatch, tab completion, strict parameter validation, and clickable chip execution (`88` tests across `10` verification suites).
- **Fabric sapling growth mixin (`TreeGrowerMixin`, `leaf_treegen.mixins.json`):**
  - Added `TreeGrowerMixin` intercepting `TreeGrower#growTree` on Fabric so natural random-tick and bonemeal sapling growth fires `FabricModServerAccessor#onTreeGrow` and triggers custom tree replacement.

### Changed

- **Platform-neutral runtime architecture (`LeafTreeGenRuntime` & `TreeGenEventController`):** Moved all event handling (`onPlayerInteractBlock`, `onSaplingGrow`, `onBlockBreak`, `onLeavesDecay`, `onChunkLoad`), custom sapling item tagging (`SaplingItem` / `TaggedItemSpec`), adaptive multi-tick block batching (`TreeBlockBatcher`), and command registration into `treegen-common`, reducing `treegen-paper`, `treegen-fabric`, and `treegen-neoforge` to loader entry points only.
- **Shared curve, noise, and sampling math (`predict-api`):** Routed procedural trunk/canopy shaping, 3D Perlin noise, 6-connected line/branch tracing, spheroid/ellipsoid envelopes, weighted variant pools, and structure-grid sampling through `UnitCurve`, `PolarCurve`, `PerlinNoise`, and `DistributionSampler`.
- **Automatic datapack cleanup when disabled (`DatapackGenerator#removeIfPresent`, `LeafTreeGenRuntime`):** Switching `generation-mode` to `NONE` or `PROCEDURAL` (or setting `datapack.generate: false`) now automatically removes any previously generated `datapacks/<pack-name>/` directory during bootstrap (`bootstrapDatapacks`), startup (`generateDatapacks`), and `/leaftree reload`.
- **Optional custom biome tags & Minecraft `26.x` pack format range (`DatapackGenerator` format `v4`):** Marked non-vanilla custom biome entries (such as `leaf:*`) as optional (`"required": false`) in `#has_structure` biome tags so generated datapacks load cleanly on vanilla servers without external biome datapacks, and updated `pack.mcmeta` (`pack_format: 71`, `supported_formats: [61, 81]`, `min_format: 61`, `max_format: 999`).
- **Automatic `sapling-item` inference & group-species variant resolution (`TreeRegistry`):** `TreeRegistry#inferSaplingItem` now infers the matching vanilla sapling item from procedural trunk/leaf/profile definitions when `sapling-item` is omitted in species JSON, and `TreeRegistry#variantsFor` resolves baked `.nbt` variants across weighted child species for group placements.
- **Wood-first placement ordering for Nether/fungi stems (`SegmentedTreeModel`, `GrowthPlanner`):** Included `hyphae` alongside `_log`, `_wood`, and `_stem` blocks in wood-first placement and cut-tree detection so crimson/warped hyphae structures place trunks before leaves and cancel staged growth cleanly when broken.
- **Group-species sapling drop resolution (`SaplingDropResolver`):** `resolveByBiome`, `leafBlocksOf`, `estimateCanopyRadius`, and `estimateHeight` now traverse weighted child species inside group placements (such as `meadow` -> `oak-small-mid` + `meadow-azalea`) so breaking primary, secondary, or underside leaves in group biomes drops the matching custom sapling.
- Vanilla-feature tree placement now rejects placements over water via a `surface_water_depth_filter` (max water depth `0`), in addition to the solid-ground check, so trees do not spawn on oceans, rivers, or swamps unless `allow_water` is enabled for the species.

### Fixed

- Fixed structure-placed `feature_pool_element` `placed_feature` generation in `DatapackGenerator#writeFeaturePool` by omitting `minecraft:biome` (`BiomeFilter`) from jigsaw-referenced placed features (preventing vanilla `"Tried to biome check an unregistered feature"` errors during structure post-processing) and expanded vanilla tree suppression targets (`writeSuppression`).
- Fixed `DatapackGenerator.bakeAndCollectElements` so species with named `treeDefinitions` no longer duplicate fallback `variants` entries into the same jigsaw `template_pool`.
- Fixed `TreeGenEventController#onChunkLoad` and `onSaplingGrow` to support explicit `.nbt` variant placement when a species has no inline procedural generator and to sample center/exact biome checks inside the delayed chunk task after confirming the chunk remains loaded.
- Fixed vanilla saplings failing to grow from bonemeal or natural random ticks when `replace-vanilla: true` was enabled: suppression now only intercepts saplings whose wood family and biome match an active species with `replace_vanilla` enabled, skips `feature-trees`-only species, and restores the sapling block if custom placement cannot proceed.
- Fixed custom sapling planting failing with `"No tree templates available."` for `feature-trees` species (`forest`, `birch-forest`, `taiga`, `jungle`, `old-growth-pine-taiga`) and when `generation-mode: PROCEDURAL` is configured by falling back to `ProceduralGenerator` and resolving group child variants in `TreeRegistry.variantsFor` and `SaplingDropResolver`.
- Fixed multiface `trunk_surface` decorators (`minecraft:glow_lichen`, `minecraft:vine`, `sculk_vein`, `resin_clump`) emitting and rotating valid `[<face>=true]` block states instead of invalid `[facing=...]` properties.
- Fixed synchronous chunk-loading hazards (`S-005`) and Folia region-thread ownership checks when reading or writing chunk persistent data across chunk boundaries, and resolved `Biome`, `Material`, and `TreeType` keys via `org.bukkit.Keyed` to prevent `IncompatibleClassChangeError` on Paper and Folia `1.21.4+`.
- Fixed `/leaftree reload` to check `needsDatapack()` (covering `suppressionOnly` configurations) and trigger datapack regeneration across Paper, Folia, Fabric, and NeoForge.
- Fixed `treegen-plugin` shaded jar packaging (`build.gradle`) to bundle `:effects-api` platform carriers and `:neoforge:neoforge-common` while excluding unused `META-INF/services/io.github.dailystruggle.rtp.**` providers.
- Trees no longer spawn on top of other trees: the generated ground-check predicate now rejects placements supported by `#minecraft:logs` or `#minecraft:leaves` blocks so `WORLD_SURFACE_WG` cannot stack a tree onto an existing trunk or canopy.
- The `.leaf-treegen.sha256` datapack fingerprint now includes `DATAPACK_FORMAT_VERSION`, forcing a one-time datapack regeneration when upgrading across generator-format changes even if `config.yml` and species files are unchanged.

## [0.1.0] - 2026-06-12

Initial release.

### Added

- Procedural tree engine that generates trees in Java from per-species parameters (height, trunk shape, lean angle, canopy layers), with no pre-baked NBT files required.
- Biome-matched species, including savanna acacias, dark-forest canopies, glacier pines, cherry groves, spiral-crown forests, and rainbow groves, each assigned to the biomes they fit.
- Jigsaw worldgen integration: a vanilla worldgen datapack is generated on first load so Minecraft's structure system handles placement.
- Plantable saplings via `/leaftree give`, delivered as NBT-tagged items that grow into a random variant of the species when planted in a valid biome.
- Commands:
  - `/leaftree generate species=<id>` - place a tree at your cursor (admin test).
  - `/leaftree give species=<id> [player] [amount]` - hand out custom saplings.
  - `/leaftree list [species=<id>]` - list registered species or variants.
  - `/leaftree reload` - apply config changes and regenerate the datapack.
- Admin commands gated behind the `leaftreegen.admin` permission node.
- Server-friendly placement: multi-block tree placement is timeboxed against a per-tick budget rather than run inline, so large trees do not stall the main thread.
- Configuration via JSON species files in the `species/` folder, with biome assignments and placement tuning (spacing, separation, salt) in `config.yml`, overridable per-species.
- Generation modes: `DATAPACK`, `PROCEDURAL`, `BOTH`, and `NONE`.
- Platform support for Paper (and compatible Folia builds), Fabric, and NeoForge.

[Unreleased]: https://github.com/DailyStruggle/RTP/compare/leaftreegen-v1.0.0...HEAD
[1.0.0]: https://github.com/DailyStruggle/RTP/releases/tag/leaftreegen-v1.0.0
[0.1.0]: https://github.com/leaf/leaf-treegen/releases/tag/v0.1.0
