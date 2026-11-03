package net.leaf.treegen.common;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.server.api.BlockBreakContext;
import io.github.dailystruggle.server.api.BlockPos3i;
import io.github.dailystruggle.server.api.ChunkLoadContext;
import io.github.dailystruggle.server.api.DispatchingWorldEventHook;
import io.github.dailystruggle.server.api.LeavesDecayContext;
import io.github.dailystruggle.server.api.PlayerInteractBlockContext;
import io.github.dailystruggle.server.api.SaplingGrowContext;
import io.github.dailystruggle.server.api.WorldEventHook;
import net.leaf.treegen.common.command.CallerLocation;
import net.leaf.treegen.common.command.LeafTreeGenCommand;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Property-based and stateful fuzz tests for {@link ProceduralGenerator}, {@link TreeModel},
 * {@link GrowthPlanner}, {@link GrowthManager}, {@link TreeBlockBatcher},
 * {@link TreeGenEventController}, and {@link LeafTreeGenCommand}.
 */
public class TreeGenFuzzTest {

    private static final String[] TRUNK_SHAPES = {
        "CONSTANT", "LINEAR", "PARABOLIC", "EXPONENTIAL", "SINUSOIDAL", "HARMONIC", "STEPPED", "TAPER", "UNKNOWN_SHAPE"
    };
    private static final String[] CURVE_FNS = {
        "CONSTANT", "LINEAR", "PARABOLIC", "EXPONENTIAL", "SINUSOIDAL", "S_CURVE", "HELIX", "UNKNOWN_FN"
    };
    private static final String[] PROFILES = {
        "OAK", "PINE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "CHERRY", "MANGROVE", "WEEPING", "MUSHROOM"
    };
    private static final String[] CANOPY_MODES = {
        "ROUNDED", "CONICAL", "LAYERED", "UMBRELLA", "WEEPING", "DENSITY", "CLUSTER", "SPHERE", "NONE"
    };
    private static final String[] DECORATOR_TARGETS = {
        "branch_tip", "trunk_surface", "canopy_top", "canopy_bottom", "trunk_base"
    };
    private static final String[] DECORATOR_BLOCKS = {
        "minecraft:vine", "minecraft:glow_lichen", "minecraft:sculk_vein", "minecraft:resin_clump",
        "minecraft:shroomlight", "minecraft:bee_nest", "minecraft:moss_carpet", "minecraft:brown_mushroom"
    };
    private static final Set<String> VALID_AXES = Set.of("x", "y", "z");
    private static final Set<String> VALID_FACINGS = Set.of("north", "south", "east", "west", "up", "down");
    private static final Set<String> VALID_BOOLS = Set.of("true", "false");
    private static final int[][] ORTHOGONAL = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    @Test
    public void testAllSpeciesAndRotationsPreserveBlockStatesLeafDecayAndGrowthPlan() {
        FuzzPlatform platform = new FuzzPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        registry.loadSpecies();
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        int modelsChecked = 0;
        for (Map.Entry<String, TreeSpecies> entry : registry.getSpeciesMap().entrySet()) {
            TreeSpecies species = entry.getValue();
            List<Map.Entry<String, TreeSpecies.ProceduralParams>> defs = new ArrayList<>();
            if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
                defs.addAll(species.treeDefinitions().entrySet());
            } else if (species.procedural() != null) {
                defs.add(Map.entry("default", species.procedural()));
            }
            for (Map.Entry<String, TreeSpecies.ProceduralParams> def : defs) {
                for (int seed = 0; seed < 3; seed++) {
                    TreeModel base = generator.buildProceduralModel(
                            species.id() + ":" + def.getKey(), def.getValue(), new Random(seed));
                    Assertions.assertNotNull(base);
                    Assertions.assertFalse(base.getBlocks().isEmpty(),
                            "Procedural model must not be empty for " + species.id() + ":" + def.getKey());
                    for (int rot = 0; rot < 4; rot++) {
                        TreeModel rotated = copyModel(base);
                        rotated.rotate(rot);
                        assertModelInvariants(species.id() + ":" + def.getKey() + "@rot" + rot, rotated);
                        modelsChecked++;
                    }
                }
            }
        }
        Assertions.assertTrue(modelsChecked >= 100,
                "Expected at least 100 species/variant/rotation combinations, checked " + modelsChecked);
    }

    @Test
    public void testRandomExtremeProceduralParamsFuzzer() {
        FuzzPlatform platform = new FuzzPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        Random rng = new Random(0x5EED_72EE_F022L);

        int[] heights = {0, 1, 2, 4, 9, 16, 26};
        double[] trunkWidths = {0.0, 0.5, 1.0, 2.5, 4.5};
        double[] densities = {0.0, 0.2, 0.65, 1.0, 1.25};

        for (int i = 0; i < 100; i++) {
            int minH = heights[rng.nextInt(heights.length)];
            int maxH = minH + rng.nextInt(5);
            double trunkWidth = trunkWidths[rng.nextInt(trunkWidths.length)];
            String profile = PROFILES[rng.nextInt(PROFILES.length)];
            String trunkShape = TRUNK_SHAPES[rng.nextInt(TRUNK_SHAPES.length)];
            String azimuthFn = CURVE_FNS[rng.nextInt(CURVE_FNS.length)];
            String curveFn = CURVE_FNS[rng.nextInt(CURVE_FNS.length)];
            String canopyMode = CANOPY_MODES[rng.nextInt(CANOPY_MODES.length)];
            double density = densities[rng.nextInt(densities.length)];

            TreeSpecies.BranchParams.SubBranchParams subBranches = rng.nextBoolean()
                    ? new TreeSpecies.BranchParams.SubBranchParams(
                            rng.nextInt(3),
                            (rng.nextDouble() - 0.5) * 30.0,
                            15.0 + rng.nextDouble() * 45.0,
                            0.4 + rng.nextDouble() * 0.5,
                            1 + rng.nextInt(3),
                            rng.nextBoolean() ? "SPHERE" : "ELLIPSOID",
                            0.4 + rng.nextDouble() * 0.6)
                    : null;

            TreeSpecies.BranchParams branchParams = rng.nextBoolean()
                    ? new TreeSpecies.BranchParams(
                            rng.nextInt(6) - 1,
                            1.0 + rng.nextDouble() * 2.0,
                            2.0 + rng.nextDouble() * 4.0,
                            CURVE_FNS[rng.nextInt(CURVE_FNS.length)],
                            Map.of("exponent", 0.5 + rng.nextDouble() * 2.0),
                            -20.0 + rng.nextDouble() * 40.0,
                            10.0 + rng.nextDouble() * 50.0,
                            0.5 + rng.nextDouble() * 2.0,
                            rng.nextDouble() * 0.8,
                            1 + rng.nextInt(3),
                            rng.nextBoolean() ? "SPHERE" : "CONE",
                            0.3 + rng.nextDouble() * 0.7,
                            CURVE_FNS[rng.nextInt(CURVE_FNS.length)],
                            Map.of(),
                            subBranches)
                    : null;

            TreeSpecies.CanopyParams canopyParams = new TreeSpecies.CanopyParams(
                    canopyMode,
                    density,
                    List.of(
                            new TreeSpecies.CanopyParams.Layer(Math.max(0, minH - 2), 1.5 + rng.nextDouble() * 3.0),
                            new TreeSpecies.CanopyParams.Layer(Math.max(1, maxH), 1.0 + rng.nextDouble() * 2.0)),
                    branchParams,
                    rng.nextBoolean() ? "minecraft:azalea_leaves" : null,
                    rng.nextDouble() * 0.5,
                    rng.nextBoolean(),
                    0.5 + rng.nextDouble() * 1.5,
                    rng.nextBoolean() ? 0.3 + rng.nextDouble() * 0.6 : null,
                    rng.nextBoolean() ? "minecraft:shroomlight" : null,
                    rng.nextDouble() * 0.25);

            List<TreeSpecies.Decorator> decorators = new ArrayList<>();
            int decCount = rng.nextInt(3);
            for (int d = 0; d < decCount; d++) {
                decorators.add(new TreeSpecies.Decorator(
                        DECORATOR_TARGETS[rng.nextInt(DECORATOR_TARGETS.length)],
                        DECORATOR_BLOCKS[rng.nextInt(DECORATOR_BLOCKS.length)],
                        0.1 + rng.nextDouble() * 0.6,
                        rng.nextBoolean()));
            }

            TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                    "minecraft:oak_log",
                    "minecraft:oak_leaves",
                    minH,
                    maxH,
                    profile,
                    trunkWidth,
                    trunkShape,
                    Map.of("floor", 0.2, "peak_offset", 0.3, "steepness", 4.0, "base_multiplier", 1.5),
                    rng.nextBoolean(),
                    rng.nextDouble() * 25.0,
                    rng.nextDouble() * 360.0,
                    azimuthFn,
                    Map.of("rate", 45.0),
                    curveFn,
                    Map.of("amplitude", 1.5),
                    rng.nextBoolean() ? "minecraft:birch_log" : null,
                    rng.nextDouble() * 0.4,
                    0.4 + rng.nextDouble() * 0.6,
                    canopyParams,
                    rng.nextBoolean(),
                    1,
                    1,
                    decorators);

            TreeModel model = generator.buildProceduralModel("fuzz-" + i, params, new Random(rng.nextLong()));
            Assertions.assertNotNull(model);
            if (!model.getBlocks().isEmpty()) {
                TreeModel rotated = copyModel(model);
                rotated.rotate(rng.nextInt(4));
                assertModelInvariants("fuzz-" + i, rotated);
            }
        }
    }

    @Test
    public void testStatefulGrowthManagerAndBatcherLifecycleFuzzer() {
        Random rng = new Random(0x72EE_57A7_EF00L);
        List<TreeSpecies> pool = List.of(
                createFuzzSpecies("fuzz-oak", 400L, 7, 2.0),
                createFuzzSpecies("fuzz-pine", 800L, 10, 2.0),
                createFuzzSpecies("fuzz-bush", 0L, 3, 1.0));
        Map<String, TreeSpecies> speciesMap = new HashMap<>();
        for (TreeSpecies s : pool) {
            speciesMap.put(s.id(), s);
        }
        TreeGenConfig cfg = new TreeGenConfig(speciesMap, "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL, 2.0, 32);

        for (int episode = 0; episode < 40; episode++) {
            FuzzPlatform platform = new FuzzPlatform();
            TreeRegistry registry = new TreeRegistry(platform);
            ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
            TreeFarmStore farmStore = new TreeFarmStore(platform);
            TreeBlockBatcher batcher = new TreeBlockBatcher(platform, () -> cfg);
            GrowthManager manager = new GrowthManager(platform, generator, farmStore, () -> cfg);

            for (int step = 0; step < 50; step++) {
                int op = rng.nextInt(6);
                switch (op) {
                    case 0 -> {
                        // Start staged growth at a candidate origin
                        int ox = (rng.nextInt(4) - 2) * 16 + 4;
                        int oz = (rng.nextInt(4) - 2) * 16 + 4;
                        TreeSpecies sp = pool.get(rng.nextInt(pool.size()));
                        manager.startGrowth("world", ox, 64, oz, sp, rng.nextLong());
                    }
                    case 1 -> {
                        // Advance one scheduled task
                        platform.runNextScheduledTask();
                    }
                    case 2 -> {
                        // Break a random placed block (wood or leaf)
                        if (!platform.placedBlocks.isEmpty()) {
                            List<Map.Entry<String, String>> entries = new ArrayList<>(platform.placedBlocks.entrySet());
                            Map.Entry<String, String> pick = entries.get(rng.nextInt(entries.size()));
                            String[] xyz = pick.getKey().split(",");
                            int bx = Integer.parseInt(xyz[0]);
                            int by = Integer.parseInt(xyz[1]);
                            int bz = Integer.parseInt(xyz[2]);
                            String oldState = platform.placedBlocks.remove(pick.getKey());
                            manager.onBlockBroken("world", bx, by, bz, oldState);
                        }
                    }
                    case 3 -> {
                        // Place a solid obstruction above one of the candidate origins
                        int ox = (rng.nextInt(4) - 2) * 16 + 4;
                        int oz = (rng.nextInt(4) - 2) * 16 + 4;
                        int oy = 65 + rng.nextInt(8);
                        platform.obstructedPositions.add(ox + "," + oy + "," + oz);
                    }
                    case 4 -> {
                        // Simulate chunk unload + restart/reload on a new GrowthManager
                        platform.scheduledTasks.clear();
                        manager = new GrowthManager(platform, generator, farmStore, () -> cfg);
                        for (int cx = -2; cx <= 2; cx++) {
                            for (int cz = -2; cz <= 2; cz++) {
                                manager.resumeChunk("world", cx, cz);
                            }
                        }
                    }
                    default -> {
                        // Queue a tree on TreeBlockBatcher and step scheduled tasks
                        TreeSpecies sp = pool.get(rng.nextInt(pool.size()));
                        TreeModel model = generator.buildModel(sp, new Random(rng.nextLong()));
                        if (model != null) {
                            Map<TreeModel.BlockPos, String> worldBlocks = new LinkedHashMap<>();
                            int ox = rng.nextInt(64);
                            int oz = rng.nextInt(64);
                            model.getBlocks().forEach((pos, st) ->
                                    worldBlocks.put(new TreeModel.BlockPos(ox + pos.x(), 64 + pos.y(), oz + pos.z()), st));
                            batcher.placeBlocks("world", ox >> 4, oz >> 4, worldBlocks);
                            platform.runNextScheduledTask();
                        }
                    }
                }
            }

            // Inject a corrupt record into chunk (0,0) and verify resumeChunk purges it cleanly
            String existingGrowth = platform.getGrowthData("world", 0, 0);
            String withCorrupt = (existingGrowth == null ? "" : existingGrowth + "\n")
                    + "corrupt_line\n1,64,1,nonexistent-species,42,0,100\n1,64,1,fuzz-oak,42,999,100";
            platform.setGrowthData("world", 0, 0, withCorrupt);
            manager.resumeChunk("world", 0, 0);

            int drainGuard = 0;
            while (platform.runNextScheduledTask() && drainGuard < 2500) {
                drainGuard++;
            }

            Assertions.assertTrue(drainGuard < 2500, "Scheduled tasks must drain within bounded steps");
            Assertions.assertEquals(0, manager.activeCount(),
                    "All active growth entries must drain to 0 at end of episode " + episode);
            for (Map.Entry<String, String> gd : platform.growthData.entrySet()) {
                Assertions.assertTrue(gd.getValue() == null || gd.getValue().isBlank(),
                        "Chunk growth persistence must be empty after drain in chunk " + gd.getKey()
                                + ", found: " + gd.getValue());
            }
        }
    }

    @Test
    public void testCommandAndEventControllerFuzzer() {
        FuzzPlatform platform = new FuzzPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        registry.loadSpecies();
        TreeGenConfig cfg = new TreeGenConfig(registry.getSpeciesMap(), "test", "test", TreeGenConfig.GenerationMode.BOTH);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        generator.setConfigSpecies(registry.getSpeciesMap());
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        GrowthManager growthManager = new GrowthManager(platform, generator, farmStore, () -> cfg);

        try (TreeGenEventController controller = new TreeGenEventController(
                platform, () -> cfg, () -> registry, generator, growthManager, farmStore)) {
            controller.register();
            LeafTreeGenCommand cmd = new LeafTreeGenCommand(platform, () -> cfg, () -> registry, generator, () -> {});
            Random rng = new Random(0xC0FFEE_1234L);
            UUID playerId = UUID.randomUUID();
            List<String> speciesIds = new ArrayList<>(registry.getSpeciesMap().keySet());

            String[][] fuzzedCommandArgs = {
                {},
                {"list"},
                {"debug"},
                {"stats"},
                {"stats", "budget=0"},
                {"stats", "budget=-999999"},
                {"stats", "budget=50", "reset=true"},
                {"stats", "budget=12", "reset=false"},
                {"stats", "budget=notanumber"},
                {"reload"},
                {"give"},
                {"give", "species=nonexistent"},
                {"give", "species=" + speciesIds.get(0), "amount=-5"},
                {"give", "species=" + speciesIds.get(0), "amount=0"},
                {"give", "species=" + speciesIds.get(0), "amount=999999", "variant=unknown"},
                {"generate"},
                {"generate", "species=nonexistent"},
                {"generate", "species=" + speciesIds.get(0)},
                {"unknown_subcommand", "foo=bar", "123"}
            };

            for (String[] args : fuzzedCommandArgs) {
                List<String> messages = new ArrayList<>();
                cmd.onCommand(playerId, p -> true, messages::add, args);
                CommandsAPI.execute();
                cmd.onCommand(CommandsAPI.serverId, p -> true, messages::add, args);
                CommandsAPI.execute();
                cmd.onCommand(playerId, p -> false, messages::add, args);
                CommandsAPI.execute();
                Assertions.assertNotNull(cmd.onTabComplete(playerId, p -> true, args));
            }

            for (int i = 0; i < 60; i++) {
                String spId = speciesIds.get(rng.nextInt(speciesIds.size()));
                int x = rng.nextInt(128) - 64;
                int y = 64;
                int z = rng.nextInt(128) - 64;
                Map<String, String> tags = rng.nextBoolean()
                        ? Map.of(Platform.TAG_TREE_SPECIES, spId)
                        : Map.of();
                platform.eventHook.firePlayerInteractBlock(new PlayerInteractBlockContext(
                        playerId, "world", x, y - 1, z, x, y, z,
                        rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(),
                        "minecraft:oak_sapling", tags, p -> true, amt -> {}));
                platform.eventHook.fireSaplingGrow(new SaplingGrowContext(
                        "world", x, y, z, "minecraft:oak_sapling", "minecraft:forest", rng.nextBoolean()));
                platform.eventHook.fireLeavesDecay(new LeavesDecayContext(
                        "world", x, y + 4, z, "minecraft:oak_leaves", "minecraft:forest"));
                platform.eventHook.fireBlockBreak(new BlockBreakContext(
                        playerId, "world", x, y, z, "minecraft:oak_log", "minecraft:forest"));
                platform.eventHook.fireChunkLoad(new ChunkLoadContext(
                        "world", x >> 4, z >> 4, rng.nextBoolean(), rng.nextLong()));
                while (platform.runNextScheduledTask()) {
                    // drain
                }
            }
        }
    }

    private static void assertModelInvariants(String label, TreeModel model) {
        Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
        Assertions.assertFalse(blocks.isEmpty(), label + ": model must not be empty");
        int[] bounds = model.getBounds();
        Assertions.assertTrue(bounds[0] <= bounds[3] && bounds[1] <= bounds[4] && bounds[2] <= bounds[5],
                label + ": invalid bounds");

        Set<TreeModel.BlockPos> woodSet = new HashSet<>();
        Map<TreeModel.BlockPos, Integer> leafDistances = new HashMap<>();

        for (Map.Entry<TreeModel.BlockPos, String> entry : blocks.entrySet()) {
            String state = entry.getValue();
            assertValidBlockStateSyntax(label, state);
            if (GrowthPlanner.isWood(state)) {
                woodSet.add(entry.getKey());
            }
            if (state.contains("_leaves") && state.contains("distance=")) {
                int idx = state.indexOf("distance=") + "distance=".length();
                int end = idx;
                while (end < state.length() && Character.isDigit(state.charAt(end))) {
                    end++;
                }
                int dist = Integer.parseInt(state.substring(idx, end));
                Assertions.assertTrue(dist >= 1 && dist <= 7,
                        label + ": invalid leaf distance " + dist + " in " + state);
                leafDistances.put(entry.getKey(), dist);
            }
        }

        // Verify leaf-decay connectivity when wood exists
        if (!woodSet.isEmpty()) {
            for (Map.Entry<TreeModel.BlockPos, Integer> le : leafDistances.entrySet()) {
                int d = le.getValue();
                if (d >= 7) continue;
                TreeModel.BlockPos pos = le.getKey();
                boolean connected = false;
                for (int[] off : ORTHOGONAL) {
                    TreeModel.BlockPos nb = new TreeModel.BlockPos(pos.x() + off[0], pos.y() + off[1], pos.z() + off[2]);
                    if (d == 1 && woodSet.contains(nb)) {
                        connected = true;
                        break;
                    } else if (d > 1) {
                        Integer nbDist = leafDistances.get(nb);
                        if ((nbDist != null && nbDist == d - 1) || woodSet.contains(nb)) {
                            connected = true;
                            break;
                        }
                    }
                }
                Assertions.assertTrue(connected,
                        label + ": leaf at " + pos + " with distance=" + d + " has no supporting orthogonal neighbor");
            }
        }

        // Verify GrowthPlanner stage invariants & exact partition coverage
        List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, 200L);
        Assertions.assertFalse(stages.isEmpty(), label + ": GrowthPlanner must produce at least 1 stage");
        int prevY = bounds[1] - 1;
        long prevEnd = 0L;
        int partitionedCount = 0;
        for (int i = 0; i < stages.size(); i++) {
            GrowthPlanner.Stage st = stages.get(i);
            Assertions.assertEquals(i, st.index(), label + ": stage index mismatch");
            Assertions.assertTrue(st.revealUpToY() > prevY,
                    label + ": stage revealUpToY must strictly increase (" + st.revealUpToY() + " <= " + prevY + ")");
            Assertions.assertTrue(st.startTick() == prevEnd && st.endTick() >= st.startTick(),
                    label + ": stage ticks must be monotonic");
            for (TreeModel.BlockPos pos : blocks.keySet()) {
                if (pos.y() > prevY && pos.y() <= st.revealUpToY()) {
                    partitionedCount++;
                }
            }
            prevY = st.revealUpToY();
            prevEnd = st.endTick();
        }
        Assertions.assertEquals(bounds[4], prevY, label + ": final stage must reach maxY");
        Assertions.assertEquals(200L, prevEnd, label + ": final stage must end at totalTime");
        Assertions.assertEquals(blocks.size(), partitionedCount,
                label + ": additive growth bands must partition 100% of model blocks");

        // Verify SegmentedTreeModel.orderForPlacement preserves total block count
        List<TreeModel.BlockPos> positions = new ArrayList<>(blocks.keySet());
        List<TreeModel.BlockPos> ordered = SegmentedTreeModel.orderForPlacement(positions, blocks);
        Assertions.assertEquals(blocks.size(), ordered.size(),
                label + ": SegmentedTreeModel.orderForPlacement must preserve exact block count");

        // Verify SegmentedTreeModel per-chunk slicing at chunk boundary (15, 64, 15) preserves all blocks
        SegmentedTreeModel segmented = new SegmentedTreeModel(model);
        int ox = 15, oy = 64, oz = 15;
        int slicedTotal = 0;
        for (long ck : segmented.touchedChunks(ox, oz)) {
            int cx = TreeModel.chunkX(ck);
            int cz = TreeModel.chunkZ(ck);
            Map<TreeModel.BlockPos, String> slice = segmented.sliceForChunk(ox, oy, oz, cx, cz);
            Assertions.assertFalse(slice.isEmpty(), label + ": touched chunk slice must not be empty");
            slicedTotal += slice.size();
        }
        Assertions.assertEquals(blocks.size(), slicedTotal,
                label + ": sum of sliceForChunk across touchedChunks must equal total block count");
    }

    private static void assertValidBlockStateSyntax(String label, String state) {
        Assertions.assertNotNull(state, label + ": block state must not be null");
        Assertions.assertFalse(state.isBlank(), label + ": block state must not be blank");
        Assertions.assertFalse(state.contains("_tmp"), label + ": uncleaned rotation temp token in " + state);
        Assertions.assertFalse(state.contains("NaN"), label + ": NaN in block state " + state);

        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        if (open < 0 && close < 0) {
            Assertions.assertTrue(state.contains(":"), label + ": expected namespaced block id: " + state);
            return;
        }
        Assertions.assertTrue(open > 0 && close == state.length() - 1 && state.indexOf('[', open + 1) < 0,
                label + ": malformed brackets in block state: " + state);

        String blockId = state.substring(0, open).toLowerCase(Locale.ROOT);
        String propsRaw = state.substring(open + 1, close);
        Assertions.assertFalse(propsRaw.isEmpty(), label + ": empty bracket properties in " + state);

        boolean isMultiface = blockId.endsWith("glow_lichen")
                || blockId.endsWith(":vine")
                || blockId.endsWith("sculk_vein")
                || blockId.endsWith("resin_clump");
        Assertions.assertFalse(blockId.endsWith("brown_mushroom") || blockId.endsWith("red_mushroom"),
                label + ": mushroom blocks do not accept block-state properties: " + state);

        for (String pair : propsRaw.split(",")) {
            int eq = pair.indexOf('=');
            Assertions.assertTrue(eq > 0 && eq < pair.length() - 1,
                    label + ": invalid key=value property '" + pair + "' in " + state);
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            if (isMultiface) {
                Assertions.assertNotEquals("facing", k,
                        label + ": multiface block must not use facing=" + v + " in " + state);
            }
            switch (k) {
                case "axis" -> Assertions.assertTrue(VALID_AXES.contains(v),
                        label + ": invalid axis=" + v + " in " + state);
                case "facing" -> Assertions.assertTrue(VALID_FACINGS.contains(v),
                        label + ": invalid facing=" + v + " in " + state);
                case "north", "south", "east", "west", "up", "down", "persistent", "waterlogged", "hanging", "berries" ->
                        Assertions.assertTrue(VALID_BOOLS.contains(v),
                                label + ": invalid boolean property " + k + "=" + v + " in " + state);
                default -> {
                    // other numeric/enum properties (distance, age, stage, etc.)
                }
            }
        }
    }

    private static TreeModel copyModel(TreeModel src) {
        TreeModel copy = new TreeModel(src.getSpeciesId());
        src.getBlocks().forEach((pos, st) -> copy.setBlock(pos.x(), pos.y(), pos.z(), st));
        return copy;
    }

    private static TreeSpecies createFuzzSpecies(String id, long totalTime, int height, double trunkWidth) {
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", "minecraft:oak_leaves", height, height,
                "OAK", trunkWidth, "TAPER", Map.of(), true,
                0.2, 0.0, "SINUSOIDAL", Map.of(), "LINEAR", Map.of(),
                null, 0.0, 0.0,
                new TreeSpecies.CanopyParams("ROUNDED", 0.9,
                        List.of(new TreeSpecies.CanopyParams.Layer(Math.max(1, height - 2), 2.0),
                                new TreeSpecies.CanopyParams.Layer(height, 1.5)),
                        null, null, 0.0, false, 1.0, null, null, 1.0),
                true, 1, 1, List.of());

        return new TreeSpecies(
                id, id, "minecraft", id,
                Set.of("minecraft:plains", "minecraft:forest"), "OAK_SAPLING", true, true,
                8, 2, -1, "linear", "surface_structures",
                List.of(), params, null, null, 1,
                new TreeSpecies.GrowthParams(true, totalTime), null);
    }

    private static final class FuzzPlatform implements Platform {
        private final Path root = Paths.get("build/test-fuzz-" + System.nanoTime());
        final Map<String, String> placedBlocks = new HashMap<>();
        final Set<String> obstructedPositions = new HashSet<>();
        final Map<String, String> chunkData = new HashMap<>();
        final Map<String, String> growthData = new HashMap<>();
        final Deque<Runnable> scheduledTasks = new ArrayDeque<>();
        final DispatchingWorldEventHook eventHook = new DispatchingWorldEventHook();

        boolean runNextScheduledTask() {
            Runnable task = scheduledTasks.pollFirst();
            if (task == null) return false;
            task.run();
            return true;
        }

        @Override public Logger logger() { return Logger.getLogger("TreeGenFuzzTest"); }
        @Override public Path getRootFolder() { return root; }
        @Override public Path getWorldFolder(String worldName) { return root.resolve(worldName); }
        @Override public List<String> getWorldNames() { return List.of("world"); }
        @Override public WorldEventHook getWorldEventHook() { return eventHook; }
        @Override public Set<String> getOnlinePlayerNames() { return Set.of("FuzzBot"); }
        @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
            scheduledTasks.addLast(task);
        }
        @Override public void scheduleDelayedTask(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
            scheduledTasks.addLast(task);
        }
        @Override public String getBiomeKey(String worldName, int x, int y, int z) { return "minecraft:forest"; }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return true; }
        @Override public void giveSapling(UUID playerId, String speciesId, int amount) {}
        @Override public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
            String key = x + "," + y + "," + z;
            if (blockData != null && blockData.endsWith("air")) {
                placedBlocks.remove(key);
            } else {
                placedBlocks.put(key, blockData);
            }
            return true;
        }
        @Override public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
            blocks.forEach((pos, data) -> placedBlocks.put(pos.x() + "," + pos.y() + "," + pos.z(), data));
            return true;
        }
        @Override public boolean setChunkBlocks(String worldName, int chunkX, int chunkZ, Map<BlockPos3i, String> blocks) {
            blocks.forEach((pos, data) -> placedBlocks.put(pos.x() + "," + pos.y() + "," + pos.z(), data));
            return true;
        }
        @Override public boolean isReplaceable(String worldName, int x, int y, int z) {
            return !obstructedPositions.contains(x + "," + y + "," + z);
        }
        @Override public String getBlockState(String worldName, int x, int y, int z) {
            return placedBlocks.get(x + "," + y + "," + z);
        }
        @Override public int getHighestBlockY(String worldName, int x, int z) { return 64; }
        @Override public int getMinHeight(String worldName) { return -64; }
        @Override public int findTreeGroundY(String worldName, int x, int z) { return 64; }
        @Override public void sendMessage(UUID playerId, String message, boolean error) {}
        @Override public CallerLocation getCallerLocation(UUID callerId) {
            return new CallerLocation("world", 0, 64, 0);
        }
        @Override public String getChunkData(String worldName, int chunkX, int chunkZ) {
            return chunkData.get(worldName + ":" + chunkX + "," + chunkZ);
        }
        @Override public void setChunkData(String worldName, int chunkX, int chunkZ, String data) {
            String key = worldName + ":" + chunkX + "," + chunkZ;
            if (data == null || data.isBlank()) chunkData.remove(key);
            else chunkData.put(key, data);
        }
        @Override public String getGrowthData(String worldName, int chunkX, int chunkZ) {
            return growthData.get(worldName + ":" + chunkX + "," + chunkZ);
        }
        @Override public void setGrowthData(String worldName, int chunkX, int chunkZ, String data) {
            String key = worldName + ":" + chunkX + "," + chunkZ;
            if (data == null || data.isBlank()) growthData.remove(key);
            else growthData.put(key, data);
        }
    }
}
