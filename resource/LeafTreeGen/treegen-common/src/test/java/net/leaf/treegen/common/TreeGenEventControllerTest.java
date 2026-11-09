package net.leaf.treegen.common;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.server.api.BlockBreakContext;
import io.github.dailystruggle.server.api.ChunkLoadContext;
import io.github.dailystruggle.server.api.DispatchingWorldEventHook;
import io.github.dailystruggle.server.api.LeavesDecayContext;
import io.github.dailystruggle.server.api.PlayerInteractBlockContext;
import io.github.dailystruggle.server.api.SaplingGrowContext;
import io.github.dailystruggle.server.api.TaggedItemSpec;
import io.github.dailystruggle.server.api.WorldEventHook;
import net.leaf.treegen.common.command.CallerLocation;
import net.leaf.treegen.common.command.LeafTreeGenCommand;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Verifies {@link TreeGenEventController}, {@link SaplingItem}, {@link TreeBlockBatcher},
 * {@link LeafTreeGenRuntime}, and the unified {@link LeafTreeGenCommand} wired through
 * {@link Platform} and {@link WorldEventHook}.
 */
public class TreeGenEventControllerTest {

    @TempDir
    Path tempDir;

    private StubTreeGenPlatform platform;
    private TreeRegistry registry;
    private ProceduralGenerator proceduralGenerator;
    private TreeFarmStore farmStore;
    private GrowthManager growthManager;
    private TreeSpecies oakSpecies;
    private TreeGenConfig config;
    private TreeGenEventController controller;

