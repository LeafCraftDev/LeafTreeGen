# treegen-ADR-001 - Tree Growth Mechanic: Deterministic Staged Reveal of the Baked Structure

**Status:** Proposed
**Date:** 2026-06-11

## Context

leaf-treegen places custom trees as fully baked NBT structures. The sapling path
(`PaperSaplingListener`) currently places the entire structure in a single
`placeStructure(...)` call, so a planted sapling pops into a finished tree instantly.
Worldgen trees are already tracked per chunk via `PaperTreeFarmStore` (axis-aligned
regions in the chunk `PersistentDataContainer`) so leaf breaks drop the correct
species' sapling.

We want a "growth" mechanic so trees visibly mature over time, comparable in appeal
to Dynamic Trees but **without a client mod** and at **low server cost**. Key
constraints surfaced during design discussion:

- Each variant is seed-fixed, so the final tree shape is fully known up front.
- Growth must read as natural (foliated at every visible step), not a bare trunk.
- Players can plant large trees (e.g. giant spruce), so a fixed reveal height would
  leave tall intermediates looking like bare poles.
- Growth must never overwrite player builds or terrain.
- A cut-down tree must not later "continue" growing or regrow ("haunting"); its
  pending future growth step must be cancelled when the tree is removed.
- Wood blocks cannot be bonemealed, so there is no in-tree acceleration.
- The pure datapack/jigsaw distribution has no plugin tick loop to drive staging.
- Our saplings are vanilla sapling blocks, so vanilla would try to grow them into
  vanilla trees; that native growth must be intercepted/cancelled and replaced with our
  staged reveal.

Prior art / discussion: this design was converged through an extended issue thread;
the ADR concept and template follow the RTP project's `docs/adr/` convention.

## Decision

Implement growth as a **deterministic, additive, staged reveal** of the already-baked
variant structure, driven by the Paper plugin. The same baked NBT used for worldgen
placement also drives growth (one source of truth).

Authoring surface is intentionally minimal - the admin sets only two fields:

```json
"growth": { "enabled": true, "totalTime": 24000 }
```

Mechanics:

1. **Stages are derived, not authored.** Stage boundaries are the structure's
   **branch attach-heights**: each stage reveals up to the next branch tier plus that
   tier's branches and their leaves, so every visible stage is foliated. Branchless
   structures (bushes, canopy-only models) fall back internally to even thirds
   (50% / 75% / 100%). The admin never configures stage counts.
2. **Additive only.** Each stage is a strict superset of the previous; a stage only
   adds the next band of voxels and never replaces an existing block. The final stage
   equals the full baked structure. This avoids block churn and flicker.
3. **Timing from completion fraction.** A single `totalTime` is authored; each stage's
   wall-clock duration is `totalTime x (completion_i - completion_{i-1})`. The tree
   always completes at exactly `totalTime`.
4. **Pre-flight footprint check (vanilla "no room" behavior).** Before growth begins,
   project the full final footprint into world coordinates using a **pooled, reusable
   buffer** (cleared and refilled per plant; backing array only grows, never shrinks)
   and check every voxel against the world and tracked-tree regions. If any
   non-replaceable block overlaps, the tree **refuses to grow** (the sapling stays a
   sapling), mirroring vanilla's failure when a block is overhead.
5. **Mid-growth obstruction backstop.** Because the world can change during growth, if
   a later stage still finds a non-replaceable block in a target voxel, keep the blocks
   already placed and **terminate growth permanently** (no rollback).
6. **Cut detection via tracked future tasks.** Each growing tree has exactly one
   pending scheduled growth task (the next stage reveal) tracked in a live registry
   keyed by the tree's origin. When a tree is removed - detected via a `BlockBreakEvent`
   listener that maps the broken block to an active growth region (reusing the
   `PaperTreeFarmStore` region lookup) - the tree's pending task is **cancelled and its
   growth record purged**, so no future step ever fires for a cut tree. As a
   restart-safe backstop, before a resumed task reveals its stage it re-validates that
   the recorded origin trunk block is still present; if it is gone (server was down when
   the tree was removed, fire, TNT, etc.), the task self-cancels and purges the record.
7. **No bonemeal on the tree.** Bonemeal works only on the sapling to initiate growth;
   the growing structure is purely time-driven.
8. **Intercept vanilla sapling growth.** Because our saplings are real vanilla sapling
   blocks, the server (random tick or bonemeal) will attempt to grow them into vanilla
   trees. We **cancel the vanilla growth** (Bukkit `StructureGrowEvent`, and the
   bonemeal `BlockFertilizeEvent` / `PlayerInteractEvent` path) for any sapling tracked
   as one of ours, and instead begin our staged reveal at that moment. The trigger
   (random tick maturing, or bonemeal) is reused as the "start growth" signal, but the
   actual structure placed is ours, not vanilla's.
