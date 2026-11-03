package net.leaf.treegen.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for {@link GrowthPlanner}: stage derivation (branch tiers and the
 * branchless thirds fallback), timing math, and additive-reveal invariants. Uses synthetic
 * {@link TreeModel} instances so geometry is fully controlled.
 */
class GrowthPlannerTest {

    private static final String LOG = "minecraft:oak_log";
    private static final String LEAF = "minecraft:oak_leaves";

    @Test
    void emptyModelProducesNoStages() {
        TreeModel model = new TreeModel("empty");
        assertTrue(GrowthPlanner.plan(model, 24000).isEmpty());
    }

    @Test
    void branchlessPoleFallsBackToThirds() {
        // A bare 10-block trunk column: no branch ever reaches past the trunk footprint.
        TreeModel model = new TreeModel("pole");
        for (int y = 0; y <= 9; y++) {
            model.setBlock(0, y, 0, LOG);
        }

        List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, 1000);

        // Boundaries: 50% -> y5, 75% -> y7, 100% -> y9.
        assertEquals(3, stages.size());
        assertEquals(5, stages.get(0).revealUpToY());
        assertEquals(7, stages.get(1).revealUpToY());
        assertEquals(9, stages.get(2).revealUpToY());

        // 6/10, 8/10, 10/10 of the blocks revealed.
        assertEquals(0.6, stages.get(0).completion(), 1e-9);
        assertEquals(0.8, stages.get(1).completion(), 1e-9);
        assertEquals(1.0, stages.get(2).completion(), 1e-9);
    }

    @Test
    void branchTiersBecomeStageBoundaries() {
        // Trunk x=0,z=0 from y=0..9, plus two outward branch tiers at y=5 (reach 3)
        // and y=8 (reach 5), each with a leaf cluster so every stage is foliated.
        TreeModel model = new TreeModel("branched");
        for (int y = 0; y <= 9; y++) {
            model.setBlock(0, y, 0, LOG);
        }
        // Tier 1 at y=5 reaching out to x=3.
        for (int x = 1; x <= 3; x++) {
            model.setBlock(x, 5, 0, LOG);
        }
        model.setBlock(4, 5, 0, LEAF);
        // Tier 2 at y=8 reaching further out to x=5.
        for (int x = 1; x <= 5; x++) {
            model.setBlock(x, 8, 0, LOG);
        }
        model.setBlock(6, 8, 0, LEAF);

        List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, 24000);

        List<Integer> boundaries = stages.stream().map(GrowthPlanner.Stage::revealUpToY).toList();
        assertEquals(List.of(5, 8, 9), boundaries);
        // Final stage always completes the structure exactly on totalTime.
        assertEquals(1.0, stages.get(stages.size() - 1).completion(), 1e-9);
    }

    @Test
    void timingIsMonotonicAndFinishesAtTotalTime() {
        TreeModel model = new TreeModel("timing");
        for (int y = 0; y <= 9; y++) {
            model.setBlock(0, y, 0, LOG);
        }
        for (int x = 1; x <= 3; x++) {
            model.setBlock(x, 6, 0, LOG);
        }

        long totalTime = 24000;
        List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, totalTime);

        long prevEnd = 0;
        double prevCompletion = 0.0;
        for (GrowthPlanner.Stage s : stages) {
            assertEquals(prevEnd, s.startTick(), "stage should start where the previous ended");
            assertTrue(s.endTick() >= s.startTick(), "stage must not end before it starts");
            assertTrue(s.completion() >= prevCompletion, "completion must be non-decreasing");
            prevEnd = s.endTick();
            prevCompletion = s.completion();
        }
        assertEquals(totalTime, stages.get(stages.size() - 1).endTick());
    }

    @Test
    void nonPositiveTotalTimeCollapsesToInstant() {
        TreeModel model = new TreeModel("instant");
        for (int y = 0; y <= 5; y++) {
            model.setBlock(0, y, 0, LOG);
        }

        List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, 0);
        assertFalse(stages.isEmpty());
        for (GrowthPlanner.Stage s : stages) {
            assertEquals(0L, s.startTick());
            assertEquals(0L, s.endTick());
        }
    }

    @Test
    void blocksUpToRevealsOnlyTheRevealedBand() {
        TreeModel model = new TreeModel("band");
        for (int y = 0; y <= 9; y++) {
            model.setBlock(0, y, 0, LOG);
        }
        // 6 blocks (y=0..5) are at or below y=5.
        assertEquals(6, GrowthPlanner.blocksUpTo(model, 5).size());
        assertEquals(10, GrowthPlanner.blocksUpTo(model, 9).size());
        assertTrue(GrowthPlanner.blocksUpTo(model, -1).isEmpty());
    }

    @Test
    void woodDetection() {
        assertTrue(GrowthPlanner.isWood("minecraft:oak_log[axis=y]"));
        assertTrue(GrowthPlanner.isWood("minecraft:oak_wood"));
        assertTrue(GrowthPlanner.isWood("minecraft:crimson_stem"));
        assertFalse(GrowthPlanner.isWood("minecraft:oak_leaves"));
        assertFalse(GrowthPlanner.isWood(null));
    }
}
