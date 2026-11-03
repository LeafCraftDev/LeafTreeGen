package net.leaf.treegen.common;

import io.github.dailystruggle.server.api.BlockPos3i;
import io.github.dailystruggle.server.api.CallerLocation;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.TaggedItemSpec;
import io.github.dailystruggle.server.api.WorldEventHook;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges any shared {@link ServerAccessor} implementation onto {@link Platform} and routes bulk
 * chunk block placement through {@link TreeBlockBatcher}.
 */
final class ServerAccessorPlatformAdapter implements Platform {

    private final ServerAccessor delegate;
    private final Supplier<TreeBlockBatcher> batcherRef;

    ServerAccessorPlatformAdapter(ServerAccessor delegate, Supplier<TreeBlockBatcher> batcherRef) {
        this.delegate = delegate;
        this.batcherRef = batcherRef;
    }

    @Override
    public Logger logger() {
        return delegate.logger();
    }

    @Override
    public Path getRootFolder() {
        return delegate.getRootFolder();
    }

    @Override
    public String getServerVersion() {
        return delegate.getServerVersion();
    }

    @Override
    public String getPlatform() {
        return delegate.getPlatform();
    }

    @Override
    public Integer getServerIntVersion() {
        return delegate.getServerIntVersion();
    }

    @Override
    public void log(Level level, String message) {
        delegate.log(level, message);
    }

    @Override
    public void log(Level level, String message, Throwable throwable) {
        delegate.log(level, message, throwable);
    }

    @Override
    public File getPluginDirectory() {
        return delegate.getPluginDirectory();
    }

    @Override
    public WorldEventHook getWorldEventHook() {
        return delegate.getWorldEventHook();
    }

    @Override
    public Set<String> getOnlinePlayerNames() {
        return delegate.getOnlinePlayerNames();
    }

    @Override
    public Path getWorldFolder(String worldName) {
        return delegate.getWorldFolder(worldName);
    }

    @Override
    public List<String> getWorldNames() {
        return delegate.getWorldNames();
    }

    @Override
    public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        delegate.scheduleRepeatingTask(task, delayTicks, periodTicks);
    }

    @Override
    public void scheduleDelayedTask(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        delegate.scheduleDelayedTask(worldName, chunkX, chunkZ, task, delayTicks);
    }

    @Override
    public String getBiomeKey(String worldName, int x, int y, int z) {
        return delegate.getBiomeKey(worldName, x, y, z);
    }

    @Override
    public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
        return delegate.placeStructure(worldName, x, y, z, structureKey);
    }

    @Override
    public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
        return delegate.setBlock(worldName, x, y, z, blockData);
    }

    @Override
    public boolean setChunkBlocks(String worldName, int chunkX, int chunkZ, Map<BlockPos3i, String> blocks) {
        return delegate.setChunkBlocks(worldName, chunkX, chunkZ, blocks);
    }

    @Override
    public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        if (batcher != null) {
            return batcher.placeBlocks(worldName, chunkX, chunkZ, blocks);
        }
        return Platform.super.setBlocks(worldName, chunkX, chunkZ, blocks);
    }

    @Override
    public void sendMessage(UUID playerId, String message, boolean error) {
        delegate.sendMessage(playerId, message, error);
    }

    @Override
    public String getChunkData(String worldName, int chunkX, int chunkZ, String key) {
        return delegate.getChunkData(worldName, chunkX, chunkZ, key);
    }

    @Override
    public void setChunkData(String worldName, int chunkX, int chunkZ, String key, String data) {
        delegate.setChunkData(worldName, chunkX, chunkZ, key, data);
    }

    @Override
    public void updateChunkData(String worldName, int chunkX, int chunkZ, String key, UnaryOperator<String> updater) {
        delegate.updateChunkData(worldName, chunkX, chunkZ, key, updater);
    }

    @Override
    public boolean isReplaceable(String worldName, int x, int y, int z) {
        return delegate.isReplaceable(worldName, x, y, z);
    }

    @Override
    public String getBlockState(String worldName, int x, int y, int z) {
        return delegate.getBlockState(worldName, x, y, intZ(z));
    }

    private static int intZ(int z) {
        return z;
    }

    @Override
    public int getHighestBlockY(String worldName, int x, int z) {
        return delegate.getHighestBlockY(worldName, x, z);
    }

    @Override
    public int getMinHeight(String worldName) {
        return delegate.getMinHeight(worldName);
    }

    @Override
    public boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
        return delegate.isChunkLoaded(worldName, chunkX, chunkZ);
    }

    @Override
    public int getDataVersion() {
        return delegate.getDataVersion();
    }

    @Override
    public boolean giveItem(UUID fallbackPlayerId, String playerName, TaggedItemSpec item) {
        return delegate.giveItem(fallbackPlayerId, playerName, item);
    }

    @Override
    public void dropItem(String worldName, int x, int y, int z, TaggedItemSpec item) {
        delegate.dropItem(worldName, x, y, z, item);
    }

    @Override
    public CallerLocation resolveCallerLocation(UUID callerId) {
        return delegate.resolveCallerLocation(callerId);
    }

    @Override
    public void registerCommands(Object rootCommand, String... aliases) {
        delegate.registerCommands(rootCommand, aliases);
    }

    @Override
    public long getTotalBlocksPlaced() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.totalBlocksPlaced() : 0L;
    }

    @Override
    public long getTotalTimeSpentNanos() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.totalTimeSpentNanos() : 0L;
    }

    @Override
    public long getTotalBatches() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.totalBatches() : 0L;
    }

    @Override
    public long getBudgetExceededCount() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.budgetExceededCount() : 0L;
    }

    @Override
    public void recordChunkLoad(boolean newChunk) {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        if (batcher != null) {
            batcher.recordChunkLoad(newChunk);
        }
    }

    @Override
    public long getTotalChunksLoaded() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.totalChunksLoaded() : 0L;
    }

    @Override
    public long getTotalNewChunksGenerated() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.totalNewChunksGenerated() : 0L;
    }

    @Override
    public long getChunkLoadWindowNanos() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.chunkLoadWindowNanos() : 0L;
    }

    @Override
    public long getTotalCompletionWindowNanos() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        return batcher != null ? batcher.totalCompletionWindowNanos() : 0L;
    }

    @Override
    public void resetStats() {
        TreeBlockBatcher batcher = batcherRef != null ? batcherRef.get() : null;
        if (batcher != null) {
            batcher.resetStats();
        }
    }
}
