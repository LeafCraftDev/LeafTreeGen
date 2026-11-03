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

    private static final class StubTreeGenPlatform implements Platform {
        private final Path root;
        private final DispatchingWorldEventHook hook = new DispatchingWorldEventHook();
        private final List<String> messages = new ArrayList<>();
        private final List<TaggedItemSpec> droppedItems = new ArrayList<>();
        private final List<TaggedItemSpec> givenItems = new ArrayList<>();
        private final Map<TreeModel.BlockPos, String> placedBlocks = new HashMap<>();
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
        @Override public String getBiomeKey(String worldName, int x, int y, int z) { return "minecraft:plains"; }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return false; }

        @Override
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
