package net.leaf.treegen.common;

import io.github.dailystruggle.rtp.common.predict.UnitCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Derives the staged-growth plan for a baked {@link TreeModel}, implementing the
 * decision recorded in {@code docs/adr/ADR-001-tree-growth-mechanic.md}.
 *
 * <p>The plan is a <strong>deterministic, additive reveal</strong> of the already-built
 * structure from the base upward. Stage boundaries are derived from the structure itself,
 * never authored:
 * <ul>
 *   <li><strong>Branch tiers (primary).</strong> A stage boundary is placed at every height
 *       where the wood (trunk/branch) footprint first reaches further out than anything
 *       below it - i.e. a new branch tier "leafs out". Because each tier reveal includes the
 *       branch and the leaves attached at that height, every visible stage is foliated.</li>
 *   <li><strong>Thirds (fallback).</strong> Branchless structures (bushes, canopy-only
 *       models, or a bare pole) fall back to revealing cumulative height fractions of
 *       50% / 75% / 100% so they still stage gracefully.</li>
 * </ul>
 *
 * <p>Timing is derived from a single authored {@code totalTime} (in ticks): each stage's
 * wall-clock duration is proportional to the fraction of the tree's blocks it adds, so a
 * tree always finishes at exactly {@code totalTime}.
 *
 * <p>This class is platform-agnostic and free of any Bukkit/Minecraft dependency so it can
 * be unit tested directly against synthetic {@link TreeModel} instances.
 */
public final class GrowthPlanner {

    private GrowthPlanner() {
    }

    /**
     * A single growth stage: an additive reveal of all model blocks at or below
     * {@link #revealUpToY()}. Stages are ordered from base to top; the final stage's
     * {@link #completion()} is {@code 1.0} and its {@link #endTick()} equals {@code totalTime}.
     *
     * @param index        zero-based stage index
     * @param revealUpToY  inclusive maximum Y (model-relative) revealed by this stage
     * @param completion   cumulative fraction (0..1] of the model's blocks revealed so far
     * @param startTick    tick offset (from planting) at which this stage begins
     * @param endTick      tick offset at which this stage is fully revealed
     */
    public record Stage(int index, int revealUpToY, double completion, long startTick, long endTick) {
        /** Wall-clock duration of this stage in ticks. */
        public long durationTicks() {
            return endTick - startTick;
        }
    }

    /**
     * Build the ordered stage list for the given model.
     *
     * @param model     the fully baked tree (model-relative coordinates, base near y=0)
     * @param totalTime total ticks for the tree to fully reveal; values {@code <= 0} collapse
     *                  every stage to tick 0 (effectively instant)
     * @return ordered stages from base to top; empty when the model has no blocks
     */
    public static List<Stage> plan(TreeModel model, long totalTime) {
        Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
        if (blocks.isEmpty()) {
            return List.of();
        }

        int[] bounds = model.getBounds();
        int minY = bounds[1];
        int maxY = bounds[4];
        int total = blocks.size();

        List<Integer> boundaries = deriveBoundaries(blocks, minY, maxY);

        // Pre-count blocks at each Y so cumulative reveals are cheap.
        Map<Integer, Integer> countByY = new HashMap<>();
        for (TreeModel.BlockPos pos : blocks.keySet()) {
            countByY.merge(pos.y(), 1, Integer::sum);
        }

        List<Stage> stages = new ArrayList<>(boundaries.size());
        long prevEnd = 0L;
        int cumulative = 0;
        int prevBoundary = minY - 1;
        int index = 0;
        for (int boundary : boundaries) {
            for (int y = prevBoundary + 1; y <= boundary; y++) {
                cumulative += countByY.getOrDefault(y, 0);
            }
            double completion = (double) cumulative / total;
            long end = totalTime <= 0 ? 0L : Math.round(UnitCurve.linear(completion, 0.0, totalTime));
            // Guard against rounding making a stage end before it starts.
            if (end < prevEnd) {
                end = prevEnd;
            }
            stages.add(new Stage(index++, boundary, completion, prevEnd, end));
            prevEnd = end;
            prevBoundary = boundary;
        }

        // Force the final stage to land exactly on totalTime and full completion.
        if (!stages.isEmpty()) {
            Stage last = stages.get(stages.size() - 1);
            stages.set(stages.size() - 1,
                    new Stage(last.index(), last.revealUpToY(), 1.0, last.startTick(),
                            totalTime <= 0 ? 0L : totalTime));
        }
        return stages;
    }

