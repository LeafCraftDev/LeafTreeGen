package net.leaf.treegen.common;

import io.github.dailystruggle.server.api.NoopWorldEventHook;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.WorldEventHook;
import net.leaf.treegen.common.command.CallerLocation;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform abstraction layer for LeafTreeGen, extending {@link ServerAccessor} so event hooks and
 * command registration are shared across Paper/Folia, Fabric, and NeoForge.
 */
public interface Platform extends ServerAccessor {
    /** Canonical custom-tag key for sapling species id in {@link io.github.dailystruggle.server.api.PlayerInteractBlockContext}. */
    String TAG_TREE_SPECIES = "tree_species";
    /** Canonical custom-tag key for bound sapling structure variant in {@link io.github.dailystruggle.server.api.PlayerInteractBlockContext}. */
    String TAG_TREE_VARIANT = "tree_variant";
    /** Chunk storage key for tree farm bounding-box records. */
    String CHUNK_KEY_TREE_REGIONS = "tree_regions";
    /** Chunk storage key for active staged-growth records (ADR-001). */
    String CHUNK_KEY_TREE_GROWTH = "tree_growth";

    Logger logger();
    Path getRootFolder();

    @Override
    default String getServerVersion() {
        return "unknown";
    }

    @Override
    default String getPlatform() {
        return "unknown";
    }

    @Override
    default Integer getServerIntVersion() {
        return 21;
    }

    @Override
    default void log(Level level, String message) {
        logger().log(level, message);
    }

    @Override
    default void log(Level level, String message, Throwable throwable) {
        logger().log(level, message, throwable);
    }

    @Override
    default File getPluginDirectory() {
        return getRootFolder().toFile();
    }

    @Override
    default WorldEventHook getWorldEventHook() {
        return NoopWorldEventHook.INSTANCE;
    }

    /** Gets the root folder for a world (e.g. for datapack generation). */
    Path getWorldFolder(String worldName);

    /** List of currently loaded world names. */
    List<String> getWorldNames();

