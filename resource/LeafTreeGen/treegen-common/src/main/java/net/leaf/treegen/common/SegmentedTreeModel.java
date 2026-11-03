package net.leaf.treegen.common;

import java.util.*;

/**
 * A compressed version of TreeModel that stores blocks in segments.
 * Optimized for memory by linearizing 3D space and using relative positions.
 */
public final class SegmentedTreeModel {
    private final String speciesId;
    private final List<Segment> segments;
    private final Map<Integer, String> palette;
    private final int[] bounds;

    public record Segment(int offsetX, int offsetY, int offsetZ, byte[] relativePositions, byte[] blockIndices) {}

    public SegmentedTreeModel(TreeModel model) {
        this.speciesId = model.getSpeciesId();
        this.palette = new HashMap<>();
        this.segments = new ArrayList<>();
        this.bounds = model.getBounds();
        compress(model);
    }

    private void compress(TreeModel model) {
        Map<String, Integer> reversePalette = new HashMap<>();
        Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
        if (blocks.isEmpty()) return;

        // Group by Y levels and sort them descending (top-to-bottom)
        Map<Integer, Map<TreeModel.BlockPos, String>> ySlices = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<TreeModel.BlockPos, String> entry : blocks.entrySet()) {
            ySlices.computeIfAbsent(entry.getKey().y(), k -> new HashMap<>()).put(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<Integer, Map<TreeModel.BlockPos, String>> slice : ySlices.entrySet()) {
            int y = slice.getKey();
            Map<TreeModel.BlockPos, String> sliceBlocks = slice.getValue();

            // Find bounds of slice to set anchor
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (TreeModel.BlockPos pos : sliceBlocks.keySet()) {
                minX = Math.min(minX, pos.x());
                minZ = Math.min(minZ, pos.z());
            }

            byte[] relPos = new byte[sliceBlocks.size() * 2]; // store relative x, z as bytes (trees are rarely > 256 wide)
            byte[] indices = new byte[sliceBlocks.size()];
            int i = 0;
            for (Map.Entry<TreeModel.BlockPos, String> entry : sliceBlocks.entrySet()) {
                TreeModel.BlockPos pos = entry.getKey();
                String state = entry.getValue();

                int paletteIdx = reversePalette.computeIfAbsent(state, k -> {
                    int id = palette.size();
                    palette.put(id, k);
                    return id;
                });

                relPos[i * 2] = (byte) (pos.x() - minX);
                relPos[i * 2 + 1] = (byte) (pos.z() - minZ);
                indices[i] = (byte) paletteIdx;
                i++;
            }
            segments.add(new Segment(minX, y, minZ, relPos, indices));
        }
    }

    public void place(Platform platform, String worldName, int ox, int oy, int oz) {
        long startTime = System.nanoTime();
        Map<Long, Map<TreeModel.BlockPos, String>> chunks = partitionByChunk(ox, oy, oz);
        long groupTime = System.nanoTime();

        int placed = 0;
        List<Long> chunkKeys = new ArrayList<>(chunks.keySet());
        Collections.sort(chunkKeys);

        for (long key : chunkKeys) {
            int cx = TreeModel.chunkX(key);
            int cz = TreeModel.chunkZ(key);
            Map<TreeModel.BlockPos, String> chunkBlocks = chunks.get(key);

            if (!platform.setBlocks(worldName, cx, cz, chunkBlocks)) {
                platform.logger().warning("[LeafTreeGen] Ceasing placements for model " + speciesId + " due to previous error.");
                return;
            }
            placed += chunkBlocks.size();
        }
        long endTime = System.nanoTime();

        double totalMs = (endTime - startTime) / 1_000_000.0;
        double groupMs = (groupTime - startTime) / 1_000_000.0;
        if (totalMs > 10.0) {
            platform.logger().info(String.format("[LeafTreeGen Profiling] %s placed at %d,%d,%d: %.2fms total (Grouping: %.2fms, Blocks: %d)",
                speciesId, ox, oy, oz, totalMs, groupMs, placed));
        }
    }

