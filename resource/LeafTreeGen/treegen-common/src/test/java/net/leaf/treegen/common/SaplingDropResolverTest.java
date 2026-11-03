package net.leaf.treegen.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class SaplingDropResolverTest {

    private TreeSpecies speciesWithLeaves(String id, Set<String> biomes,
                                          String leaf, String secondary, String underside) {
        TreeSpecies.CanopyParams canopy = new TreeSpecies.CanopyParams(
                "DENSITY", 1.0,
                List.of(new TreeSpecies.CanopyParams.Layer(5, 3.0)),
                null, secondary, secondary == null ? 0.0 : 0.5, true, 1.0, null,
                underside, underside == null ? 0.0 : 0.2);
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", leaf, 6, 12, "OAK",
                1.0, "CONSTANT", new HashMap<>(), false,
                0.0, 0.0, "CONSTANT", new HashMap<>(),
                "LINEAR", new HashMap<>(),
                null, 0.5, 1.0,
                canopy, true, 1, 1, List.of());
        return new TreeSpecies(id, id, "ns", "gp", biomes, "OAK_SAPLING",
                true, false, 3, 2, -1, "linear", "step", List.of(), params, null, null, 1);
    }

    @Test
    public void baseBlockIdNormalisesStatesAndNamespace() {
        Assertions.assertEquals("minecraft:oak_leaves",
                SaplingDropResolver.baseBlockId("minecraft:oak_leaves[distance=1,persistent=false]"));
        Assertions.assertEquals("minecraft:oak_leaves",
                SaplingDropResolver.baseBlockId("OAK_LEAVES"));
        Assertions.assertNull(SaplingDropResolver.baseBlockId(null));
    }

    @Test
    public void leafBlocksIncludePrimarySecondaryAndUnderside() {
        TreeSpecies s = speciesWithLeaves("custom", Set.of("minecraft:forest"),
                "minecraft:cherry_leaves", "minecraft:azalea_leaves", "minecraft:glow_lichen");
        Set<String> leaves = SaplingDropResolver.leafBlocksOf(s);
        Assertions.assertTrue(leaves.contains("minecraft:cherry_leaves"));
        Assertions.assertTrue(leaves.contains("minecraft:azalea_leaves"));
        Assertions.assertTrue(leaves.contains("minecraft:glow_lichen"));
    }

    @Test
    public void resolveByBiomeMatchesLeafAndBiome() {
        TreeSpecies cherry = speciesWithLeaves("cherry", Set.of("minecraft:cherry_grove"),
                "minecraft:cherry_leaves", null, null);
        TreeSpecies birch = speciesWithLeaves("birch", Set.of("minecraft:birch_forest"),
                "minecraft:birch_leaves", null, null);
        List<TreeSpecies> all = List.of(cherry, birch);

        TreeSpecies resolved = SaplingDropResolver.resolveByBiome(
                all, "minecraft:cherry_grove", "minecraft:cherry_leaves[distance=2]");
        Assertions.assertNotNull(resolved);
        Assertions.assertEquals("cherry", resolved.id());
    }

    @Test
    public void resolveByBiomeRejectsWrongBiome() {
        TreeSpecies cherry = speciesWithLeaves("cherry", Set.of("minecraft:cherry_grove"),
                "minecraft:cherry_leaves", null, null);
        Assertions.assertNull(SaplingDropResolver.resolveByBiome(
                List.of(cherry), "minecraft:desert", "minecraft:cherry_leaves"));
    }

    @Test
    public void resolveByBiomeRejectsUnknownLeaf() {
        TreeSpecies cherry = speciesWithLeaves("cherry", Set.of("minecraft:cherry_grove"),
                "minecraft:cherry_leaves", null, null);
        Assertions.assertNull(SaplingDropResolver.resolveByBiome(
                List.of(cherry), "minecraft:cherry_grove", "minecraft:oak_leaves"));
    }

    @Test
    public void estimatesAreSaneAndBounded() {
        TreeSpecies s = speciesWithLeaves("custom", Set.of(),
                "minecraft:oak_leaves", null, null);
        Assertions.assertTrue(SaplingDropResolver.estimateCanopyRadius(s) >= 4);
        Assertions.assertTrue(SaplingDropResolver.estimateHeight(s) >= 12);
        // Null/non-procedural species fall back to defaults.
        Assertions.assertEquals(4, SaplingDropResolver.estimateCanopyRadius(null));
        Assertions.assertEquals(12, SaplingDropResolver.estimateHeight(null));
    }

    @Test
    public void resolveByBiomeAndEstimatesTraverseGroupChildSpecies() {
        TreeSpecies oakChild = speciesWithLeaves("oak-small-mid", Set.of(),
                "minecraft:oak_leaves", null, null);
        TreeSpecies azaleaChild = speciesWithLeaves("meadow-azalea", Set.of(),
                "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves", null);
        TreeSpecies meadowGroup = new TreeSpecies(
                "meadow", "Meadow", "minecraft", "meadow",
                Set.of("minecraft:meadow"), "OAK_SAPLING",
                true, true, 8, 4, -1, "linear", "surface_structures",
                List.of(), null,
                List.of(
                        new TreeSpecies.WeightedSpecies("oak-small-mid", 85),
                        new TreeSpecies.WeightedSpecies("meadow-azalea", 15)),
                null, 1);

        List<TreeSpecies> all = List.of(meadowGroup, oakChild, azaleaChild);
        TreeSpecies resolvedOak = SaplingDropResolver.resolveByBiome(
                all, "minecraft:meadow", "minecraft:oak_leaves[distance=1]");
        Assertions.assertNotNull(resolvedOak,
                "Group species (e.g. meadow -> oak-small-mid) must resolve leaf drops from child species");

        TreeSpecies resolvedAzalea = SaplingDropResolver.resolveByBiome(
                all, "minecraft:meadow", "minecraft:flowering_azalea_leaves");
        Assertions.assertNotNull(resolvedAzalea,
                "Group species must resolve secondary leaves of referenced child species");
    }
}