9. **Persistence.** A per-tree growth record `{variant, currentStage, origin,
   nextTickTime}` is stored in the chunk PDC alongside the existing `PaperTreeFarmStore`
   regions. On chunk load the record is used to (re)schedule the single pending future
   task and register it in the live task registry. On completion, termination, or
   cancellation the pending task is removed and the tree hands off to `recordTree(...)`
   for sapling-drop tracking.
10. **Scope: plugin-only.** Growth applies to plugin-placed trees (sapling and the plugin
   worldgen path). The pure datapack/jigsaw model places instantly and is explicitly
   out of scope.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Per-block animated branch physics (Dynamic Trees style) | Not achievable server-side on vanilla clients; high cost; would overpromise. |
| Author explicit per-species stage lists / counts | More config burden; size-derived branch tiers give natural, foliated stages for free. |
| Fixed block-height bands per stage | Tall trees (giant spruce) would show bare-pole intermediates; proportional/branch-aligned reveal always shows leaves. |
| Replace blocks between stages | Causes block churn and flicker; additive supersets are cheaper and cleaner. |
| Bonemeal accelerates the growing tree | Wood blocks cannot be bonemealed in Minecraft; not possible. |
| Skip-and-continue around obstructions | User chose vanilla-like "no room -> won't grow" pre-flight plus permanent termination if blocked mid-growth. |
| Roll back to previous stage on mid-growth obstruction | Extra bookkeeping; freezing a partial tree against a wall reads fine. |
| Trunk-base sentinel only (per-tick origin block check) | User preferred explicitly tracking the pending future task and cancelling it on cut; the sentinel is retained only as a restart-safe backstop. |
| Per-tick polling of every growing tree | Tracking one pending scheduled task per tree (cancelled on break) avoids continuous polling and lets a cut tree's future step be removed directly. |
| Persisted voxel reservation for the footprint | Ephemeral pooled-buffer check is enough; the growth record's bounds act as a soft reservation, avoiding extra storage and GC churn. |

## Consequences

- **Positive:**
  - One source of truth: the same baked NBT drives both worldgen placement and growth.
  - Low runtime cost: precomputed voxels revealed by stage, no per-tick procedural sim.
  - Vanilla-client friendly: pure timed block placement, no client mod required.
  - Minimal authoring: two JSON fields; stages and durations are derived.
  - Predictable and safe: pre-flight footprint check prevents griefing player builds;
    cut trees cannot haunt or regrow.
  - Pooled footprint buffer avoids per-plant allocation / GC churn under bulk planting.
- **Negative / Trade-offs:**
  - Not animated branch articulation; marketed as "progressive reveal / staged natural
    growth," not Dynamic-Trees-style animation.
  - Plugin-only; the datapack distribution cannot offer growth (must be documented).
  - World changes during a long `totalTime` can permanently stunt a tree (accepted).
  - The break-listener adds a per-break region lookup; the trunk-block backstop lets a
    partial trim (base intact) heal as growth continues; acceptable per design.

## Implementation Status

- **Done (platform-agnostic core, unit tested):** `TreeSpecies.GrowthParams(enabled, totalTime)`
  with a backwards-compatible constructor; `growth` JSON parsing in `TreeRegistry`
  (preserved through config overrides); `GrowthPlanner` deriving additive Y-band stages from
  a `TreeModel` (branch-tier boundaries, thirds fallback, completion-fraction timing).
  Covered by `common/src/test/.../GrowthPlannerTest`.
- **Remaining (Paper runtime, needs a live server to verify):** sapling-path integration,
  the staged tick driver, chunk-PDC growth persistence, vanilla `StructureGrowEvent` /
  bonemeal interception, the break-listener task cancellation, and the pooled footprint
  pre-flight check.

## References

- `paper/src/main/java/net/leaf/treegen/paper/PaperSaplingListener.java` - current
  instant-placement sapling path to be extended; also where vanilla `StructureGrowEvent`
  / bonemeal growth is intercepted and replaced with the staged reveal.
- `common/src/main/java/net/leaf/treegen/common/TreeFarmStore.java` - platform-agnostic
  chunk-PDC region tracking pattern (via `Platform#getChunkData`/`setChunkData`) to extend
  with the growth record.
- `common/src/main/java/net/leaf/treegen/common/GrowthPlanner.java` - derives the additive
  staged-reveal plan from a baked `TreeModel`.
- `common/src/main/java/net/leaf/treegen/common/TreeSpecies.java` - `ProceduralParams` /
  `BranchParams` carry the branch geometry used to derive stage boundaries.
- ADR concept and template adapted from the RTP project's `docs/adr/` convention.
