# treegen-ADR-003 - Java Plugin: Platform-Abstracted Runtime for Placing and Tracking Trees

**Status:** Accepted
**Date:** 2026-06-11

## Context

ADR-002 covered the offline Python `tree-gen` generator: how a tree is described
in JSON and how species map to biomes. That generator is an authoring/baking tool;
it does not run on a live server. The companion piece is the **Java plugin** that
runs inside the Minecraft server and is responsible for actually getting those
trees into the world, letting players plant them, and keeping the world's vanilla
trees out of the way.

The plugin must settle a different set of concerns from the generator:

- **Where the trees come from at runtime.** The same data-driven species JSON used
  by the Python tool must also be readable by the server so the plugin can either
  bake worldgen datapacks or place trees procedurally at runtime - without a second,
  divergent authoring format.
- **Server portability.** The project targets Paper/Folia first, but the core logic
  (species parsing, geometry, datapack writing, tree tracking) should not be tied to
  the Bukkit API so a Fabric/NeoForge port remains possible.
- **Folia thread-safety.** Modern Paper/Folia is regionised; block reads and writes
  must happen on the owning region thread, not on a global main thread.
- **Datapack timing.** Worldgen datapacks (and the vanilla-tree suppression they
  carry) are only discovered by the server early in boot; writing them in the legacy
  `JavaPlugin#onLoad()` was too late and needed an extra restart to take effect.
- **Closing the loop for players.** Custom trees placed by worldgen or by a sapling
  must drop the *correct* species' sapling when their leaves break or decay, even
  where the biome alone is ambiguous.

Prior art / discussion: the configuration model and biome map are documented in
ADR-002 and `docs/biome-trees.md`; the growth mechanic that consumes the same baked
structures is ADR-001. This ADR records the runtime plugin that ties them together.

## Decision

Structure the plugin as a **platform-agnostic `common` core behind a `Platform`
service interface**, with thin server-specific modules (`paper`, and stub
`fabric`/`neoforge`) implementing that interface.

Core (`common`) responsibilities, all free of any server API:

1. **`Platform` SPI.** A single interface abstracts everything the core needs from
   the host: logging, world/root folders, biome lookup, single- and batched-block
   placement, structure placement, scheduling, messaging, per-chunk persistent data
   (`getChunkData`/`setChunkData`), and the structure `DataVersion`. Platforms that
   cannot persist chunk data inherit safe no-op defaults.
2. **`TreeRegistry`.** Extracts the bundled `species/*.json` (and a default
   `config.yml`) into the data folder, then loads and parses every species into
   `TreeSpecies` (trunk/canopy/branches/roots/decorators + procedural params),
   shared by both the generator-equivalent baking path and runtime placement.
3. **`ProceduralGenerator`.** A Java reimplementation of the same shaping logic the
   Python tool uses (trunk shaping, canopy presets, branch system, decorators,
   Perlin noise), producing an in-memory voxel `TreeModel` that is either placed
   live or baked to `.nbt` via `StructureNbtWriter`.
4. **`DatapackGenerator`.** Writes an Iris/jigsaw-style worldgen datapack: bakes
   variant structures, builds weighted `template_pool`s, normalises weights, and -
   when species opt into `replace-vanilla` - writes the `placed_feature` overrides
   that suppress the biome's vanilla trees.
