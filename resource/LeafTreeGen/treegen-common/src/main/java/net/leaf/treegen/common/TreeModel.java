package net.leaf.treegen.common;

import io.github.dailystruggle.rtp.common.predict.PolarCurve;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory representation of a generated tree.
 * Can be selectively output to a platform (world) or structure format (NBT).
 */
public final class TreeModel {
    /** The six orthogonal face-adjacent neighbour offsets vanilla leaf-decay propagates through. */
    public static final int[][] ORTHOGONAL = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    public record BlockPos(int x, int y, int z) {
        public BlockPos add(int dx, int dy, int dz) {
            return new BlockPos(x + dx, y + dy, z + dz);
        }
    }

    /** Packs 2D chunk coordinates {@code (chunkX, chunkZ)} into a 64-bit key. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Extracts {@code chunkX} from a packed {@link #chunkKey(int, int)}. */
    public static int chunkX(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    /** Extracts {@code chunkZ} from a packed {@link #chunkKey(int, int)}. */
    public static int chunkZ(long chunkKey) {
        return (int) chunkKey;
    }

    /** Packs 2D planar block coordinates {@code (x, z)} into a 64-bit column key. */
    public static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Multi-source 6-connected Manhattan BFS from {@code wood} through {@code leafSet}, seeded by
     * every leaf in {@code leaves} orthogonally adjacent to {@code wood} (distance 1) and bounded
     * by {@code maxDistance}.
     */
    public static Map<BlockPos, Integer> computeLeafDistancesFromWoodSet(
            Collection<BlockPos> leaves,
            Set<BlockPos> leafSet,
            Set<BlockPos> wood,
            int maxDistance) {
        Map<BlockPos, Integer> dist = new HashMap<>();
        if (leaves == null || leaves.isEmpty() || wood == null || wood.isEmpty()) {
            return dist;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos leaf : leaves) {
            for (int[] o : ORTHOGONAL) {
                if (wood.contains(new BlockPos(leaf.x + o[0], leaf.y + o[1], leaf.z + o[2]))) {
                    dist.put(leaf, 1);
                    queue.add(leaf);
                    break;
                }
            }
        }
        propagateLeafDistances(dist, queue, leafSet, maxDistance);
        return dist;
    }

    /**
     * Multi-source 6-connected Manhattan BFS seeded directly from {@code woodSources} into
     * surrounding {@code leafSet} blocks up to {@code maxDistance}.
     */
    public static Map<BlockPos, Integer> computeLeafDistancesFromWoodSources(
            Collection<BlockPos> woodSources,
            Set<BlockPos> leafSet,
            int maxDistance) {
        Map<BlockPos, Integer> dist = new HashMap<>();
        if (woodSources == null || woodSources.isEmpty() || leafSet == null || leafSet.isEmpty()) {
            return dist;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos w : woodSources) {
            for (int[] o : ORTHOGONAL) {
                BlockPos n = new BlockPos(w.x + o[0], w.y + o[1], w.z + o[2]);
                if (leafSet.contains(n) && !dist.containsKey(n)) {
                    dist.put(n, 1);
                    queue.add(n);
                }
            }
        }
        propagateLeafDistances(dist, queue, leafSet, maxDistance);
        return dist;
    }

    private static void propagateLeafDistances(
            Map<BlockPos, Integer> dist,
            ArrayDeque<BlockPos> queue,
            Set<BlockPos> leafSet,
            int maxDistance) {
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            int d = dist.get(cur);
            if (d >= maxDistance) {
                continue;
            }
            for (int[] o : ORTHOGONAL) {
                BlockPos n = new BlockPos(cur.x + o[0], cur.y + o[1], cur.z + o[2]);
                if (leafSet.contains(n) && !dist.containsKey(n)) {
                    dist.put(n, d + 1);
                    queue.add(n);
                }
            }
        }
    }

    private final Map<BlockPos, String> blocks;
    private final String speciesId;

    public TreeModel(String speciesId) {
        this.speciesId = speciesId;
        this.blocks = new HashMap<>();
    }

    public TreeModel(String speciesId, Map<BlockPos, String> blocks, boolean copy) {
        this.speciesId = speciesId;
        this.blocks = copy ? new HashMap<>(blocks) : blocks;
    }

    public TreeModel(String speciesId, Map<BlockPos, String> blocks) {
        this(speciesId, blocks, false);
    }

    public void setBlock(int x, int y, int z, String block) {
        blocks.put(new BlockPos(x, y, z), block);
    }

    public String getBlock(int x, int y, int z) {
        return blocks.get(new BlockPos(x, y, z));
    }

    public Map<BlockPos, String> getBlocks() {
        return Collections.unmodifiableMap(blocks);
    }

    public void rotate(int quadrants) {
        quadrants = ((quadrants % 4) + 4) % 4;
        if (quadrants == 0) return;

        Map<BlockPos, String> newBlocks = new HashMap<>();
        for (Map.Entry<BlockPos, String> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            String state = entry.getValue();

            int[] rotated = PolarCurve.quadrantRotateXZ(pos.x, pos.z, quadrants);
            int nx = rotated[0];
            int nz = rotated[1];

            // Rotate block states if they have orientation
            if (state.contains("axis=")) {
                if (quadrants % 2 != 0) {
                    if (state.contains("axis=x")) {
                        state = state.replace("axis=x", "axis=z");
                    } else if (state.contains("axis=z")) {
                        state = state.replace("axis=z", "axis=x");
                    }
                }
            } else if (state.contains("facing=")) {
                for (int i = 0; i < quadrants; i++) {
                    if (state.contains("facing=north")) state = state.replace("facing=north", "facing=east_tmp");
                    else if (state.contains("facing=east")) state = state.replace("facing=east", "facing=south_tmp");
                    else if (state.contains("facing=south")) state = state.replace("facing=south", "facing=west_tmp");
                    else if (state.contains("facing=west")) state = state.replace("facing=west", "facing=north_tmp");

                    state = state.replace("_tmp", "");
                }
            } else if (state.contains("north=")
                    || state.contains("east=")
                    || state.contains("south=")
                    || state.contains("west=")) {
                for (int i = 0; i < quadrants; i++) {
                    state = state.replace("north=", "east_tmp=")
                            .replace("east=", "south_tmp=")
                            .replace("south=", "west_tmp=")
                            .replace("west=", "north_tmp=")
                            .replace("_tmp=", "=");
                }
            }

            newBlocks.put(new BlockPos(nx, pos.y, nz), state);
        }
        blocks.clear();
        blocks.putAll(newBlocks);
    }

