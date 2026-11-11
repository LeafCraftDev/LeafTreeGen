package net.leaf.treegen.common;

import io.github.dailystruggle.rtp.common.predict.BudgetPacer;
import io.github.dailystruggle.rtp.common.predict.EwmaEstimator;
import io.github.dailystruggle.server.api.BlockPos3i;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Platform-neutral budgeted block batcher backed by {@link BudgetPacer} and {@link EwmaEstimator}.
 *
 * <p>Slices per-chunk block maps across ticks according to {@link TreeGenConfig#timeAllocationMs()}
 * and {@link TreeGenConfig#maxBlocksPerTick()}, filtering out plain air blocks while recording
 * profiling telemetry for {@code /leaftree stats} across Paper/Folia, Fabric, and NeoForge.
 */
public final class TreeBlockBatcher {

    private final Platform platform;
    private final Supplier<TreeGenConfig> configRef;
    private final EwmaEstimator blockCostEstimator = EwmaEstimator.ofWindow(32, 2_000L);
    private final BudgetPacer batchPacer = BudgetPacer.ofEstimator(blockCostEstimator, 1.05);

    private final AtomicLong totalBlocksPlaced = new AtomicLong();
    private final AtomicLong totalTimeSpentNanos = new AtomicLong();
    private final AtomicLong totalBatches = new AtomicLong();
    private final AtomicLong budgetExceededCount = new AtomicLong();
    private final AtomicLong totalChunksLoaded = new AtomicLong();
    private final AtomicLong totalNewChunksGenerated = new AtomicLong();
    private final AtomicLong firstChunkLoadNanos = new AtomicLong();
    private final AtomicLong lastChunkLoadNanos = new AtomicLong();
    private final AtomicLong lastBatchNanos = new AtomicLong();

    public TreeBlockBatcher(Platform platform, Supplier<TreeGenConfig> configRef) {
        this.platform = platform;
        this.configRef = configRef;
    }

    /**
     * Records a chunk-load event for server-side generation and load-window telemetry.
     */
    public void recordChunkLoad(boolean newChunk) {
        long now = System.nanoTime();
        firstChunkLoadNanos.compareAndSet(0L, now);
        lastChunkLoadNanos.set(now);
        totalChunksLoaded.incrementAndGet();
        if (newChunk) {
            totalNewChunksGenerated.incrementAndGet();
        }
    }

    /**
     * Schedules throttled placement of {@code blocks} in {@code (worldName, chunkX, chunkZ)}.
     */
    public boolean placeBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return true;
        }
        List<Map.Entry<TreeModel.BlockPos, String>> entries = new ArrayList<>(blocks.size());
        for (Map.Entry<TreeModel.BlockPos, String> entry : blocks.entrySet()) {
            String data = entry.getValue();
            if (data == null || isPlainAir(data)) {
                continue;
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            return true;
        }
        TreeGenConfig cfg = configRef != null ? configRef.get() : null;
        double allocationMs = cfg != null ? cfg.timeAllocationMs() : 2.0;
        int maxBlocksPerTick = cfg != null ? Math.max(1, cfg.maxBlocksPerTick()) : 64;
        platform.scheduleDelayedTask(worldName, chunkX, chunkZ,
                () -> placeNextBatch(worldName, chunkX, chunkZ, entries, 0, allocationMs, maxBlocksPerTick),
                0L);
        return true;
    }

    private void placeNextBatch(String worldName, int chunkX, int chunkZ,
                                List<Map.Entry<TreeModel.BlockPos, String>> entries,
                                int start, double allocationMs, int maxBlocksPerTick) {
        long startNanos = System.nanoTime();
        long budgetNanos = (long) (allocationMs * 1_000_000.0);
        int i = start;
        int placedThisTick = 0;
        Map<BlockPos3i, String> batch = new LinkedHashMap<>();

        while (i < entries.size()) {
            Map.Entry<TreeModel.BlockPos, String> entry = entries.get(i);
            TreeModel.BlockPos pos = entry.getKey();
            batch.put(new BlockPos3i(pos.x(), pos.y(), pos.z()), entry.getValue());
            i++;
            placedThisTick++;

            if (placedThisTick >= maxBlocksPerTick) {
                break;
            }
            if (placedThisTick % 10 == 0) {
                long elapsed = System.nanoTime() - startNanos;
                if (elapsed >= budgetNanos || !batchPacer.canExecute(elapsed, budgetNanos)) {
                    budgetExceededCount.incrementAndGet();
                    break;
                }
            }
        }

        boolean applied = true;
        if (!batch.isEmpty()) {
            applied = platform.setChunkBlocks(worldName, chunkX, chunkZ, batch);
            if (!applied) {
                platform.logger().warning(
                        "Failed to apply tree block batch (" + batch.size() + " blocks) in chunk ("
                                + chunkX + "," + chunkZ + ") of world " + worldName);
            }
        }

        long endNanos = System.nanoTime();
        long tickTime = Math.max(1L, endNanos - startNanos);
        if (applied && placedThisTick > 0) {
            blockCostEstimator.recordNanos(Math.max(1L, tickTime / placedThisTick));
            totalBlocksPlaced.addAndGet(placedThisTick);
        }
        totalTimeSpentNanos.addAndGet(tickTime);
        totalBatches.incrementAndGet();
        lastBatchNanos.set(endNanos);

        if (applied && i < entries.size()) {
            final int nextStart = i;
            platform.scheduleDelayedTask(worldName, chunkX, chunkZ,
                    () -> placeNextBatch(worldName, chunkX, chunkZ, entries, nextStart, allocationMs, maxBlocksPerTick),
                    1L);
        }
    }

    /** Returns {@code true} for plain or cave air (preserving {@code void_air} used for clearing). */
    public static boolean isPlainAir(String blockData) {
        if (blockData == null) return true;
        String base = blockData.split("\\[")[0].toLowerCase(Locale.ROOT);
        return base.equals("minecraft:air")
                || base.equals("minecraft:cave_air")
                || base.equals("air")
                || base.equals("cave_air");
    }

    public long totalBlocksPlaced() {
        return totalBlocksPlaced.get();
    }

    public long totalTimeSpentNanos() {
        return totalTimeSpentNanos.get();
    }

    public long totalBatches() {
        return totalBatches.get();
    }

    public long budgetExceededCount() {
        return budgetExceededCount.get();
    }

    public long totalChunksLoaded() {
        return totalChunksLoaded.get();
    }

    public long totalNewChunksGenerated() {
        return totalNewChunksGenerated.get();
    }

    public long chunkLoadWindowNanos() {
        long first = firstChunkLoadNanos.get();
        long last = lastChunkLoadNanos.get();
        return (first > 0L && last >= first) ? (last - first) : 0L;
    }

    public long totalCompletionWindowNanos() {
        long first = firstChunkLoadNanos.get();
        long last = Math.max(lastChunkLoadNanos.get(), lastBatchNanos.get());
        return (first > 0L && last >= first) ? (last - first) : 0L;
    }

    public void resetStats() {
        totalBlocksPlaced.set(0L);
        totalTimeSpentNanos.set(0L);
        totalBatches.set(0L);
        budgetExceededCount.set(0L);
        totalChunksLoaded.set(0L);
        totalNewChunksGenerated.set(0L);
        firstChunkLoadNanos.set(0L);
        lastChunkLoadNanos.set(0L);
        lastBatchNanos.set(0L);
    }
}