    @BeforeEach
    void setUp() {
        platform = new StubTreeGenPlatform(tempDir);
        registry = new TreeRegistry(platform);
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log",
                "minecraft:oak_leaves",
                6,
                6,
                "OAK",
                1.0,
                "CONSTANT",
                Map.of(),
                false,
                0.0,
                0.0,
                "CONSTANT",
                Map.of(),
                "LINEAR",
                Map.of(),
                null,
                0.5,
                1.0,
                new TreeSpecies.CanopyParams(
                        "DENSITY",
                        1.0,
                        List.of(new TreeSpecies.CanopyParams.Layer(5, 2.0)),
                        null,
                        null,
                        0.0,
                        true,
                        1.0,
                        null,
                        null,
                        1.0),
                false,
                0,
                0,
                List.of());
        oakSpecies = new TreeSpecies(
                "custom-oak",
                "Custom Oak",
                "minecraft",
                "custom-oak",
                Set.of("minecraft:plains"),
                "OAK_SAPLING",
                true,
                true,
                1,
                0,
                123,
                "linear",
                "surface_structures",
                List.of(new TreeSpecies.TreeVariant("minecraft:custom-oak/oak_v1", 1)),
                params,
                null,
                null,
                1);
        Map<String, TreeSpecies> map = new LinkedHashMap<>();
        map.put(oakSpecies.id(), oakSpecies);
        config = new TreeGenConfig(map, "test-pack", "desc", TreeGenConfig.GenerationMode.PROCEDURAL, 4.0, 200);
        config.setSaplingDropChance(1.0);
        proceduralGenerator = new ProceduralGenerator(platform, registry);
        proceduralGenerator.setConfigSpecies(map);
        farmStore = new TreeFarmStore(platform);
        growthManager = new GrowthManager(platform, proceduralGenerator, farmStore, () -> config);
        controller = new TreeGenEventController(
                platform, () -> config, () -> registry, proceduralGenerator, growthManager, farmStore);
        controller.register();
    }

    @Test
    void testSaplingInteractPlantsProceduralTreeAndConsumesSapling() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger consumed = new AtomicInteger();
        PlayerInteractBlockContext ctx = new PlayerInteractBlockContext(
                playerId,
                "world",
                10,
                63,
                10,
                10,
                64,
                10,
                true,
                false,
                false,
                "minecraft:oak_sapling",
                Map.of(Platform.TAG_TREE_SPECIES, "custom-oak"),
                perm -> true,
                consumed::addAndGet);

        platform.hook.firePlayerInteractBlock(ctx);

        Assertions.assertTrue(ctx.isCancelled(), "Magical sapling interaction must be cancelled");
        Assertions.assertEquals(1, consumed.get(), "One sapling should be consumed in survival mode");
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Custom Oak springs up!")));
        Assertions.assertFalse(platform.placedBlocks.isEmpty(), "Procedural blocks should be placed");
        Assertions.assertEquals("custom-oak", farmStore.resolve("world", 10, 64, 10));
    }

    @Test
    void testSaplingInteractRejectsWaterAndMissingPermission() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger consumed = new AtomicInteger();
        PlayerInteractBlockContext noPerm = new PlayerInteractBlockContext(
                playerId,
                "world",
                0,
                63,
                0,
                0,
                64,
                0,
                true,
                false,
                false,
                "minecraft:oak_sapling",
                Map.of(Platform.TAG_TREE_SPECIES, "custom-oak"),
                perm -> false,
                consumed::addAndGet);
        platform.hook.firePlayerInteractBlock(noPerm);
        Assertions.assertTrue(noPerm.isCancelled());
        Assertions.assertEquals(0, consumed.get());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("can't plant magical saplings")));

        platform.messages.clear();
        PlayerInteractBlockContext onWater = new PlayerInteractBlockContext(
                playerId,
                "world",
                0,
                63,
                0,
                0,
                64,
                0,
                true,
                false,
                true,
                "minecraft:oak_sapling",
                Map.of(Platform.TAG_TREE_SPECIES, "custom-oak"),
                perm -> true,
                consumed::addAndGet);
        platform.hook.firePlayerInteractBlock(onWater);
        Assertions.assertTrue(onWater.isCancelled());
        Assertions.assertEquals(0, consumed.get());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("won't take root on water")));
    }

    @Test
    void testLeavesDecayAndBlockBreakDropCustomSaplingsViaTaggedItemSpec() {
        platform.hook.fireLeavesDecay(
                new LeavesDecayContext("world", 5, 70, 5, "minecraft:oak_leaves", "minecraft:plains"));
        Assertions.assertEquals(1, platform.droppedItems.size());
        Assertions.assertEquals("custom-oak", platform.droppedItems.get(0).customTags().get(Platform.TAG_TREE_SPECIES));

        platform.hook.fireBlockBreak(
                new BlockBreakContext(UUID.randomUUID(), "world", 5, 71, 5, "minecraft:oak_leaves", "minecraft:plains"));
        Assertions.assertEquals(2, platform.droppedItems.size());
    }

    @Test
    void testChunkLoadPlacesProceduralTreeInMatchingBiome() {
        platform.hook.fireChunkLoad(new ChunkLoadContext("world", 0, 0, true, 999L));
        Assertions.assertFalse(platform.placedBlocks.isEmpty(),
                "New chunk load in matching biome should place procedural tree");
    }

    @Test
    void testSaplingGrowReplacesVanillaSaplingInCustomBiome() {
        platform.placedBlocks.put(new TreeModel.BlockPos(12, 64, 12), "minecraft:oak_sapling");
        SaplingGrowContext ctx = new SaplingGrowContext(
                "world",
                12,
                64,
                12,
                "minecraft:oak_sapling",
                "minecraft:plains",
                false);

        platform.hook.fireSaplingGrow(ctx);
        Assertions.assertTrue(ctx.isCancelled());
        Assertions.assertEquals("custom-oak", farmStore.resolve("world", 12, 64, 12));
    }

    @Test
    void testVanillaSaplingGrowsNormallyInFeatureTreesBiomeAndWithEmptyBiomeHelpers() {
        TreeSpecies forestFeatureOnly = new TreeSpecies(
                "forest", "Forest", "minecraft", "forest",
                Set.of("minecraft:forest"), "OAK_SAPLING", true, true,
                1, 0, -1, "linear", "surface_structures",
                List.of(), null, null, null, 1,
                TreeSpecies.GrowthParams.disabled(),
                new TreeSpecies.FeatureConfig(10, List.of(new TreeSpecies.WeightedFeature("minecraft:oak", 8))));

        TreeSpecies helperEmptyBiomes = new TreeSpecies(
                "oak-small-mid", "Oak Small Mid", "minecraft", "oak-small-mid",
                Set.of(), "OAK_SAPLING", true, true,
                16, 4, -1, "linear", "surface_structures",
                List.of(), null, null, Map.of(), 1);

        Map<String, TreeSpecies> speciesMap = new LinkedHashMap<>();
        speciesMap.put("forest", forestFeatureOnly);
        speciesMap.put("oak-small-mid", helperEmptyBiomes);
        config = new TreeGenConfig(speciesMap, "pack", "desc", TreeGenConfig.GenerationMode.DATAPACK);
        proceduralGenerator.setConfigSpecies(speciesMap);

        SaplingGrowContext ctx = new SaplingGrowContext(
                "world", 10, 64, 10, "minecraft:oak_sapling", "minecraft:forest", false);
        platform.hook.fireSaplingGrow(ctx);

        Assertions.assertFalse(ctx.isCancelled(),
                "Bonemeal / natural vanilla sapling growth in a feature-trees biome must NOT be cancelled");
    }

    @Test
    void testCustomBiomeSpeciesWithStagedGrowthDelegatesToGrowthManager() {
        TreeSpecies cherry = new TreeSpecies(
                "cherry-grove", "Cherry Grove", "minecraft", "cherry-grove",
                Set.of("minecraft:cherry_grove"), "CHERRY_SAPLING", true, true,
                4, 1, -1, "linear", "surface_structures",
                List.of(), oakSpecies.procedural(), null, null, 1,
                new TreeSpecies.GrowthParams(true, 1200L), null);

        Map<String, TreeSpecies> speciesMap = Map.of("cherry-grove", cherry);
        config = new TreeGenConfig(speciesMap, "pack", "desc", TreeGenConfig.GenerationMode.DATAPACK);
        proceduralGenerator.setConfigSpecies(speciesMap);

        SaplingGrowContext ctx = new SaplingGrowContext(
                "world", 30, 64, 30, "minecraft:cherry_sapling", "minecraft:cherry_grove", false);
        platform.hook.fireSaplingGrow(ctx);

        Assertions.assertTrue(ctx.isCancelled(),
                "Staged growth species should start growth via GrowthManager and cancel vanilla sapling growth");
        Assertions.assertEquals("cherry-grove", farmStore.resolve("world", 30, 64, 30));
    }

    @Test
    void testTreeBlockBatcherFiltersPlainAirAndTracksTelemetry() {
        TreeBlockBatcher batcher = new TreeBlockBatcher(platform, () -> config);
        Map<TreeModel.BlockPos, String> blocks = new LinkedHashMap<>();
        blocks.put(new TreeModel.BlockPos(0, 64, 0), "minecraft:oak_log");
        blocks.put(new TreeModel.BlockPos(0, 65, 0), "minecraft:air");
        blocks.put(new TreeModel.BlockPos(0, 66, 0), "minecraft:void_air");

        Assertions.assertTrue(batcher.placeBlocks("world", 0, 0, blocks));
        Assertions.assertEquals(2L, batcher.totalBlocksPlaced());
        Assertions.assertEquals(1L, batcher.totalBatches());
        Assertions.assertEquals("minecraft:oak_log", platform.placedBlocks.get(new TreeModel.BlockPos(0, 64, 0)));
        Assertions.assertNull(platform.placedBlocks.get(new TreeModel.BlockPos(0, 65, 0)));
        Assertions.assertEquals("minecraft:void_air", platform.placedBlocks.get(new TreeModel.BlockPos(0, 66, 0)));

        batcher.resetStats();
        Assertions.assertEquals(0L, batcher.totalBlocksPlaced());
    }

    @Test
    void testUnifiedLeafTreeGenCommandViaPlatformRegisterCommands() {
        AtomicBoolean reloaded = new AtomicBoolean();
        LeafTreeGenCommand root = new LeafTreeGenCommand(
                platform, () -> config, () -> registry, proceduralGenerator, () -> reloaded.set(true));
        platform.registerCommands(root);
        Assertions.assertSame(root, platform.registeredRoot);

        UUID caller = UUID.randomUUID();
        platform.callerLocations.put(caller, new CallerLocation("world", 10, 64, 10));

        List<String> giveTabs = root.onTabComplete(caller, p -> true, new String[]{"give", "player="});
        Assertions.assertTrue(giveTabs.stream().anyMatch(s -> s.contains("Alice")));

        root.onCommand(caller, p -> true, msg -> platform.sendMessage(caller, msg, false), new String[]{"debug"});
        CommandsAPI.execute();
        root.onCommand(caller, p -> true, msg -> platform.sendMessage(caller, msg, false), new String[]{"stats"});
        CommandsAPI.execute();
        root.onCommand(caller, p -> true, msg -> platform.sendMessage(caller, msg, false), new String[]{"stats", "reset=true"});
        CommandsAPI.execute();
        root.onCommand(caller, p -> true, msg -> platform.sendMessage(caller, msg, false),
                new String[]{"give", "species=custom-oak", "player=Alice", "amount=2"});
        CommandsAPI.execute();
        root.onCommand(caller, p -> true, msg -> platform.sendMessage(caller, msg, false), new String[]{"reload"});
        CommandsAPI.execute();

        Assertions.assertTrue(reloaded.get(), "reload subcommand should invoke reload hook");
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("--- LeafTreeGen Debug ---")));
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("--- LeafTreeGen Tick Budget Stats ---")));
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Stats reset.")));
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Gave 2x Custom Oak") && m.contains("to Alice.")));
        Assertions.assertEquals(1, platform.givenItems.size());
        Assertions.assertEquals("custom-oak", platform.givenItems.get(0).customTags().get(Platform.TAG_TREE_SPECIES));

        TaggedItemSpec variantSpec = SaplingItem.create(oakSpecies, 3, "oak_v1");
        Assertions.assertEquals("oak_v1", variantSpec.customTags().get(Platform.TAG_TREE_VARIANT));
        Assertions.assertEquals("oak_v1", variantSpec.customTags().get(SaplingItem.TREE_VARIANT_KEY));
    }

    @Test
    public void testDeferredOverhangPlacementWaitsForNeighborChunkLoad() {
        // Restrict loaded chunks strictly to chunk (0, 0) initially.
        platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(0, 0)));

        try (TreeGenEventController controller = new TreeGenEventController(
                platform, () -> config, () -> registry, proceduralGenerator, growthManager, farmStore)) {
            controller.register();

            // Plant a custom-oak sapling at (15, 64, 15) on the corner of chunk (0, 0) so its
            // canopy overhangs into unloaded neighbor chunks (1, 0), (0, 1), and (1, 1).
            UUID playerId = UUID.randomUUID();
            PlayerInteractBlockContext plantCtx = new PlayerInteractBlockContext(
                    playerId, "world", 15, 63, 15, 15, 64, 15,
                    true, false, false,
                    "minecraft:oak_sapling",
                    Map.of(Platform.TAG_TREE_SPECIES, "custom-oak"),
                    perm -> true,
                    amt -> {});
            platform.hook.firePlayerInteractBlock(plantCtx);

            Assertions.assertFalse(platform.placedBlocks.isEmpty(),
                    "Loaded trunk chunk (0, 0) must receive its tree slice immediately");
            for (TreeModel.BlockPos pos : platform.placedBlocks.keySet()) {
                Assertions.assertEquals(0, pos.x() >> 4,
                        "Unloaded neighbor chunk X must not be written before onChunkLoad: " + pos);
                Assertions.assertEquals(0, pos.z() >> 4,
                        "Unloaded neighbor chunk Z must not be written before onChunkLoad: " + pos);
            }
            Assertions.assertTrue(controller.pendingOverhangChunkCount() > 0,
                    "Overhanging slices for unloaded neighbor chunks must be queued");

            int blocksInOriginChunk = platform.placedBlocks.size();

            // Now load neighbor chunks (1, 0), (0, 1), (1, 1) and (-1, 0), (0, -1) and verify
            // deferred overhangs and TreeFarmStore regions flush automatically.
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    if (cx == 0 && cz == 0) continue;
                    platform.loadedChunks.add(TreeModel.chunkKey(cx, cz));
                    platform.hook.fireChunkLoad(new ChunkLoadContext("world", cx, cz, false, 42L));
                }
            }

            Assertions.assertEquals(0, controller.pendingOverhangChunkCount(),
                    "All deferred overhang slices must flush once neighbor chunks load");
            Assertions.assertTrue(platform.placedBlocks.size() > blocksInOriginChunk,
                    "Flushing neighbor chunks must place the deferred canopy overhang blocks");
            Assertions.assertEquals("custom-oak", farmStore.resolve("world", 16, 68, 15),
                    "TreeFarmStore region deferred for neighbor chunk (1, 0) must flush on chunk load");
        }
    }

    @Test
    void testSaplingConsumedSynchronouslyBeforeDeferredTaskRunsAndRefundedOnFailure() {
        UUID playerId = UUID.randomUUID();
        AtomicInteger stackSize = new AtomicInteger(1);
        List<Runnable> deferredQueue = new ArrayList<>();
        platform.deferredTasks = deferredQueue;

        // Player clicks two different target blocks before the next tick runs while holding 1 sapling.
        for (int i = 0; i < 2; i++) {
            int tx = 10 + i * 20;
            PlayerInteractBlockContext ctx = new PlayerInteractBlockContext(
                    playerId,
                    "world",
                    tx,
                    63,
                    10,
                    tx,
                    64,
                    10,
                    true,
                    false,
                    false,
                    "minecraft:oak_sapling",
                    Map.of(Platform.TAG_TREE_SPECIES, "custom-oak"),
                    perm -> stackSize.get() > 0,
                    amt -> {
                        if (stackSize.get() < amt) {
                            throw new IllegalStateException("Cannot consume from empty stack");
                        }
                        stackSize.addAndGet(-amt);
                    });
            if (stackSize.get() > 0) {
                platform.hook.firePlayerInteractBlock(ctx);
            }
        }

        Assertions.assertEquals(0, stackSize.get(),
                "Sapling must be consumed synchronously in onPlayerInteractBlock before deferred task runs");
        Assertions.assertEquals(1, deferredQueue.size(),
                "Only one tree placement task should be scheduled from a 1-item sapling stack");
        deferredQueue.forEach(Runnable::run);
        deferredQueue.clear();
        Assertions.assertEquals(0, platform.droppedItems.size(),
                "Successful placement must not refund the sapling");

        // Now verify that when staged growth fails pre-flight footprint check, the consumed sapling is refunded.
        TreeSpecies stagedOak = new TreeSpecies(
                oakSpecies.id(), oakSpecies.displayName(), "minecraft", oakSpecies.id(),
                Set.of("minecraft:plains"), "OAK_SAPLING", true, true,
                1, 0, 123, "linear", "surface_structures",
                List.of(), oakSpecies.procedural(), null, null, 1,
                new TreeSpecies.GrowthParams(true, 200L), null);
        Map<String, TreeSpecies> speciesMap = Map.of(stagedOak.id(), stagedOak);
        config = new TreeGenConfig(speciesMap, "pack", "desc", TreeGenConfig.GenerationMode.PROCEDURAL);
        proceduralGenerator.setConfigSpecies(speciesMap);

        // Obstruct block directly above target (30, 65, 30) so startGrowth fails.
        platform.placedBlocks.put(new TreeModel.BlockPos(30, 65, 30), "minecraft:stone");
        stackSize.set(1);
        PlayerInteractBlockContext obstructedCtx = new PlayerInteractBlockContext(
                playerId,
                "world",
                30,
                63,
                30,
                30,
                64,
                30,
                true,
                false,
                false,
                "minecraft:oak_sapling",
                Map.of(Platform.TAG_TREE_SPECIES, stagedOak.id()),
                perm -> true,
                stackSize::addAndGet);
        platform.hook.firePlayerInteractBlock(obstructedCtx);
        Assertions.assertEquals(2, stackSize.get());
        deferredQueue.forEach(Runnable::run);
        deferredQueue.clear();
        Assertions.assertEquals(1, platform.droppedItemCount());
        Assertions.assertEquals(stagedOak.id(),
                platform.lastDroppedItem().customTags().get(Platform.TAG_TREE_SPECIES));
    }

    @Test
    void testFlushPendingOverhangsSkipsNonReplaceablePlayerBuildBlocks() {
        platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(0, 0)));

        // Plant a tree at (15, 64, 15) so overhangs into neighbor chunks are deferred.
        UUID playerId = UUID.randomUUID();
        PlayerInteractBlockContext plantCtx = new PlayerInteractBlockContext(
                playerId, "world", 15, 63, 15, 15, 64, 15,
                true, false, false,
                "minecraft:oak_sapling",
                Map.of(Platform.TAG_TREE_SPECIES, "custom-oak"),
                perm -> true,
                amt -> {});
        platform.hook.firePlayerInteractBlock(plantCtx);
        Assertions.assertTrue(controller.pendingOverhangChunkCount() > 0);

        // Place a solid player build block (chest) throughout neighbor chunks (1, 0), (0, 1), (1, 1).
        List<TreeModel.BlockPos> chestPositions = new ArrayList<>();
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                if (cx == 0 && cz == 0) continue;
                for (int y = 64; y <= 75; y++) {
                    for (int x = cx * 16; x < cx * 16 + 16; x++) {
                        for (int z = cz * 16; z < cz * 16 + 16; z++) {
                            TreeModel.BlockPos p = new TreeModel.BlockPos(x, y, z);
                            platform.placedBlocks.put(p, "minecraft:chest");
                            chestPositions.add(p);
                        }
                    }
                }
            }
        }

        // Load neighbor chunks and flush overhangs.
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                if (cx == 0 && cz == 0) continue;
                platform.loadedChunks.add(TreeModel.chunkKey(cx, cz));
                platform.hook.fireChunkLoad(new ChunkLoadContext("world", cx, cz, false, 42L));
            }
        }

        for (TreeModel.BlockPos chestPos : chestPositions) {
            Assertions.assertEquals("minecraft:chest", platform.placedBlocks.get(chestPos),
                    "Deferred overhang flush must re-check isReplaceable and skip non-replaceable blocks at " + chestPos);
        }
    }
    @Test
    void testChunkLoadCompletesHaloPiecesOnFirstLoadWhenChunksUnloadImmediately() throws Exception {
        // Save an already-generated chunk (0, 0) to disk under the 26.1+ unified overworld path
        // (world/dimensions/minecraft/overworld/region/r.0.0.lca) with a chest at (15, 68, 8) and
        // air elsewhere, then unload chunk (0, 0) before chunk (1, 0) generates.
        Path regionDir = java.nio.file.Files.createDirectories(
                platform.getWorldFolder("world")
                        .resolve("dimensions")
                        .resolve("minecraft")
                        .resolve("overworld")
                        .resolve("region"));
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 0);
        root.put("zPos", 0);
        root.put("Status", "minecraft:full");
        LinkedHashMap<String, Object> sec4 = new LinkedHashMap<>();
        sec4.put("Y", (byte) 4);
        LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> airEntry = new LinkedHashMap<>();
        airEntry.put("Name", "minecraft:air");
        LinkedHashMap<String, Object> chestEntry = new LinkedHashMap<>();
        chestEntry.put("Name", "minecraft:chest");
        bs.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, List.of(airEntry, chestEntry)));
        int entryIdx = io.github.dailystruggle.anvil.PackedPaletteDecoder.entryIndex(15, 4, 8); // (15, 68, 8) = chest
        long[] packed = new long[256];
        packed[entryIdx / 16] = 1L << ((entryIdx % 16) * 4);
        bs.put("data", packed);
        sec4.put("block_states", bs);
        root.put("sections", new io.github.dailystruggle.anvil.Nbt.NbtList(
                io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, List.of(sec4)));
        try (io.github.dailystruggle.anvil.LcaRegionFileStorage storage =
                new io.github.dailystruggle.anvil.LcaRegionFileStorage(regionDir)) {
            storage.writeChunkNbtBytes(0, 0, io.github.dailystruggle.anvil.Nbt.writeNamedRoot("", root));
        }
        io.github.dailystruggle.anvil.RegionFormatDetector.INSTANCE.invalidateCache();
        io.github.dailystruggle.anvil.RegionFileResolver.invalidateMemo();

        // Only chunk (1, 0) is loaded in memory; chunk (0, 0) is already saved & unloaded on disk,
        // and chunk (2, 0) has not been generated yet.
        platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(1, 0)));

        // Place oakSpecies at (16, 64, 8) on the west border of chunk (1, 0) so its canopy
        // overhangs west into saved chunk (0, 0).
        controller.placeProceduralByChunk("world", 16, 64, 8, oakSpecies, new java.util.Random(42L));

        Assertions.assertFalse(platform.placedBlocks.isEmpty(),
                "Live origin chunk (1, 0) must receive its tree slice immediately");
        Assertions.assertEquals(0, controller.pendingOverhangChunkCount(),
                "Overhang into already-saved chunk (0, 0) must be retroactively patched on disk rather than stranded in pendingChunkSlices");

        // Verify saved chunk (0, 0) on disk received the overhanging leaves via storage-agnostic anvil-api RMW.
        io.github.dailystruggle.anvil.RegionFileResolver.ResolvedRegion resolved =
                io.github.dailystruggle.anvil.RegionFileResolver.resolveExisting(
                        platform.getWorldFolder("world"), "", 0, 0);
        Assertions.assertNotNull(resolved);
        io.github.dailystruggle.anvil.AnvilChunkView view =
                io.github.dailystruggle.anvil.LcaRegionReader.readChunkView(
                        java.nio.file.Files.readAllBytes(resolved.path()), 0, 0);
        Assertions.assertNotNull(view);
        Assertions.assertEquals("minecraft:chest", view.blockIdAt(15, 68, 8),
                "Non-replaceable chest in saved chunk (0, 0) must be preserved during retroactive RMW");
        boolean foundPatchedLeafInSavedChunk = false;
        for (int lx = 13; lx <= 15; lx++) {
            for (int y = 64; y < 75; y++) {
                for (int lz = 6; lz <= 10; lz++) {
                    String blockId = view.blockIdAt(lx, y, lz);
                    if ("minecraft:oak_leaves".equals(blockId) || "minecraft:oak_log".equals(blockId)) {
                        foundPatchedLeafInSavedChunk = true;
                    }
                }
            }
        }
        Assertions.assertTrue(foundPatchedLeafInSavedChunk,
                "Saved chunk (0, 0) on disk must contain retroactively patched overhanging canopy blocks");
    }

    @Test
    void testOnChunkLoadHandlesBiomeBordersAndSkipsUnderwaterPondFloor() {
        // 1. Biome border chunk: (8, 8) is minecraft:desert (disallowed by oakSpecies),
        // while every other column is minecraft:plains (allowed).
        platform.biomeOverrides.put(new TreeModel.BlockPos(8, 63, 8), "minecraft:desert");
        platform.hook.fireChunkLoad(new ChunkLoadContext("world", 0, 0, true, 42L));
        Assertions.assertFalse(platform.placedBlocks.isEmpty(),
                "Single-point exactBiome check at (x, y, z) must place tree even when chunk center (8, 8) is a different biome");

        // 2. Underwater pond/river floor: surface above ground (y = 64) is water.
        platform.placedBlocks.clear();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                platform.placedBlocks.put(new TreeModel.BlockPos(x, 64, z), "minecraft:water");
            }
        }
        platform.hook.fireChunkLoad(new ChunkLoadContext("world", 0, 0, true, 42L));
        long logsPlacedUnderwater = platform.placedBlocks.values().stream()
                .filter(v -> v.contains("oak_log"))
                .count();
        Assertions.assertEquals(0L, logsPlacedUnderwater,
                "Species without allow-water-growth must not be placed on submerged pond/river floors");
    }

    @Test
    void testOnChunkLoadCompletesOverhangFromPreexistingAdjacentChunksAcrossRegionBoundary() throws Exception {
        // Configure a wide-canopy species with spacing=2 so chunk (31, 0) in r.0.0 is selected
        // while chunk (32, 0) in r.1.0 is not selected as a tree origin itself.
        TreeSpecies.ProceduralParams wideParams = new TreeSpecies.ProceduralParams(
                "minecraft:spruce_log",
                "minecraft:spruce_leaves",
                10,
                10,
                "SPRUCE",
                1.0,
                "CONSTANT",
                Map.of(),
                false,
                0.0,
                0.0,
                "CONSTANT",
                Map.of(),
                "LINEAR",
                Map.of(),
                null,
                0.5,
                1.0,
                new TreeSpecies.CanopyParams(
                        "DENSITY",
                        1.0,
                        List.of(new TreeSpecies.CanopyParams.Layer(6, 15.0)),
                        null,
                        null,
                        0.0,
                        true,
                        1.0,
                        null,
                        null,
                        1.0),
                false,
                0,
                0,
                List.of());
        TreeSpecies borderSpruce = new TreeSpecies(
                "border-spruce",
                "Border Spruce",
                "minecraft",
                "border-spruce",
                Set.of("minecraft:snowy_taiga"),
                "SPRUCE_SAPLING",
                true,
                true,
                2,
                0,
                321,
                "linear",
                "surface_structures",
                List.of(),
                wideParams,
                null,
                null,
                1);
        Map<String, TreeSpecies> speciesMap = new LinkedHashMap<>();
        speciesMap.put(borderSpruce.id(), borderSpruce);
        config = new TreeGenConfig(
                speciesMap, "test-pack", "desc", TreeGenConfig.GenerationMode.PROCEDURAL, 4.0, 200);
        proceduralGenerator.setConfigSpecies(speciesMap);

        long chosenSeed = -1L;
        for (long s = 1L; s < 10_000L; s++) {
            int[] t31 = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(31, 0, 2, s, borderSpruce.effectiveSalt());
            int[] t32 = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(32, 0, 2, s, borderSpruce.effectiveSalt());
            java.util.Random gr = new java.util.Random(
                    io.github.dailystruggle.rtp.common.predict.DistributionSampler
                            .variantSeed(borderSpruce.id(), (((long) 31) << 32) ^ (0 & 0xFFFFFFFFL) ^ s));
            int localX = gr.nextInt(16);
            if (t31[0] == 31 && t31[1] == 0 && (t32[0] != 32 || t32[1] != 0) && localX >= 14) {
                chosenSeed = s;
                break;
            }
        }
        Assertions.assertTrue(chosenSeed > 0L, "Must find a deterministic seed targeting (31, 0) near east border but not (32, 0)");

        // Write preexisting chunk (31, 0) into r.0.0.lca on disk with grass_block ground at y=63
        // and biome minecraft:snowy_taiga, while chunk (32, 0) in r.1.0 is not yet generated.
        java.nio.file.Path regionDir = platform.getWorldFolder("world").resolve("region");
        java.nio.file.Files.createDirectories(regionDir);
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 31);
        root.put("zPos", 0);
        root.put("Status", "minecraft:full");
        LinkedHashMap<String, Object> sec3 = new LinkedHashMap<>();
        sec3.put("Y", (byte) 3); // worldY 48..63
        LinkedHashMap<String, Object> bs3 = new LinkedHashMap<>();
        LinkedHashMap<String, Object> grassEntry = new LinkedHashMap<>();
        grassEntry.put("Name", "minecraft:grass_block");
        bs3.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, List.of(grassEntry)));
        sec3.put("block_states", bs3);
        LinkedHashMap<String, Object> biomes3 = new LinkedHashMap<>();
        biomes3.put("palette", new io.github.dailystruggle.anvil.Nbt.NbtList(
                io.github.dailystruggle.anvil.Nbt.TAG_STRING, List.of("minecraft:snowy_taiga")));
        sec3.put("biomes", biomes3);
        root.put("sections", new io.github.dailystruggle.anvil.Nbt.NbtList(
                io.github.dailystruggle.anvil.Nbt.TAG_COMPOUND, List.of(sec3)));
        try (io.github.dailystruggle.anvil.LcaRegionFileStorage storage =
                new io.github.dailystruggle.anvil.LcaRegionFileStorage(regionDir)) {
            storage.writeChunkNbtBytes(31, 0, io.github.dailystruggle.anvil.Nbt.writeNamedRoot("", root));
        }
        io.github.dailystruggle.anvil.RegionFormatDetector.INSTANCE.invalidateCache();
        // Only chunk (32, 0) in region r.1.0 is loaded; preexisting chunk (31, 0) in r.0.0 is
        // unloaded on disk and pendingChunkSlices is empty (e.g. across a server restart).
        platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(32, 0)));
        platform.hook.fireChunkLoad(new ChunkLoadContext("world", 32, 0, true, chosenSeed));

        boolean foundOverhangInChunk32 = platform.placedBlocks.entrySet().stream()
                .anyMatch(e -> (e.getKey().x() >> 4) == 32
                        && (e.getKey().z() >> 4) == 0
                        && e.getValue().contains("spruce_leaves"));
        Assertions.assertTrue(foundOverhangInChunk32,
                "Loading chunk (32, 0) in r.1.0 must calculate and complete the overhanging tree from preexisting chunk (31, 0) in r.0.0 using its saved land data");

        // Also verify when a preexisting neighbor chunk (31, 0) is already loaded in memory
        // (newChunk=false) and another adjacent chunk (32, 0) loads from disk (newChunk=false),
        // while loading (32, 0) BEFORE (31, 0) is loaded in memory does NOT perform synchronous
        // main-thread disk reads for unloaded neighbors on pregenerated chunks (S-005).
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                platform.biomeOverrides.put(
                        new TreeModel.BlockPos((31 << 4) + lx, 63, lz), "minecraft:snowy_taiga");
            }
        }
        TreeSpecies groupWrapper = new TreeSpecies(
                "snowy_taiga_group",
                "Snowy Taiga Group",
                "minecraft",
                "snowy_taiga_group",
                Set.of("minecraft:snowy_taiga"),
                "SPRUCE_SAPLING",
                true,
                true,
                2,
                0,
                321,
                "linear",
                "surface_structures",
                List.of(),
                null,
                List.of(new TreeSpecies.WeightedSpecies(borderSpruce.id(), 100)),
                null,
                1);
        Map<String, TreeSpecies> groupMap = new LinkedHashMap<>();
        groupMap.put(groupWrapper.id(), groupWrapper);
        groupMap.put(borderSpruce.id(), borderSpruce);
        proceduralGenerator.setConfigSpecies(groupMap);
        TreeGenConfig groupConfig = new TreeGenConfig(
                Map.of(groupWrapper.id(), groupWrapper),
                "test-pack",
                "desc",
                TreeGenConfig.GenerationMode.PROCEDURAL,
                4.0,
                200);
        long groupSeed = -1L;
        for (long s = 1L; s < 10_000L; s++) {
            int[] t31 = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(31, 0, 2, s, groupWrapper.effectiveSalt());
            int[] t32 = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(32, 0, 2, s, groupWrapper.effectiveSalt());
            java.util.Random gr = new java.util.Random(
                    io.github.dailystruggle.rtp.common.predict.DistributionSampler
                            .variantSeed(groupWrapper.id(), (((long) 31) << 32) ^ (0 & 0xFFFFFFFFL) ^ s));
            int localX = gr.nextInt(16);
            if (t31[0] == 31 && t31[1] == 0 && (t32[0] != 32 || t32[1] != 0) && localX >= 4 && localX <= 5) {
                groupSeed = s;
                break;
            }
        }
        Assertions.assertTrue(groupSeed > 0L, "Must find deterministic seed for group species at localX=4..5");

        try (TreeGenEventController freshController = new TreeGenEventController(
                platform, () -> groupConfig, () -> registry, proceduralGenerator, growthManager, farmStore)) {
            freshController.register();
            platform.placedBlocks.clear();
            platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(32, 0)));
            platform.hook.fireChunkLoad(new ChunkLoadContext("world", 32, 0, false, groupSeed));
            boolean placedWhileNeighborUnloaded = platform.placedBlocks.entrySet().stream()
                    .anyMatch(e -> (e.getKey().x() >> 4) == 32
                            && (e.getKey().z() >> 4) == 0
                            && e.getValue().contains("spruce_leaves"));
            Assertions.assertFalse(
                    placedWhileNeighborUnloaded,
                    "Pregenerated chunk (newChunk=false) must not synchronously read unloaded neighbor chunk (31, 0) from disk on the main thread (S-005)");

            // Load trunk chunk (31, 0) AFTER target chunk (32, 0) is already loaded, without re-firing (32, 0):
            // bidirectional edge completion and group child reach must place the overhang into (32, 0).
            platform.loadedChunks = new java.util.HashSet<>(
                    Set.of(TreeModel.chunkKey(31, 0), TreeModel.chunkKey(32, 0)));
            platform.hook.fireChunkLoad(new ChunkLoadContext("world", 31, 0, false, groupSeed));
            boolean completedOnNeighborLoad = platform.placedBlocks.entrySet().stream()
                    .anyMatch(e -> (e.getKey().x() >> 4) == 32
                            && (e.getKey().z() >> 4) == 0
                            && e.getValue().contains("spruce_leaves"));
            Assertions.assertTrue(completedOnNeighborLoad,
                    "Loading preexisting trunk chunk (31, 0) after target chunk (32, 0) is already loaded must bidirectionally complete group species overhang into (32, 0)");
        }
        proceduralGenerator.setConfigSpecies(speciesMap);
        try (TreeGenEventController pregenController = new TreeGenEventController(
                platform, () -> config, () -> registry, proceduralGenerator, growthManager, farmStore)) {
            pregenController.register();
            // Simulate Chunky pregen where (32, 0) generates, unloads immediately, and (32, 1) generates next:
            // (32, 0) was already spawned in this session so it must not be re-read from disk, and any .lca read
            // for preexisting (31, 0) must use LcaRegionReader.readChunkViewFromFile rather than AnvilRegionByteCache.
            platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(32, 0)));
            platform.hook.fireChunkLoad(new ChunkLoadContext("world", 32, 0, true, chosenSeed));
            platform.loadedChunks = new java.util.HashSet<>(Set.of(TreeModel.chunkKey(32, 1)));
            platform.hook.fireChunkLoad(new ChunkLoadContext("world", 32, 1, true, chosenSeed));
            Assertions.assertEquals(
                    0L,
                    io.github.dailystruggle.anvil.AnvilRegionByteCache.stats().total(),
                    "Saved .lca chunk reads must use LcaRegionReader's cached dictionary and single-chunk reader rather than whole-file AnvilRegionByteCache");
        }
    }

    private static final class StubTreeGenPlatform implements Platform {
        private final Path root;
        private final DispatchingWorldEventHook hook = new DispatchingWorldEventHook();
        private final List<String> messages = new ArrayList<>();
        private final List<TaggedItemSpec> droppedItems = new ArrayList<>();
        private final List<TaggedItemSpec> givenItems = new ArrayList<>();
        private final Map<TreeModel.BlockPos, String> placedBlocks = new HashMap<>();
        private final Map<TreeModel.BlockPos, String> biomeOverrides = new HashMap<>();
        private final Map<String, String> chunkData = new HashMap<>();
        private final Map<UUID, CallerLocation> callerLocations = new HashMap<>();
        private Set<Long> loadedChunks = null;
        private List<Runnable> deferredTasks = null;
        private Object registeredRoot;

        StubTreeGenPlatform(Path root) {
            this.root = root;
        }

        @Override
        public boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
            return loadedChunks == null || loadedChunks.contains(TreeModel.chunkKey(chunkX, chunkZ));
        }

        @Override public Logger logger() { return Logger.getLogger("TestTreeGen"); }
        @Override public Path getRootFolder() { return root; }
        @Override public Path getWorldFolder(String worldName) { return root.resolve(worldName != null ? worldName : "world"); }
        @Override public List<String> getWorldNames() { return List.of("world"); }
        @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) { task.run(); }
        @Override public void scheduleDelayedTask(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
            if (deferredTasks != null) {
                deferredTasks.add(task);
            } else {
                task.run();
            }
        }
        @Override public String getBiomeKey(String worldName, int x, int y, int z) {
            return biomeOverrides.getOrDefault(new TreeModel.BlockPos(x, y, z), "minecraft:plains");
        }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return false; }
        public boolean giveItem(UUID fallbackPlayerId, String playerName, TaggedItemSpec item) {
            if ("Alice".equals(playerName) && item != null) {
                givenItems.add(item);
                return true;
            }
            return false;
        }

        @Override
        public void dropItem(String worldName, int x, int y, int z, TaggedItemSpec item) {
            if (item != null) {
                droppedItems.add(item);
            }
        }

        int droppedItemCount() {
            return droppedItems.size();
        }

        TaggedItemSpec lastDroppedItem() {
            return droppedItems.get(droppedItems.size() - 1);
        }

        @Override
        public int getHighestBlockY(String worldName, int x, int z) {
            if (!isChunkLoaded(worldName, x >> 4, z >> 4)) {
                return Integer.MIN_VALUE;
            }
            return 63;
        }

        @Override
        public boolean isReplaceable(String worldName, int x, int y, int z) {
            if (y <= 63 && !placedBlocks.containsKey(new TreeModel.BlockPos(x, y, z))) {
                return false;
            }
            String block = placedBlocks.get(new TreeModel.BlockPos(x, y, z));
            return block == null || block.endsWith("air") || block.endsWith("sapling");
        }

        @Override
        public String getBlockState(String worldName, int x, int y, int z) {
            return placedBlocks.getOrDefault(new TreeModel.BlockPos(x, y, z), y <= 63 ? "minecraft:grass_block" : "minecraft:air");
        }

        @Override
        public CallerLocation getCallerLocation(UUID callerId) {
            return callerLocations.get(callerId);
        }

        @Override
        public Set<String> getOnlinePlayerNames() {
            return Set.of("Alice", "Bob");
        }

        @Override
        public void registerCommands(Object rootCommand, String... aliases) {
            this.registeredRoot = rootCommand;
        }

        @Override
        public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
            placedBlocks.put(new TreeModel.BlockPos(x, y, z), blockData);
            return true;
        }

        @Override
        public void sendMessage(UUID playerId, String message, boolean error) {
            messages.add(message);
        }

        @Override
        public String getChunkData(String worldName, int chunkX, int chunkZ, String key) {
            return chunkData.get(worldName + ":" + chunkX + ":" + chunkZ + ":" + key);
        }

        @Override
        public void setChunkData(String worldName, int chunkX, int chunkZ, String key, String data) {
            String k = worldName + ":" + chunkX + ":" + chunkZ + ":" + key;
            if (data == null || data.isEmpty()) {
                chunkData.remove(k);
            } else {
                chunkData.put(k, data);
            }
        }

        @Override
        public WorldEventHook getWorldEventHook() {
            return hook;
        }

        @Override
        public long getTotalBlocksPlaced() {
            return placedBlocks.size();
        }
    }
}
