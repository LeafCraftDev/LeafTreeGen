package net.leaf.treegen.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runtime driver for the deterministic staged tree-growth mechanic (ADR-001).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><strong>Pre-flight footprint check:</strong> Projects the full final {@link TreeModel}
 *       into world coordinates using a pooled, grow-only buffer and verifies every voxel is
 *       replaceable and outside existing {@link TreeFarmStore} regions before growth begins.</li>
 *   <li><strong>Additive stage reveal:</strong> Schedules one delayed stage at a time using
 *       {@link GrowthPlanner#plan(TreeModel, long)}, placing only the newly revealed Y-band.</li>
 *   <li><strong>Mid-growth obstruction backstop:</strong> If a block in the next stage's Y-band
 *       becomes obstructed while the tree is growing, keeps already-placed blocks and terminates
 *       growth permanently without rollback.</li>
 *   <li><strong>Cut-tree cancellation:</strong> Breaking a wood block inside an active growing
 *       tree cancels its pending future stage and purges its chunk persistence record so cut
 *       trees never "haunt" or regrow; a restart-safe trunk-base sentinel also verifies the
 *       origin wood before revealing stages {@code > 0}.</li>
 * </ul>
 */
public final class GrowthManager {

    private final Platform platform;
    private final ProceduralGenerator generator;
    private final TreeFarmStore farmStore;
    private final Supplier<TreeGenConfig> configRef;

    private final Map<OriginKey, ActiveGrowth> activeTrees = new ConcurrentHashMap<>();

    /** Pooled, grow-only footprint buffer for pre-flight obstruction checks (ADR-001). */
    private int[] footprintCoords = new int[256 * 3];

    public record OriginKey(String worldName, int x, int y, int z) {}

    public record GrowthRecord(
            int originX,
            int originY,
            int originZ,
            String speciesId,
            long seed,
            int nextStageIndex,
            long totalTime
    ) {
        String encode() {
            return originX + "," + originY + "," + originZ + ","
                    + speciesId + "," + seed + "," + nextStageIndex + "," + totalTime;
        }

        static GrowthRecord decode(String line) {
            if (line == null || line.isBlank()) return null;
            String[] parts = line.trim().split(",", 7);
            if (parts.length < 7) return null;
            try {
                return new GrowthRecord(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        parts[3],
                        Long.parseLong(parts[4]),
                        Integer.parseInt(parts[5]),
                        Long.parseLong(parts[6]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    private static final class ActiveGrowth {
        final OriginKey origin;
        final TreeSpecies species;
        final long seed;
        final TreeModel model;
        final List<GrowthPlanner.Stage> stages;
        final Set<TreeModel.BlockPos> placedWorldPositions = ConcurrentHashMap.newKeySet();
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;
        volatile int nextStageIndex;
        volatile int prevRevealUpToY;
        volatile boolean cancelled;

        ActiveGrowth(OriginKey origin, TreeSpecies species, long seed, TreeModel model,
                     List<GrowthPlanner.Stage> stages, int startStageIndex, int prevRevealUpToY) {
            this.origin = origin;
            this.species = species;
            this.seed = seed;
            this.model = model;
            this.stages = stages;
            this.nextStageIndex = startStageIndex;
            this.prevRevealUpToY = prevRevealUpToY;
            int[] bounds = model.getBounds();
            this.minX = origin.x() + bounds[0];
            this.minY = origin.y() + bounds[1];
            this.minZ = origin.z() + bounds[2];
            this.maxX = origin.x() + bounds[3];
            this.maxY = origin.y() + bounds[4];
            this.maxZ = origin.z() + bounds[5];
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public GrowthManager(Platform platform, ProceduralGenerator generator,
                         TreeFarmStore farmStore, Supplier<TreeGenConfig> configRef) {
        this.platform = platform;
        this.generator = generator;
        this.farmStore = farmStore;
        this.configRef = configRef;
    }

    /**
     * Initiates staged growth of {@code species} at {@code (originX, originY, originZ)}.
     *
     * @return {@code true} if the pre-flight footprint check passed and growth has started;
     *         {@code false} if the footprint is obstructed or no model could be built
     */
    public boolean startGrowth(String worldName, int originX, int originY, int originZ,
                               TreeSpecies species, long seed) {
        if (worldName == null || species == null) return false;
        OriginKey key = new OriginKey(worldName, originX, originY, originZ);
        if (activeTrees.containsKey(key)) {
            return false;
        }
        TreeModel model = buildDeterministicModel(species, seed);
        if (model == null || model.getBlocks().isEmpty()) {
            return false;
        }

        if (!checkPreFlightFootprint(worldName, originX, originY, originZ, model)) {
            return false;
        }

        long totalTime = species.growth() != null && species.growth().enabled()
                ? Math.max(0L, species.growth().totalTime())
                : 0L;
        List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, totalTime);
        if (stages.isEmpty()) {
            return false;
        }

        int[] bounds = model.getBounds();
        ActiveGrowth active = new ActiveGrowth(key, species, seed, model, stages, 0, bounds[1] - 1);
        activeTrees.put(key, active);
        persistChunkRecords(worldName, originX >> 4, originZ >> 4);
        scheduleStage(active, stages.get(0).durationTicks());
        return true;
    }

    /**
     * Projects {@code model} into world coordinates using the pooled, grow-only footprint buffer
     * and checks that every target block is replaceable and does not overlap a tracked tree region.
     */
    synchronized boolean checkPreFlightFootprint(String worldName, int ox, int oy, int oz, TreeModel model) {
        Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
        int count = blocks.size();
        int needed = count * 3;
        if (footprintCoords.length < needed) {
            int newLen = Math.max(needed, footprintCoords.length * 2);
            footprintCoords = new int[newLen];
        }

        int idx = 0;
        for (TreeModel.BlockPos rel : blocks.keySet()) {
            footprintCoords[idx++] = ox + rel.x();
            footprintCoords[idx++] = oy + rel.y();
            footprintCoords[idx++] = oz + rel.z();
        }

        for (int i = 0; i < count; i++) {
            int wx = footprintCoords[i * 3];
            int wy = footprintCoords[i * 3 + 1];
            int wz = footprintCoords[i * 3 + 2];
            // The sapling block itself sits at the origin before stage 0 replaces it.
            if (wx == ox && wy == oy && wz == oz) {
                continue;
            }
            if (!isInspectableAndReplaceable(worldName, wx, wy, wz)) {
                return false;
            }
            if (farmStore != null && farmStore.regionAt(worldName, wx, wy, wz) != null) {
                return false;
            }
            for (ActiveGrowth active : activeTrees.values()) {
                if (!active.cancelled
                        && active.origin.worldName().equals(worldName)
                        && active.contains(wx, wy, wz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void scheduleStage(ActiveGrowth active, long delayTicks) {
        int cx = active.origin.x() >> 4;
        int cz = active.origin.z() >> 4;
        platform.scheduleDelayedTask(active.origin.worldName(), cx, cz, () -> executeNextStage(active), Math.max(0L, delayTicks));
    }

    private void executeNextStage(ActiveGrowth active) {
        if (active.cancelled) {
            return;
        }
        ActiveGrowth current = activeTrees.get(active.origin);
        if (current != active) {
            return;
        }

        int stageIdx = active.nextStageIndex;
        if (stageIdx < 0 || stageIdx >= active.stages.size()) {
            finishGrowth(active);
            return;
        }

        String worldName = active.origin.worldName();
        int ox = active.origin.x();
        int oy = active.origin.y();
        int oz = active.origin.z();

        // Restart-safe sentinel (ADR-001 section 6): before revealing stages > 0, verify
        // the origin trunk block is still wood (unless the platform does not report block state).
        if (stageIdx > 0) {
            String originState = platform.getBlockState(worldName, ox, oy, oz);
            if (originState != null && !GrowthPlanner.isWood(originState)) {
                cancelAndPurge(active, false);
                return;
            }
        }

        GrowthPlanner.Stage stage = active.stages.get(stageIdx);
        int lowerExclusiveY = active.prevRevealUpToY;
        int upperInclusiveY = stage.revealUpToY();

        // Collect the additive band (lowerExclusiveY, upperInclusiveY].
        List<TreeModel.BlockPos> bandPositions = new ArrayList<>();
        Map<TreeModel.BlockPos, String> bandStateByWorldPos = new HashMap<>();
        for (Map.Entry<TreeModel.BlockPos, String> entry : active.model.getBlocks().entrySet()) {
            TreeModel.BlockPos rel = entry.getKey();
            if (rel.y() <= lowerExclusiveY || rel.y() > upperInclusiveY) {
                continue;
            }
            TreeModel.BlockPos worldPos = new TreeModel.BlockPos(ox + rel.x(), oy + rel.y(), oz + rel.z());
            // Mid-growth obstruction backstop (ADR-001 section 5): if a target voxel not
            // already placed by this tree is now obstructed, keep placed blocks and stop.
            if (!(worldPos.x() == ox && worldPos.y() == oy && worldPos.z() == oz)
                    && !active.placedWorldPositions.contains(worldPos)
                    && !isInspectableAndReplaceable(worldName, worldPos.x(), worldPos.y(), worldPos.z())) {
                cancelAndPurge(active, !active.placedWorldPositions.isEmpty());
                return;
            }
            bandPositions.add(worldPos);
            bandStateByWorldPos.put(worldPos, entry.getValue());
        }

        placeOrderedBand(worldName, ox, oy, oz, bandPositions, bandStateByWorldPos);
        active.placedWorldPositions.addAll(bandPositions);
        active.prevRevealUpToY = upperInclusiveY;

        int nextIdx = stageIdx + 1;
        if (nextIdx >= active.stages.size()) {
            finishGrowth(active);
        } else {
            active.nextStageIndex = nextIdx;
            persistChunkRecords(worldName, ox >> 4, oz >> 4);
            long nextDelay = active.stages.get(nextIdx).durationTicks();
            scheduleStage(active, nextDelay);
        }
    }

    private void placeOrderedBand(String worldName, int ox, int oy, int oz,
                                  List<TreeModel.BlockPos> positions,
                                  Map<TreeModel.BlockPos, String> stateByPos) {
        if (positions.isEmpty()) return;
        List<TreeModel.BlockPos> ordered = SegmentedTreeModel.orderForPlacement(positions, stateByPos);
        Map<Long, Map<TreeModel.BlockPos, String>> byChunk = new LinkedHashMap<>();
        for (TreeModel.BlockPos pos : ordered) {
            int cx = pos.x() >> 4;
            int cz = pos.z() >> 4;
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .put(pos, stateByPos.get(pos));
        }
        for (Map.Entry<Long, Map<TreeModel.BlockPos, String>> e : byChunk.entrySet()) {
            long key = e.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Map<TreeModel.BlockPos, String> replaceable = new LinkedHashMap<>();
            for (Map.Entry<TreeModel.BlockPos, String> blockEntry : e.getValue().entrySet()) {
                TreeModel.BlockPos pos = blockEntry.getKey();
                boolean isOriginSapling = pos.x() == ox && pos.y() == oy && pos.z() == oz
                        && platform.isChunkLoaded(worldName, cx, cz);
                if (isOriginSapling || isInspectableAndReplaceable(worldName, pos.x(), pos.y(), pos.z())) {
                    replaceable.put(pos, blockEntry.getValue());
                }
            }
            if (!replaceable.isEmpty()) {
                platform.setBlocks(worldName, cx, cz, replaceable);
            }
        }
    }

    private boolean isInspectableAndReplaceable(String worldName, int x, int y, int z) {
        if (!platform.isChunkLoaded(worldName, x >> 4, z >> 4)) {
            return false;
        }
        return platform.isReplaceable(worldName, x, y, z);
    }

    private void finishGrowth(ActiveGrowth active) {
        active.cancelled = true;
        activeTrees.remove(active.origin, active);
        persistChunkRecords(active.origin.worldName(), active.origin.x() >> 4, active.origin.z() >> 4);
        if (farmStore != null) {
            int radius = SaplingDropResolver.estimateCanopyRadius(active.species);
            int height = SaplingDropResolver.estimateHeight(active.species);
            farmStore.recordTree(
                    active.origin.worldName(),
                    active.origin.x(),
                    active.origin.y(),
                    active.origin.z(),
                    radius,
                    height,
                    active.species.id());
        }
    }

    private void cancelAndPurge(ActiveGrowth active, boolean recordPartial) {
        active.cancelled = true;
        activeTrees.remove(active.origin, active);
        persistChunkRecords(active.origin.worldName(), active.origin.x() >> 4, active.origin.z() >> 4);
        if (recordPartial && farmStore != null) {
            int radius = SaplingDropResolver.estimateCanopyRadius(active.species);
            int height = SaplingDropResolver.estimateHeight(active.species);
            farmStore.recordTree(
                    active.origin.worldName(),
                    active.origin.x(),
                    active.origin.y(),
                    active.origin.z(),
                    radius,
                    height,
                    active.species.id());
        }
    }

    /**
     * Notifies the manager that a block was broken at {@code (x, y, z)}.
     * If the broken block is wood inside an active growing tree, cancels its
     * pending growth task and purges its persistence record (ADR-001 section 6).
     *
     * @return {@code true} if an active growing tree was cancelled
     */
    public boolean onBlockBroken(String worldName, int x, int y, int z, String brokenBlockState) {
        if (worldName == null || !GrowthPlanner.isWood(brokenBlockState)) {
            return false;
        }
        boolean cancelledAny = false;
        for (ActiveGrowth active : new ArrayList<>(activeTrees.values())) {
            if (!active.cancelled
                    && active.origin.worldName().equals(worldName)
                    && active.contains(x, y, z)) {
                cancelAndPurge(active, false);
                cancelledAny = true;
            }
        }
        return cancelledAny;
    }

    /**
     * Resumes any persisted staged-growth records stored in the given chunk.
     */
    public void resumeChunk(String worldName, int chunkX, int chunkZ) {
        String raw = platform.getGrowthData(worldName, chunkX, chunkZ);
        if (raw == null || raw.isBlank()) return;
        TreeGenConfig cfg = configRef != null ? configRef.get() : null;
        if (cfg == null) return;

        boolean dirty = false;
        for (String line : raw.split("\n")) {
            GrowthRecord rec = GrowthRecord.decode(line);
            if (rec == null) {
                dirty = true;
                continue;
            }
            OriginKey key = new OriginKey(worldName, rec.originX(), rec.originY(), rec.originZ());
            if (activeTrees.containsKey(key)) continue;

            TreeSpecies species = cfg.get(rec.speciesId());
            if (species == null) {
                dirty = true;
                continue;
            }
            TreeModel model = buildDeterministicModel(species, rec.seed());
            if (model == null || model.getBlocks().isEmpty()) {
                dirty = true;
                continue;
            }

            List<GrowthPlanner.Stage> stages = GrowthPlanner.plan(model, rec.totalTime());
            if (stages.isEmpty() || rec.nextStageIndex() < 0 || rec.nextStageIndex() >= stages.size()) {
                dirty = true;
                continue;
            }

            int prevY = rec.nextStageIndex() > 0
                    ? stages.get(rec.nextStageIndex() - 1).revealUpToY()
                    : model.getBounds()[1] - 1;
            ActiveGrowth active = new ActiveGrowth(
                    key, species, rec.seed(), model, stages, rec.nextStageIndex(), prevY);
            // Mark already-revealed positions so mid-growth obstruction check recognises them.
            for (TreeModel.BlockPos rel : model.getBlocks().keySet()) {
                if (rel.y() <= prevY) {
                    active.placedWorldPositions.add(
                            new TreeModel.BlockPos(rec.originX() + rel.x(), rec.originY() + rel.y(), rec.originZ() + rel.z()));
                }
            }
            activeTrees.put(key, active);
            long delay = stages.get(rec.nextStageIndex()).durationTicks();
            scheduleStage(active, delay);
        }
        if (dirty) {
            persistChunkRecords(worldName, chunkX, chunkZ);
        }
    }

    private TreeModel buildDeterministicModel(TreeSpecies species, long seed) {
        TreeModel built = generator.buildModel(species, new Random(seed));
        if (built != null) {
            built.rotate(new Random(seed).nextInt(4));
        }
        return built;
    }

    private void persistChunkRecords(String worldName, int chunkX, int chunkZ) {
        List<GrowthRecord> inChunk = new ArrayList<>();
        for (ActiveGrowth active : activeTrees.values()) {
            if (active.cancelled) continue;
            if (!active.origin.worldName().equals(worldName)) continue;
            if ((active.origin.x() >> 4) == chunkX && (active.origin.z() >> 4) == chunkZ) {
                long totalTime = active.species.growth() != null ? active.species.growth().totalTime() : 0L;
                inChunk.add(new GrowthRecord(
                        active.origin.x(),
                        active.origin.y(),
                        active.origin.z(),
                        active.species.id(),
                        active.seed,
                        active.nextStageIndex,
                        totalTime));
            }
        }
        if (inChunk.isEmpty()) {
            platform.setGrowthData(worldName, chunkX, chunkZ, null);
        } else {
            StringBuilder sb = new StringBuilder();
            for (GrowthRecord r : inChunk) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(r.encode());
            }
            platform.setGrowthData(worldName, chunkX, chunkZ, sb.toString());
        }
    }

    public int activeCount() {
        return activeTrees.size();
    }

    public synchronized int footprintBufferCapacity() {
        return footprintCoords.length / 3;
    }
}