    /**
     * All model blocks revealed by a stage: every block at or below {@code revealUpToY}.
     * Because reveals are additive supersets, callers may place the full set each stage and
     * rely on "place only into air/replaceable" semantics, or diff against the previous
     * stage's {@code revealUpToY} to place only the new band.
     */
    public static Map<TreeModel.BlockPos, String> blocksUpTo(TreeModel model, int revealUpToY) {
        Map<TreeModel.BlockPos, String> out = new HashMap<>();
        model.getBlocks().forEach((pos, state) -> {
            if (pos.y() <= revealUpToY) {
                out.put(pos, state);
            }
        });
        return out;
    }

    /** True when the block state represents trunk/branch wood (as opposed to leaves/accents). */
    public static boolean isWood(String state) {
        if (state == null) return false;
        String low = state.toLowerCase(java.util.Locale.ROOT);
        return low.contains("log") || low.contains("wood") || low.contains("stem") || low.contains("hyphae");
    }

    /**
     * Derive ascending, distinct stage-boundary Y levels (each inclusive), always ending at
     * {@code maxY}. Primary rule: branch tiers (wood reaching further out than anything
     * below). Fallback: cumulative-height thirds.
     */
    private static List<Integer> deriveBoundaries(Map<TreeModel.BlockPos, String> blocks, int minY, int maxY) {
        // Center of the trunk taken from the lowest wood slice.
        int lowestWoodY = Integer.MAX_VALUE;
        for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
            if (isWood(e.getValue())) {
                lowestWoodY = Math.min(lowestWoodY, e.getKey().y());
            }
        }

        TreeSet<Integer> boundaries = new TreeSet<>();
        if (lowestWoodY != Integer.MAX_VALUE) {
            long sumX = 0, sumZ = 0, n = 0;
            for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
                if (isWood(e.getValue()) && e.getKey().y() == lowestWoodY) {
                    sumX += e.getKey().x();
                    sumZ += e.getKey().z();
                    n++;
                }
            }
            int cx = (int) Math.round((double) sumX / n);
            int cz = (int) Math.round((double) sumZ / n);

            // Maximum horizontal reach of wood at each Y, measured from the trunk center.
            Map<Integer, Integer> reachByY = new HashMap<>();
            for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
                if (!isWood(e.getValue())) continue;
                int reach = Math.max(Math.abs(e.getKey().x() - cx), Math.abs(e.getKey().z() - cz));
                reachByY.merge(e.getKey().y(), reach, Math::max);
            }

            int trunkReach = reachByY.getOrDefault(lowestWoodY, 0);
            int spanY = Math.max(0, maxY - lowestWoodY);
            int[] reachProfile = new int[spanY];
            Arrays.fill(reachProfile, Integer.MIN_VALUE);
            for (int y = lowestWoodY + 1; y <= maxY; y++) {
                Integer reach = reachByY.get(y);
                if (reach != null) {
                    reachProfile[y - lowestWoodY - 1] = reach;
                }
            }
            for (int idx : UnitCurve.runningMaxBreakouts(reachProfile, trunkReach)) {
                boundaries.add(lowestWoodY + 1 + idx);
            }
        }

        if (boundaries.isEmpty()) {
            // Fallback: cumulative-height thirds (50% / 75% / 100%).
            boundaries.add((int) Math.round(UnitCurve.linear(0.5, minY, maxY)));
            boundaries.add((int) Math.round(UnitCurve.linear(0.75, minY, maxY)));
        }
        boundaries.add(maxY); // final stage always completes the structure

        return new ArrayList<>(boundaries);
    }
}
