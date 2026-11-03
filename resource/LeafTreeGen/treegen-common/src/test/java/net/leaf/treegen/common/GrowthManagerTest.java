package net.leaf.treegen.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Unit tests for {@link GrowthManager} (ADR-001 staged tree growth runtime).
 */
public class GrowthManagerTest {

    private static TreeSpecies sampleStagedSpecies(long totalTime) {
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", "minecraft:oak_leaves", 8, 8,
                "OAK", 1.0, "CONSTANT", Map.of(), true,
                0.0, 0.0, "CONSTANT", Map.of(), "LINEAR", Map.of(),
                null, 0.5, 1.0,
                new TreeSpecies.CanopyParams("DENSITY", 1.0,
                        List.of(new TreeSpecies.CanopyParams.Layer(6, 2.0), new TreeSpecies.CanopyParams.Layer(7, 1.5)),
                        null, null, 0.0, true, 1.0, null, null, 1.0),
                true, 1, 1, List.of());

        return new TreeSpecies(
                "staged-oak", "Staged Oak", "minecraft", "staged-oak",
                Set.of("minecraft:plains"), "OAK_SAPLING", true, true,
                8, 2, -1, "linear", "surface_structures",
                List.of(), params, null, null, 1,
                new TreeSpecies.GrowthParams(true, totalTime), null);
    }

    @Test
    public void testStagedGrowthRevealsBandsAdditivelyAndRecordsFarmRegion() {
        SteppingPlatform platform = new SteppingPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        TreeSpecies species = sampleStagedSpecies(1000L);
        TreeGenConfig cfg = new TreeGenConfig(Map.of(species.id(), species), "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL);
        GrowthManager manager = new GrowthManager(platform, generator, farmStore, () -> cfg);

        boolean started = manager.startGrowth("world", 10, 64, 10, species, 42L);
        Assertions.assertTrue(started, "Unobstructed footprint should start growth");
        Assertions.assertEquals(1, manager.activeCount());
        Assertions.assertNotNull(platform.getGrowthData("world", 0, 0),
                "Active growth must persist its record in chunk growth data");

        int prevBlockCount = 0;
        int steps = 0;
        while (platform.runNextScheduledTask()) {
            steps++;
            int now = platform.placedBlocks.size();
            Assertions.assertTrue(now >= prevBlockCount,
                    "Each stage must only add blocks (never remove); step " + steps);
            prevBlockCount = now;
        }

        Assertions.assertTrue(steps >= 2, "Expected multiple growth stages to execute, got " + steps);
        Assertions.assertEquals(0, manager.activeCount(), "Growth should finish and clear active entry");
        Assertions.assertNull(platform.getGrowthData("world", 0, 0),
                "Completed growth must clear its chunk growth record");
        Assertions.assertEquals("staged-oak", farmStore.resolve("world", 10, 65, 10),
                "Completed staged tree must be recorded in TreeFarmStore");
    }

    @Test
    public void testPreFlightFootprintRefusesObstructedLocationAndReusesPooledBuffer() {
        SteppingPlatform platform = new SteppingPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        TreeSpecies species = sampleStagedSpecies(500L);
        TreeGenConfig cfg = new TreeGenConfig(Map.of(species.id(), species), "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL);
        GrowthManager manager = new GrowthManager(platform, generator, farmStore, () -> cfg);

        // Place a solid obstruction directly above the sapling origin.
        platform.obstructedPositions.add("10,66,10");

        int initialCap = manager.footprintBufferCapacity();
        boolean started = manager.startGrowth("world", 10, 64, 10, species, 42L);
        Assertions.assertFalse(started, "Obstructed footprint must refuse growth (vanilla no-room behavior)");
        Assertions.assertEquals(0, manager.activeCount());
        Assertions.assertTrue(platform.placedBlocks.isEmpty(), "Refused growth must not place any blocks");
        Assertions.assertTrue(manager.footprintBufferCapacity() >= initialCap,
                "Pooled footprint buffer must only grow, never shrink");
    }

    @Test
    public void testCutDetectionCancelsRemainingStagesAndPurgesRecord() {
        SteppingPlatform platform = new SteppingPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        TreeSpecies species = sampleStagedSpecies(1000L);
        TreeGenConfig cfg = new TreeGenConfig(Map.of(species.id(), species), "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL);
        GrowthManager manager = new GrowthManager(platform, generator, farmStore, () -> cfg);

        Assertions.assertTrue(manager.startGrowth("world", 10, 64, 10, species, 7L));
        // Reveal stage 0 (base trunk).
        Assertions.assertTrue(platform.runNextScheduledTask());
        int blocksAfterStage0 = platform.placedBlocks.size();
        Assertions.assertTrue(blocksAfterStage0 > 0);

        // Break the base trunk wood block -> must cancel future stages ("no haunting").
        boolean cancelled = manager.onBlockBroken("world", 10, 64, 10, "minecraft:oak_log");
        Assertions.assertTrue(cancelled, "Breaking wood inside an active growing tree must cancel it");
        Assertions.assertEquals(0, manager.activeCount());
        Assertions.assertNull(platform.getGrowthData("world", 0, 0),
                "Cut tree must purge its chunk growth record");

        // Drain any remaining scheduled callbacks; no new blocks may be placed.
        while (platform.runNextScheduledTask()) {
            // drain
        }
        Assertions.assertEquals(blocksAfterStage0, platform.placedBlocks.size(),
                "Cut tree must not place any further blocks after cancellation");
    }

    @Test
    public void testMidGrowthObstructionTerminatesPermanentlyKeepingPlacedBlocks() {
        SteppingPlatform platform = new SteppingPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        TreeSpecies species = sampleStagedSpecies(1000L);
        TreeGenConfig cfg = new TreeGenConfig(Map.of(species.id(), species), "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL);
        GrowthManager manager = new GrowthManager(platform, generator, farmStore, () -> cfg);

        Assertions.assertTrue(manager.startGrowth("world", 10, 64, 10, species, 99L));
        // Reveal stage 0.
        Assertions.assertTrue(platform.runNextScheduledTask());
        int blocksAfterStage0 = platform.placedBlocks.size();
        Assertions.assertTrue(blocksAfterStage0 > 0);

        // Obstruct upper trunk before stage 1 runs.
        for (int y = 65; y <= 75; y++) {
            platform.obstructedPositions.add("10," + y + ",10");
        }

        while (platform.runNextScheduledTask()) {
            // drain
        }
        Assertions.assertEquals(0, manager.activeCount(),
                "Mid-growth obstruction must terminate growth permanently");
        Assertions.assertEquals(blocksAfterStage0, platform.placedBlocks.size(),
                "Already-placed blocks from earlier stages must be kept without rollback");
    }

    @Test
    public void testResumeChunkRestoresPendingGrowthAfterRestart() {
        SteppingPlatform platform = new SteppingPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        TreeSpecies species = sampleStagedSpecies(1000L);
        TreeGenConfig cfg = new TreeGenConfig(Map.of(species.id(), species), "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL);
        GrowthManager manager1 = new GrowthManager(platform, generator, farmStore, () -> cfg);

        Assertions.assertTrue(manager1.startGrowth("world", 10, 64, 10, species, 123L));
        // Run stage 0 only, then simulate server restart with a fresh GrowthManager.
        Assertions.assertTrue(platform.runNextScheduledTask());
        int afterStage0 = platform.placedBlocks.size();
        platform.scheduledTasks.clear();

        GrowthManager manager2 = new GrowthManager(platform, generator, farmStore, () -> cfg);
        manager2.resumeChunk("world", 0, 0);
        Assertions.assertEquals(1, manager2.activeCount(), "resumeChunk should restore persisted growing tree");

        while (platform.runNextScheduledTask()) {
            // finish remaining stages
        }
        Assertions.assertTrue(platform.placedBlocks.size() > afterStage0,
                "Resumed tree should place remaining stages");
        Assertions.assertEquals(0, manager2.activeCount());
    }

    @Test
    public void testStartGrowthRefusesWhenCanopyExtendsIntoUnloadedNeighborChunk() {
        SteppingPlatform platform = new SteppingPlatform();
        platform.restrictLoadedChunksTo(0, 0);
        TreeRegistry registry = new TreeRegistry(platform);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeFarmStore farmStore = new TreeFarmStore(platform);
        TreeSpecies species = sampleStagedSpecies(500L);
        TreeGenConfig cfg = new TreeGenConfig(Map.of(species.id(), species), "test", "test", TreeGenConfig.GenerationMode.PROCEDURAL);
        GrowthManager manager = new GrowthManager(platform, generator, farmStore, () -> cfg);

        // Plant near chunk border (15, 64, 15) where canopy extends into unloaded chunk (1, 0).
        Assertions.assertTrue(platform.isChunkLoaded("world", 0, 0));
        Assertions.assertFalse(platform.isChunkLoaded("world", 1, 0));
        boolean started = manager.startGrowth("world", 15, 64, 15, species, 42L);
        Assertions.assertFalse(started,
                "Pre-flight footprint check must fail closed when canopy reaches unloaded neighbor chunk");
        Assertions.assertEquals(0, manager.activeCount());
        Assertions.assertTrue(platform.placedBlocks.isEmpty());
    }

    private static final class SteppingPlatform implements Platform {
        private final Path root = Paths.get("build/test-growth-" + System.nanoTime());
        final Map<String, String> placedBlocks = new HashMap<>();
        final Set<String> obstructedPositions = new java.util.HashSet<>();
        final Map<String, String> chunkData = new HashMap<>();
        final Map<String, String> growthData = new HashMap<>();
        final Deque<Runnable> scheduledTasks = new ArrayDeque<>();
        private Set<Long> loadedChunks = null;

        synchronized void restrictLoadedChunksTo(int chunkX, int chunkZ) {
            loadedChunks = Set.of(TreeModel.chunkKey(chunkX, chunkZ));
        }

        boolean runNextScheduledTask() {
            Runnable r = scheduledTasks.pollFirst();
            if (r == null) return false;
            r.run();
            return true;
        }

        @Override
        public synchronized boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
            return loadedChunks == null || loadedChunks.contains(TreeModel.chunkKey(chunkX, chunkZ));
        }

        @Override public Logger logger() { return Logger.getLogger("GrowthTest"); }
        @Override public Path getRootFolder() { return root; }
        @Override public Path getWorldFolder(String worldName) { return root.resolve(worldName); }
        @Override public List<String> getWorldNames() { return List.of("world"); }
        @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
            scheduledTasks.addLast(task);
        }
        @Override public void scheduleDelayedTask(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
            scheduledTasks.addLast(task);
        }
        @Override public String getBiomeKey(String worldName, int x, int y, int z) { return "minecraft:plains"; }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return true; }
        @Override public void giveSapling(UUID playerId, String speciesId, int amount) {}
        @Override public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
            placedBlocks.put(x + "," + y + "," + z, blockData);
            return true;
        }
        @Override public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
            blocks.forEach((pos, data) -> placedBlocks.put(pos.x() + "," + pos.y() + "," + pos.z(), data));
            return true;
        }
        @Override public boolean isReplaceable(String worldName, int x, int y, int z) {
            return !obstructedPositions.contains(x + "," + y + "," + z);
        }
        @Override public String getBlockState(String worldName, int x, int y, int z) {
            return placedBlocks.get(x + "," + y + "," + z);
        }
        @Override public String getChunkData(String worldName, int chunkX, int chunkZ) {
            return chunkData.get(worldName + ":" + chunkX + "," + chunkZ);
        }
        @Override public void setChunkData(String worldName, int chunkX, int chunkZ, String data) {
            String key = worldName + ":" + chunkX + "," + chunkZ;
            if (data == null || data.isEmpty()) chunkData.remove(key);
            else chunkData.put(key, data);
        }
        @Override public String getGrowthData(String worldName, int chunkX, int chunkZ) {
            return growthData.get(worldName + ":" + chunkX + "," + chunkZ);
        }
        @Override public void setGrowthData(String worldName, int chunkX, int chunkZ, String data) {
            String key = worldName + ":" + chunkX + "," + chunkZ;
            if (data == null || data.isEmpty()) growthData.remove(key);
            else growthData.put(key, data);
        }
        @Override public void sendMessage(UUID playerId, String message, boolean error) {}
    }
}