    public String getSpeciesId() {
        return speciesId;
    }

    public Set<BlockPos> getPositions() {
        return blocks.keySet();
    }

    public void place(Platform platform, String worldName, int ox, int oy, int oz) {
        Map<Long, Map<BlockPos, String>> chunks = partitionByChunk(ox, oy, oz);
        chunks.forEach((key, chunkBlocks) -> {
            int cx = chunkX(key);
            int cz = chunkZ(key);
            platform.setBlocks(worldName, cx, cz, chunkBlocks);
        });
    }

    /**
     * Returns {@code true} if the horizontal bounding box of this model placed at {@code (ox, oz)}
     * overlaps chunk {@code (targetChunkX, targetChunkZ)}.
     */
    public boolean intersectsChunk(int ox, int oz, int targetChunkX, int targetChunkZ) {
        if (blocks.isEmpty()) return false;
        int[] b = getBounds();
        int minCX = (ox + b[0]) >> 4;
        int maxCX = (ox + b[3]) >> 4;
        int minCZ = (oz + b[2]) >> 4;
        int maxCZ = (oz + b[5]) >> 4;
        return targetChunkX >= minCX && targetChunkX <= maxCX
                && targetChunkZ >= minCZ && targetChunkZ <= maxCZ;
    }

    /**
     * Returns the set of packed {@link #chunkKey(int, int)} coordinates touched by this model when
     * placed at {@code (ox, oz)}.
     */
    public Set<Long> touchedChunks(int ox, int oz) {
        if (blocks.isEmpty()) return Collections.emptySet();
        Set<Long> touched = new java.util.LinkedHashSet<>();
        for (BlockPos pos : blocks.keySet()) {
            int cx = (ox + pos.x) >> 4;
            int cz = (oz + pos.z) >> 4;
            touched.add(chunkKey(cx, cz));
        }
        return Collections.unmodifiableSet(touched);
    }

    /**
     * Partitions all blocks of this model placed at {@code (ox, oy, oz)} into per-chunk maps keyed
     * by {@link #chunkKey(int, int)}, preserving wood-first, leaf-distance-ascending placement
     * order within each chunk.
     */
    public Map<Long, Map<BlockPos, String>> partitionByChunk(int ox, int oy, int oz) {
        if (blocks.isEmpty()) return Collections.emptyMap();
        java.util.List<BlockPos> worldPositions = new java.util.ArrayList<>(blocks.size());
        Map<BlockPos, String> stateByPos = new HashMap<>(blocks.size() * 2);
        blocks.forEach((pos, state) -> {
            BlockPos wp = new BlockPos(ox + pos.x, oy + pos.y, oz + pos.z);
            worldPositions.add(wp);
            stateByPos.put(wp, state);
        });
        java.util.List<BlockPos> ordered = SegmentedTreeModel.orderForPlacement(worldPositions, stateByPos);
        Map<Long, Map<BlockPos, String>> chunks = new java.util.TreeMap<>();
        for (BlockPos wp : ordered) {
            long key = chunkKey(wp.x >> 4, wp.z >> 4);
            chunks.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>())
                    .put(wp, stateByPos.get(wp));
        }
        return chunks;
    }

    /**
     * Returns the ordered world-coordinate block slice of this model placed at {@code (ox, oy, oz)}
     * clipped strictly to chunk {@code (targetChunkX, targetChunkZ)}, or an empty map when the tree
     * does not touch that chunk.
     */
    public Map<BlockPos, String> sliceForChunk(
            int ox, int oy, int oz, int targetChunkX, int targetChunkZ) {
        if (!intersectsChunk(ox, oz, targetChunkX, targetChunkZ)) {
            return Collections.emptyMap();
        }
        Map<BlockPos, String> slice =
                partitionByChunk(ox, oy, oz).get(chunkKey(targetChunkX, targetChunkZ));
        return slice != null ? Collections.unmodifiableMap(slice) : Collections.emptyMap();
    }

    /**
     * Places only the slice of this model that falls within chunk {@code (targetChunkX,
     * targetChunkZ)} when anchored at {@code (ox, oy, oz)}.
     */
    public boolean placeChunkSlice(
            Platform platform, String worldName, int ox, int oy, int oz,
            int targetChunkX, int targetChunkZ) {
        Map<BlockPos, String> slice = sliceForChunk(ox, oy, oz, targetChunkX, targetChunkZ);
        if (slice.isEmpty()) {
            return true;
        }
        return platform.setBlocks(worldName, targetChunkX, targetChunkZ, slice);
    }

    /**
     * Returns the bounding box of the tree model.
     * [minX, minY, minZ, maxX, maxY, maxZ]
     */
    public int[] getBounds() {
        if (blocks.isEmpty()) return new int[]{0, 0, 0, 0, 0, 0};
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }
}