    /** Schedules a repeating task. */
    void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks);

    /** Resolves a biome key at the given location in the specified world. */
    String getBiomeKey(String worldName, int x, int y, int z);

    /** Places a structure at the given location. */
    boolean placeStructure(String worldName, int x, int y, int z, String structureKey);

    /** Hands a tagged sapling to a player. */
    default void giveSapling(UUID playerId, String speciesId, int amount) {
    }

    /** Places a block at the given location. Returns true if successful. */
    boolean setBlock(String worldName, int x, int y, int z, String blockData);

    /** Places a batch of blocks in a specific chunk. Returns true if all were successful. */
    default boolean setBlocks(String worldName, int chunkX, int chunkZ, java.util.Map<TreeModel.BlockPos, String> blocks) {
        if (blocks == null || blocks.isEmpty()) return true;
        java.util.Map<io.github.dailystruggle.server.api.BlockPos3i, String> converted = new java.util.LinkedHashMap<>(blocks.size());
        for (java.util.Map.Entry<TreeModel.BlockPos, String> entry : blocks.entrySet()) {
            if (TreeBlockBatcher.isPlainAir(entry.getValue())) continue;
            TreeModel.BlockPos pos = entry.getKey();
            converted.put(new io.github.dailystruggle.server.api.BlockPos3i(pos.x(), pos.y(), pos.z()), entry.getValue());
        }
        return setChunkBlocks(worldName, chunkX, chunkZ, converted);
    }

    /** Message a player or console. */
    void sendMessage(UUID playerId, String message, boolean error);

    /**
     * Reads a named persistent per-chunk string value for the given chunk, or {@code null} when
     * none is stored.
     */
    default String getChunkData(String worldName, int chunkX, int chunkZ, String key) {
        return null;
    }

    /**
     * Writes (or clears when {@code data} is {@code null}/empty) a named persistent per-chunk
     * string value for the given chunk.
     */
    default void setChunkData(String worldName, int chunkX, int chunkZ, String key, String data) {
    }

    /**
     * Atomically reads, transforms, and writes a named persistent per-chunk string value for the
     * given chunk.
     */
    default void updateChunkData(String worldName, int chunkX, int chunkZ, String key, java.util.function.UnaryOperator<String> updater) {
        String updated = updater.apply(getChunkData(worldName, chunkX, chunkZ, key));
        setChunkData(worldName, chunkX, chunkZ, key, updated);
    }

    /**
     * Reads the plugin's persistent per-chunk data blob for the given chunk, or
     * {@code null} when none is stored. Used by {@link TreeFarmStore} to track
     * where custom trees have grown. Platforms that cannot persist chunk data may
     * leave the default no-op implementation (the feature then relies solely on
     * biome-based resolution).
     */
    default String getChunkData(String worldName, int chunkX, int chunkZ) {
        return getChunkData(worldName, chunkX, chunkZ, CHUNK_KEY_TREE_REGIONS);
    }

    /**
     * Writes (or clears, when {@code data} is {@code null}/empty) the plugin's
     * persistent per-chunk data blob for the given chunk. Counterpart to
     * {@link #getChunkData(String, int, int)}.
     */
    default void setChunkData(String worldName, int chunkX, int chunkZ, String data) {
        setChunkData(worldName, chunkX, chunkZ, CHUNK_KEY_TREE_REGIONS, data);
    }

    /**
     * Atomically reads, transforms, and writes the plugin's persistent per-chunk
     * data blob for the given chunk. Platforms with regional threading (Folia) may
     * dispatch this to the owning region thread and skip unloaded chunks (S-005).
     */
    default void updateChunkData(String worldName, int chunkX, int chunkZ, java.util.function.UnaryOperator<String> updater) {
        String updated = updater.apply(getChunkData(worldName, chunkX, chunkZ));
        setChunkData(worldName, chunkX, chunkZ, updated);
    }

    /**
     * Reads the persistent staged-growth record blob for the given chunk (ADR-001),
     * or {@code null} when none is stored.
     */
    default String getGrowthData(String worldName, int chunkX, int chunkZ) {
        return getChunkData(worldName, chunkX, chunkZ, CHUNK_KEY_TREE_GROWTH);
    }

    /**
     * Writes (or clears, when {@code data} is {@code null}/empty) the persistent
     * staged-growth record blob for the given chunk (ADR-001).
     */
    default void setGrowthData(String worldName, int chunkX, int chunkZ, String data) {
        setChunkData(worldName, chunkX, chunkZ, CHUNK_KEY_TREE_GROWTH, data);
    }

    /**
     * Returns {@code true} if the world block at {@code (x, y, z)} is air or a
     * replaceable plant/fluid block into which a tree stage may grow (ADR-001).
     */
    default boolean isReplaceable(String worldName, int x, int y, int z) {
        return true;
    }

    /**
     * Returns the current block-state / material identifier at {@code (x, y, z)},
     * or {@code null} if unknown on this platform.
     */
    default String getBlockState(String worldName, int x, int y, int z) {
        return null;
    }

    /**
     * Schedules a one-shot delayed task anchored to the given chunk coordinates.
     */
    default void scheduleDelayedTask(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            task.run();
        } else {
            scheduleRepeatingTask(new Runnable() {
                private boolean ran = false;
                @Override
                public void run() {
                    if (!ran) {
                        ran = true;
                        task.run();
                    }
                }
            }, delayTicks, Long.MAX_VALUE);
        }
    }

    /**
     * Minecraft DataVersion stamped into generated structure {@code .nbt} templates.
     * Platforms should override this with the running server's data version so the
     * baked structures match the world; the default targets 1.20.1 and relies on the
     * client/server DataFixerUpper to upgrade if older than the running version.
     */
    default int getDataVersion() {
        return 3578;
    }

    /**
     * Resolves the world folder name under which generated structure templates live for the given
     * runtime world/dimension name.
     */
    default String resolveTemplateWorld(String worldName) {
        if (worldName != null && !worldName.isBlank()) {
            Path folder = getWorldFolder(worldName);
            if (folder != null && folder.resolve("datapacks").toFile().isDirectory()) {
                return worldName;
            }
        }
        List<String> names = getWorldNames();
        if (names != null && !names.isEmpty()) {
            return names.get(0);
        }
        return worldName != null && !worldName.isBlank() ? worldName : "world";
    }

    /**
     * Delivers a species-tagged sapling (optionally bound to {@code variant}) to {@code playerName}
     * or {@code fallbackPlayerId} when {@code playerName} is omitted, delegating to
     * {@link #giveItem(UUID, String, io.github.dailystruggle.server.api.TaggedItemSpec)}.
     */
    default boolean giveSapling(UUID fallbackPlayerId, String playerName, TreeSpecies species, int amount, String variant) {
        if (species == null) return false;
        return giveItem(fallbackPlayerId, playerName, SaplingItem.create(species, amount, variant));
    }

    /**
     * Drops a species-tagged sapling item naturally at the given block coordinates, delegating to
     * {@link #dropItem(String, int, int, int, io.github.dailystruggle.server.api.TaggedItemSpec)}.
     */
    default void dropSapling(String worldName, int x, int y, int z, TreeSpecies species, int amount) {
        if (species == null) return;
        dropItem(worldName, x, y, z, SaplingItem.create(species, amount));
    }

    /**
     * Returns the highest non-air surface block Y coordinate at {@code (x, z)} in {@code worldName},
     * or {@link Integer#MIN_VALUE} when unknown.
     */
    default int getHighestBlockY(String worldName, int x, int z) {
        return Integer.MIN_VALUE;
    }

    /**
     * Returns the minimum build height for {@code worldName}.
     */
    default int getMinHeight(String worldName) {
        return -64;
    }

    /**
     * Finds the highest solid non-leaf, non-log ground Y coordinate at {@code (x, z)} in
     * {@code worldName}, or {@link Integer#MIN_VALUE} when no valid ground is found.
     */
    default int findTreeGroundY(String worldName, int x, int z) {
        int y = getHighestBlockY(worldName, x, z);
        if (y == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        int minY = getMinHeight(worldName);
        while (y > minY) {
            if (!isReplaceable(worldName, x, y, z)) {
                String blockState = getBlockState(worldName, x, y, z);
                String upper = blockState != null ? blockState.toUpperCase(java.util.Locale.ROOT) : "";
                if (!upper.contains("LEAVES") && !upper.contains("LOG")) {
                    return y;
                }
            }
            y--;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Resolves the caller's live target or block location for command coordinate defaults.
     */
    default CallerLocation getCallerLocation(UUID callerId) {
        io.github.dailystruggle.server.api.CallerLocation loc = resolveCallerLocation(callerId);
        return loc != null ? new CallerLocation(loc.worldName(), loc.x(), loc.y(), loc.z()) : null;
    }

    /**
     * Adapts any {@link ServerAccessor} into a {@link Platform} instance backed by the supplied
     * {@link TreeBlockBatcher}.
     */
    static Platform from(ServerAccessor accessor, java.util.function.Supplier<TreeBlockBatcher> batcherRef) {
        if (accessor instanceof Platform p) {
            return p;
        }
        return new ServerAccessorPlatformAdapter(accessor, batcherRef);
    }

    default long getTotalBlocksPlaced() {
        return 0L;
    }

    default long getTotalTimeSpentNanos() {
        return 0L;
    }

    default long getTotalBatches() {
        return 0L;
    }

    default long getBudgetExceededCount() {
        return 0L;
    }

    default void recordChunkLoad(boolean newChunk) {
    }

    default long getTotalChunksLoaded() {
        return 0L;
    }

    default long getTotalNewChunksGenerated() {
        return 0L;
    }

    default long getChunkLoadWindowNanos() {
        return 0L;
    }

    default long getTotalCompletionWindowNanos() {
        return 0L;
    }

    default void resetStats() {
    }
}