    /**
     * Returns the relative bounding box {@code [minX, minY, minZ, maxX, maxY, maxZ]} of this model.
     */
    public int[] getBounds() {
        return bounds.clone();
    }

    /**
     * Returns {@code true} if the horizontal bounding box of this model placed at {@code (ox, oz)}
     * overlaps chunk {@code (targetChunkX, targetChunkZ)}.
     */
    public boolean intersectsChunk(int ox, int oz, int targetChunkX, int targetChunkZ) {
        if (segments.isEmpty()) return false;
        int minCX = (ox + bounds[0]) >> 4;
        int maxCX = (ox + bounds[3]) >> 4;
        int minCZ = (oz + bounds[2]) >> 4;
        int maxCZ = (oz + bounds[5]) >> 4;
        return targetChunkX >= minCX && targetChunkX <= maxCX
                && targetChunkZ >= minCZ && targetChunkZ <= maxCZ;
    }

    /**
     * Returns the set of packed {@link TreeModel#chunkKey(int, int)} coordinates touched by this
     * model when placed at {@code (ox, oz)}.
     */
    public Set<Long> touchedChunks(int ox, int oz) {
        if (segments.isEmpty()) return Collections.emptySet();
        Set<Long> touched = new LinkedHashSet<>();
        for (Segment segment : segments) {
            for (int i = 0; i < segment.blockIndices.length; i++) {
                int worldX = ox + segment.offsetX + (segment.relativePositions[i * 2] & 0xFF);
                int worldZ = oz + segment.offsetZ + (segment.relativePositions[i * 2 + 1] & 0xFF);
                touched.add(TreeModel.chunkKey(worldX >> 4, worldZ >> 4));
            }
        }
        return Collections.unmodifiableSet(touched);
    }

    /**
     * Partitions all blocks of this model placed at {@code (ox, oy, oz)} into per-chunk maps keyed
     * by {@link TreeModel#chunkKey(int, int)}, preserving wood-first, leaf-distance-ascending
     * placement order within each chunk.
     */
    public Map<Long, Map<TreeModel.BlockPos, String>> partitionByChunk(int ox, int oy, int oz) {
        if (segments.isEmpty()) return Collections.emptyMap();
        Map<Long, Map<TreeModel.BlockPos, String>> chunks = new HashMap<>();

        // Decide the order blocks are written, then bucket them into per-chunk
        // LinkedHashMaps that preserve that order.
        //
        // Platforms that apply block physics compute a leaf's decay "distance" the
        // moment it is written (deferred by at most one tick), and a log or closer
        // leaf placed *afterwards* does not re-trigger that computation. We therefore
        // emit: (1) all wood, then (2) leaves ordered by their decay distance to the
        // nearest wood (nearest first), then (3) anything else.
        List<TreeModel.BlockPos> positions = new ArrayList<>();
        Map<TreeModel.BlockPos, String> stateByPos = new HashMap<>();
        for (Segment segment : segments) {
            for (int i = 0; i < segment.blockIndices.length; i++) {
                int worldX = ox + segment.offsetX + (segment.relativePositions[i * 2] & 0xFF);
                int worldY = oy + segment.offsetY;
                int worldZ = oz + segment.offsetZ + (segment.relativePositions[i * 2 + 1] & 0xFF);
                String state = palette.get(segment.blockIndices[i] & 0xFF);
                TreeModel.BlockPos pos = new TreeModel.BlockPos(worldX, worldY, worldZ);
                positions.add(pos);
                stateByPos.put(pos, state);
            }
        }

        List<TreeModel.BlockPos> ordered = orderForPlacement(positions, stateByPos);

        for (TreeModel.BlockPos pos : ordered) {
            int cx = pos.x() >> 4;
            int cz = pos.z() >> 4;
            long key = TreeModel.chunkKey(cx, cz);
            chunks.computeIfAbsent(key, k -> new LinkedHashMap<>())
                  .put(pos, stateByPos.get(pos));
        }
        return chunks;
    }

