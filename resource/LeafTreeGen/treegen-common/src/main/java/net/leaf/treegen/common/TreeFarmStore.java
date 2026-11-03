package net.leaf.treegen.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-agnostic, persistent record of where custom trees have actually grown,
 * so that breaking or decaying their leaves can drop the correct species' sapling
 * even when the biome alone is ambiguous (the "precise" half of the hybrid
 * resolution; the biome half lives in {@link SaplingDropResolver}).
 *
 * <p>Each grown tree is stored as an axis-aligned bounding box tagged with a
 * species id. Regions are written into the per-chunk persistent data of every
 * chunk they overlap (via {@link Platform#getChunkData}/{@link Platform#setChunkData}),
 * so they survive restarts and a leaf lookup only needs to inspect the chunk the
 * leaf sits in.
 */
public final class TreeFarmStore {

    private record WorldChunkKey(String worldName, int chunkX, int chunkZ) {}

    private final Platform platform;
    private final Map<WorldChunkKey, List<Region>> pendingChunkRegions = new ConcurrentHashMap<>();

    public TreeFarmStore(Platform platform) {
        this.platform = platform;
    }

    /** A tracked tree bounding box tagged with the species that grew it. */
    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String speciesId) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        String encode() {
            return minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ + "," + speciesId;
        }

        static Region decode(String s) {
            String[] parts = s.split(",", 7);
            if (parts.length < 7) return null;
            try {
                return new Region(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                        parts[6]);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    /**
     * Record a grown tree centred on (originX, originY, originZ). The region spans
     * {@code radius} blocks horizontally and {@code height} blocks upward (plus a
     * little below the base to cover low leaves).
     */
    public void recordTree(String worldName, int originX, int originY, int originZ,
                           int radius, int height, String speciesId) {
        if (worldName == null || speciesId == null) return;
        int minX = originX - radius;
        int maxX = originX + radius;
        int minZ = originZ - radius;
        int maxZ = originZ + radius;
        int minY = originY - 2;
        int maxY = originY + height;
        Region region = new Region(minX, minY, minZ, maxX, maxY, maxZ, speciesId);

        int minCX = minX >> 4;
        int maxCX = maxX >> 4;
        int minCZ = minZ >> 4;
        int maxCZ = maxZ >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (platform.isChunkLoaded(worldName, cx, cz)) {
                    platform.updateChunkData(worldName, cx, cz, raw -> {
                        List<Region> regions = decodeAll(raw);
                        regions.add(region);
                        return encodeAll(regions);
                    });
                } else {
                    pendingChunkRegions
                            .computeIfAbsent(new WorldChunkKey(worldName, cx, cz), k -> new ArrayList<>())
                            .add(region);
                }
            }
        }
    }

    /**
     * Flushes any tree regions that were deferred because chunk {@code (chunkX, chunkZ)} in {@code
     * worldName} was not yet loaded when a neighboring tree was recorded (S-005).
     */
    public void flushPendingChunk(String worldName, int chunkX, int chunkZ) {
        if (worldName == null) return;
        List<Region> pending = pendingChunkRegions.remove(new WorldChunkKey(worldName, chunkX, chunkZ));
        if (pending == null || pending.isEmpty()) return;
        platform.updateChunkData(worldName, chunkX, chunkZ, raw -> {
            List<Region> regions = decodeAll(raw);
            regions.addAll(pending);
            return encodeAll(regions);
        });
    }

    /**
     * Return the species id of the tracked tree occupying the given block, or
     * {@code null} when no tree region covers it.
     */
    public String resolve(String worldName, int x, int y, int z) {
        Region region = regionAt(worldName, x, y, z);
        return region != null ? region.speciesId() : null;
    }

    /**
     * Return the tracked tree {@link Region} occupying the given block, or
     * {@code null} when no tree region covers it. Unlike {@link #resolve}, this
     * exposes the full bounding box so callers can, for example, look for the
     * tree's remaining wood to decide whether its leaves should still be
     * protected from decay.
     */
    public Region regionAt(String worldName, int x, int y, int z) {
        if (worldName == null) return null;
        for (Region region : read(worldName, x >> 4, z >> 4)) {
            if (region.contains(x, y, z)) {
                return region;
            }
        }
        return null;
    }

    private List<Region> read(String worldName, int chunkX, int chunkZ) {
        return decodeAll(platform.getChunkData(worldName, chunkX, chunkZ));
    }

    static List<Region> decodeAll(String raw) {
        List<Region> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            Region r = Region.decode(line);
            if (r != null) out.add(r);
        }
        return out;
    }

    static String encodeAll(List<Region> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Region r : regions) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.encode());
        }
        return sb.toString();
    }
}