5. **`TreeGenConfig`.** Parses `config.yml` (reusing RTP's `RtpYamlConfig`) into a
   typed config with four `GenerationMode`s - `DATAPACK` (bake jigsaw pools),
   `PROCEDURAL` (place at runtime), `BOTH`, and `NONE` - plus helpers
   (`needsDatapack`, `suppressionOnly`, `hasVanillaSuppression`) that decide whether
   a full datapack, a suppression-only datapack, or no datapack is required.
6. **Hybrid sapling-drop resolution.** `TreeFarmStore` records each placed tree as
   an axis-aligned region tagged with its species id, written into the per-chunk
   persistent data of every chunk it overlaps (so it survives restarts and a leaf
   lookup only inspects its own chunk). `SaplingDropResolver` is the biome-based
   fallback (match a broken leaf block to a species allowed in that biome).

Paper module responsibilities:

7. **`PaperBootstrap` writes datapacks in the bootstrap phase**, before the server
   discovers datapacks, so both baked trees and vanilla suppression take effect on
   the same boot rather than after an extra restart. It generates for the main level
   folder always and for the legacy split `_nether`/`_the_end` folders only when
   they exist (Folia single-folder vs. legacy Spigot layout).
8. **Four event listeners**, all Folia-safe via the `RegionScheduler`:
   - `PaperSaplingListener` - a tagged sapling item is planted: validate
     permission/biome, place a random baked variant, record the region.
   - `PaperChunkPlacementListener` - in `PROCEDURAL` mode, deterministic
     spacing/salt grid placement on new-chunk load, deferred ~40 ticks so neighbour
     chunks finish generating (avoids trees clipped square at chunk borders).
   - `PaperTreeSuppressionListener` - cancels vanilla `StructureGrowEvent` for biomes
     where a `replace-vanilla` species is active and optionally replaces it with our
     tree (the runtime counterpart to the datapack suppression).
   - `PaperLeafDropListener` - on leaf decay/break, drops the species sapling using
     the hybrid resolution (precise `TreeFarmStore` first, biome fallback second).
9. **Fabric/NeoForge are intentionally stubs.** They implement `Platform` with
   no-op/placeholder behaviour to keep the abstraction honest and the port path open,
   without yet shipping a working mod.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Bukkit-coupled single module | No port path; geometry, parsing, and datapack writing would be locked to one server API. |
| Write datapacks in `onLoad()` | Runs after datapack discovery; trees/suppression needed an extra server restart to apply. |
| Datapack baking only (no runtime placement) | Loses live sapling planting, runtime vanilla replacement, and per-chunk procedural variety; `PROCEDURAL`/`BOTH` modes cover these. |
| Runtime procedural only (no datapack) | Worldgen integration and Iris/jigsaw pools want a real datapack; suppression of vanilla trees is cleanest as `placed_feature` overrides. |
| Biome-only sapling resolution | Ambiguous where several species share a biome/leaf; the precise per-region store disambiguates actual plantings. |
| Global main-thread block edits | Breaks on Folia's regionised threading; all edits are dispatched through the `RegionScheduler`. |
| Separate runtime authoring format | Duplicates the Python tool's schema; instead the same `species/*.json` feeds both. |

## Consequences

- **Positive:**
  - One species schema drives the Python tool, the Java baking path, and runtime
    placement - no divergent formats.
  - The `Platform` SPI keeps all real logic server-agnostic and unit-testable, with a
    clear Fabric/NeoForge port path.
  - Bootstrap-phase datapack writing makes baked trees and vanilla suppression take
    effect on first boot.
  - Folia-safe scheduling and per-chunk persistence make placement and tracking
    correct on modern regionised servers and across restarts.
  - Hybrid sapling resolution drops the correct species even in shared biomes.
- **Negative / Trade-offs:**
  - `ProceduralGenerator` duplicates the Python generator's shaping logic in Java;
    the two must be kept behaviourally in sync.
  - Fabric/NeoForge modules are non-functional stubs today.
  - Per-chunk region records add persistent-data writes per placed tree (bounded, but
    non-zero) and grow with farmed forests.
  - Reusing RTP's `RtpYamlConfig` couples config parsing to that library.
  - Vanilla-tree detection in the suppression listener is name-substring based and
    must be widened as new vanilla tree types appear.

## References

- `common/src/main/java/net/leaf/treegen/common/Platform.java` - the server SPI.
- `common/src/main/java/net/leaf/treegen/common/TreeRegistry.java` - species loading
  and parsing.
- `common/src/main/java/net/leaf/treegen/common/ProceduralGenerator.java` - runtime
  geometry (Java port of the Python shaping logic).
- `common/src/main/java/net/leaf/treegen/common/DatapackGenerator.java` - jigsaw pool
  and vanilla-suppression datapack writer.
- `common/src/main/java/net/leaf/treegen/common/TreeGenConfig.java` - config and
  generation modes.
- `common/src/main/java/net/leaf/treegen/common/TreeFarmStore.java` /
  `SaplingDropResolver.java` - hybrid sapling-drop resolution.
- `paper/src/main/java/net/leaf/treegen/paper/PaperBootstrap.java` - bootstrap-phase
  datapack generation.
- `paper/src/main/java/net/leaf/treegen/paper/Paper*Listener.java` - the four runtime
  listeners (sapling, chunk placement, suppression, leaf drop).
- ADR-001 (tree growth) and ADR-002 (configuration and the Python generator) - the
  decisions this plugin runtime consumes.