    /**
     * Returns the ordered world-coordinate block slice of this model placed at {@code (ox, oy, oz)}
     * clipped strictly to chunk {@code (targetChunkX, targetChunkZ)}, or an empty map when the tree
     * does not touch that chunk.
     */
    public Map<TreeModel.BlockPos, String> sliceForChunk(
            int ox, int oy, int oz, int targetChunkX, int targetChunkZ) {
        if (!intersectsChunk(ox, oz, targetChunkX, targetChunkZ)) {
            return Collections.emptyMap();
        }
        Map<TreeModel.BlockPos, String> slice =
                partitionByChunk(ox, oy, oz).get(TreeModel.chunkKey(targetChunkX, targetChunkZ));
        return slice != null ? Collections.unmodifiableMap(slice) : Collections.emptyMap();
    }

    /**
     * Places only the slice of this model that falls within chunk {@code (targetChunkX,
     * targetChunkZ)} when anchored at {@code (ox, oy, oz)}.
     */
    public boolean placeChunkSlice(
            Platform platform, String worldName, int ox, int oy, int oz,
            int targetChunkX, int targetChunkZ) {
        Map<TreeModel.BlockPos, String> slice = sliceForChunk(ox, oy, oz, targetChunkX, targetChunkZ);
        if (slice.isEmpty()) {
            return true;
        }
        return platform.setBlocks(worldName, targetChunkX, targetChunkZ, slice);
    }

    /**
     * Returns the order blocks should be written so that, when each leaf is placed,
     * the wood and every leaf closer to the trunk already exist: all wood first,
     * then leaves in ascending decay-distance from the nearest wood (a multi-source
     * BFS that mirrors vanilla's leaf-distance propagation), then everything else.
     */
    static List<TreeModel.BlockPos> orderForPlacement(
            List<TreeModel.BlockPos> positions, Map<TreeModel.BlockPos, String> stateByPos) {
        Set<TreeModel.BlockPos> leaves = new HashSet<>();
        Set<TreeModel.BlockPos> wood = new HashSet<>();
        for (TreeModel.BlockPos p : positions) {
            String s = stateByPos.get(p);
            if (isWood(s)) wood.add(p);
            else if (isLeaf(s)) leaves.add(p);
        }

        Map<TreeModel.BlockPos, Integer> dist =
                TreeModel.computeLeafDistancesFromWoodSet(leaves, leaves, wood, Integer.MAX_VALUE);

        List<TreeModel.BlockPos> woodList = new ArrayList<>();
        List<TreeModel.BlockPos> leafList = new ArrayList<>();
        List<TreeModel.BlockPos> otherList = new ArrayList<>();
        for (TreeModel.BlockPos p : positions) {
            String s = stateByPos.get(p);
            if (isWood(s)) woodList.add(p);
            else if (isLeaf(s)) leafList.add(p);
            else otherList.add(p);
        }
        // Leaves with no path to wood (shouldn't happen post-connector) sort last.
        leafList.sort(Comparator.comparingInt(p -> dist.getOrDefault(p, Integer.MAX_VALUE)));

        List<TreeModel.BlockPos> ordered = new ArrayList<>(positions.size());
        ordered.addAll(woodList);
        ordered.addAll(leafList);
        ordered.addAll(otherList);
        return ordered;
    }

    /**
     * Whether a palette block-state is "wood" (a log/wood/stem) for placement
     * ordering. Matches {@code ProceduralGenerator.isWood} so the leaf-decay
     * skeleton (trunk + decay-prevention branches) is recognised and placed first.
     */
    private static boolean isWood(String state) {
        return state.contains("log") || state.contains("wood") || state.contains("stem") || state.contains("hyphae");
    }

    private static boolean isLeaf(String state) {
        return state.contains("leaves");
    }

    public String getSpeciesId() {
        return speciesId;
    }

    public int getBlockCount() {
        int count = 0;
        for (Segment segment : segments) {
            count += segment.blockIndices.length;
        }
        return count;
    }
}
