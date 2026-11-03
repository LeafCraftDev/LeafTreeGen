package net.leaf.treegen.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Exercises the now platform-agnostic {@link TreeFarmStore} against an in-memory
 * {@link Platform} fake, proving the farm registry no longer depends on Paper.
 */
public class TreeFarmStoreTest {

    /** Minimal {@link Platform} that stores per-chunk blobs in a map. */
    private static final class FakePlatform implements Platform {
        final Map<String, String> chunks = new HashMap<>();

        private String key(String world, int cx, int cz) {
            return world + ":" + cx + ":" + cz;
        }

        @Override public String getChunkData(String worldName, int chunkX, int chunkZ) {
            return chunks.get(key(worldName, chunkX, chunkZ));
        }

        @Override public void setChunkData(String worldName, int chunkX, int chunkZ, String data) {
            if (data == null || data.isEmpty()) {
                chunks.remove(key(worldName, chunkX, chunkZ));
            } else {
                chunks.put(key(worldName, chunkX, chunkZ), data);
            }
        }

        @Override public Logger logger() { return Logger.getLogger("test"); }
        @Override public Path getRootFolder() { return Path.of("."); }
        @Override public Path getWorldFolder(String worldName) { return Path.of("."); }
        @Override public List<String> getWorldNames() { return List.of(); }
        @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) { }
        @Override public String getBiomeKey(String worldName, int x, int y, int z) { return ""; }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return false; }
        @Override public void giveSapling(UUID playerId, String speciesId, int amount) { }
        @Override public boolean setBlock(String worldName, int x, int y, int z, String blockData) { return false; }
        @Override public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) { return false; }
        @Override public void sendMessage(UUID playerId, String message, boolean error) { }
    }

    @Test
    public void recordsAndResolvesWithinRegion() {
        FakePlatform platform = new FakePlatform();
        TreeFarmStore store = new TreeFarmStore(platform);

        store.recordTree("world", 100, 64, 100, 4, 12, "cherry");

        Assertions.assertEquals("cherry", store.resolve("world", 100, 64, 100));
        Assertions.assertEquals("cherry", store.resolve("world", 103, 70, 97));
        Assertions.assertNull(store.resolve("world", 200, 64, 200));
    }

    @Test
    public void regionAtExposesBoundingBox() {
        FakePlatform platform = new FakePlatform();
        TreeFarmStore store = new TreeFarmStore(platform);

        store.recordTree("world", 100, 64, 100, 4, 12, "cherry");

        TreeFarmStore.Region region = store.regionAt("world", 103, 70, 97);
        Assertions.assertNotNull(region);
        Assertions.assertEquals("cherry", region.speciesId());
        Assertions.assertEquals(96, region.minX());
        Assertions.assertEquals(104, region.maxX());
        Assertions.assertEquals(62, region.minY());
        Assertions.assertEquals(76, region.maxY());

        Assertions.assertNull(store.regionAt("world", 200, 64, 200));
    }

    @Test
    public void regionSpansMultipleChunks() {
        FakePlatform platform = new FakePlatform();
        TreeFarmStore store = new TreeFarmStore(platform);

        // Origin near a chunk boundary so the region overlaps several chunks.
        store.recordTree("world", 15, 64, 15, 6, 12, "oak");

        // A block in a neighbouring chunk still resolves.
        Assertions.assertEquals("oak", store.resolve("world", 20, 64, 16));
        Assertions.assertTrue(platform.chunks.size() > 1, "expected region to span multiple chunks");
    }

    @Test
    public void differentWorldsAreIsolated() {
        FakePlatform platform = new FakePlatform();
        TreeFarmStore store = new TreeFarmStore(platform);

        store.recordTree("overworld", 0, 64, 0, 4, 12, "birch");

        Assertions.assertEquals("birch", store.resolve("overworld", 0, 64, 0));
        Assertions.assertNull(store.resolve("nether", 0, 64, 0));
    }
}
