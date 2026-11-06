package net.leaf.treegen.common;

import io.github.dailystruggle.server.api.BlockBreakContext;
import io.github.dailystruggle.server.api.ChunkLoadContext;
import io.github.dailystruggle.server.api.LeavesDecayContext;
import io.github.dailystruggle.server.api.PlayerInteractBlockContext;
import io.github.dailystruggle.server.api.SaplingGrowContext;
import io.github.dailystruggle.server.api.WorldEventHook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Platform-neutral event controller for LeafTreeGen, subscribing to {@link WorldEventHook} on
 * {@link Platform} to handle sapling planting, leaf-decay/block-break sapling drops and cut-tree
 * cancellation, chunk-load growth resumption and procedural placement, and vanilla tree
 * replacement across Paper/Folia, Fabric, and NeoForge.
 */
public final class TreeGenEventController implements AutoCloseable {

    private static final long PLACEMENT_DELAY_TICKS = 40L;
    private static final int MAX_PENDING_SLICES_PER_CHUNK = 32;

    private record WorldChunkKey(String worldName, int chunkX, int chunkZ) {}

    private final Platform platform;
    private final Supplier<TreeGenConfig> configRef;
    private final Supplier<TreeRegistry> registryRef;
    private final ProceduralGenerator proceduralGenerator;
    private final GrowthManager growthManager;
    private final TreeFarmStore farmStore;
    private final Random random = new Random();
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    private final Map<WorldChunkKey, List<Map<TreeModel.BlockPos, String>>> pendingChunkSlices =
            new ConcurrentHashMap<>();
    private final java.util.Set<WorldChunkKey> seenChunks = ConcurrentHashMap.newKeySet();

    public TreeGenEventController(Platform platform,
                                  Supplier<TreeGenConfig> configRef,
                                  Supplier<TreeRegistry> registryRef,
                                  ProceduralGenerator proceduralGenerator,
                                  GrowthManager growthManager,
                                  TreeFarmStore farmStore) {
        this.platform = platform;
        this.configRef = configRef;
        this.registryRef = registryRef;
        this.proceduralGenerator = proceduralGenerator;
        this.growthManager = growthManager;
        this.farmStore = farmStore;
    }

    /**
     * Subscribes all handlers to {@link Platform#getWorldEventHook()}.
     */
    public void register() {
        WorldEventHook hook = platform.getWorldEventHook();
        if (hook == null) return;
        subscriptions.add(hook.onPlayerInteractBlock(this::onPlayerInteractBlock));
        subscriptions.add(hook.onBlockBreak(this::onBlockBreak));
        subscriptions.add(hook.onLeavesDecay(this::onLeavesDecay));
        subscriptions.add(hook.onChunkLoad(this::onChunkLoad));
        subscriptions.add(hook.onSaplingGrow(this::onSaplingGrow));
    }

    @Override
    public void close() {
        for (WorldChunkKey key : new ArrayList<>(pendingChunkSlices.keySet())) {
            retryPendingSavedChunkPatch(key);
        }
        for (AutoCloseable sub : subscriptions) {
            try {
                sub.close();
            } catch (Exception ignored) {
            }
        }
        subscriptions.clear();
    }

    public void onPlayerInteractBlock(PlayerInteractBlockContext ctx) {
        if (ctx == null || !ctx.mainHand()) {
            return;
        }
        String speciesId = resolveTag(ctx, Platform.TAG_TREE_SPECIES,
                "leaf_treegen_tree_species", "leaf-treegen:tree_species");
        if (speciesId == null || speciesId.isBlank()) {
            return;
        }

        ctx.cancel();

        if (!ctx.hasPermission("leaftreegen.sapling")) {
            platform.sendMessage(ctx.playerId(), "You can't plant magical saplings.", true);
            return;
        }

        TreeGenConfig cfg = configRef.get();
        TreeSpecies species = cfg == null ? null : cfg.get(speciesId);
        if (species == null) {
            platform.sendMessage(ctx.playerId(), "Unknown tree species: " + speciesId, true);
            return;
        }

        if (!cfg.allowsWaterGrowth(species.id()) && ctx.clickedOrTargetLiquid()) {
            platform.sendMessage(ctx.playerId(), species.displayName() + " won't take root on water.", true);
            return;
        }

        String boundVariant = resolveTag(ctx, Platform.TAG_TREE_VARIANT,
                "leaf_treegen_tree_variant", "leaf-treegen:tree_variant");
        String variant = null;
        TreeRegistry registry = registryRef.get();
        String templateWorld = platform.resolveTemplateWorld(ctx.worldName());
        if (boundVariant != null && !boundVariant.isBlank()) {
            variant = boundVariant;
        } else if (registry != null) {
            List<String> variants = registry.variantsFor(templateWorld, species);
            if (!variants.isEmpty()) {
                variant = variants.get(random.nextInt(variants.size()));
            }
        }

        final boolean canProcedural = proceduralGenerator != null && proceduralGenerator.canGenerate(species);
        if (variant == null && !canProcedural) {
            platform.sendMessage(ctx.playerId(), "No tree templates available.", true);
            return;
        }

        final String worldName = ctx.worldName();
        final int targetX = ctx.targetX();
        final int targetY = ctx.targetY();
        final int targetZ = ctx.targetZ();
        final String finalVariant = variant;
        final boolean explicitVariant = boundVariant != null && !boundVariant.isBlank();
        final boolean useStagedGrowth = species.growth() != null && species.growth().enabled()
                && canProcedural && growthManager != null;
        final long growthSeed = random.nextLong();

        ctx.consumeItem(1);
        platform.scheduleDelayedTask(worldName, targetX >> 4, targetZ >> 4, () -> {
            if (useStagedGrowth) {
                boolean started = growthManager.startGrowth(
                        worldName, targetX, targetY, targetZ, species, growthSeed);
                if (started) {
                    platform.sendMessage(ctx.playerId(), "A " + species.displayName() + " begins to grow!", false);
                } else {
                    if (!ctx.creativeMode()) {
                        platform.dropSapling(worldName, targetX, targetY, targetZ, species, 1);
                    }
                    platform.sendMessage(ctx.playerId(), "Not enough room for " + species.displayName() + " to grow.", true);
                }
                return;
            }

            boolean placed = false;
            if (explicitVariant && finalVariant != null) {
                placed = platform.placeStructure(worldName, targetX, targetY, targetZ, finalVariant);
            }
            if (!placed && canProcedural) {
                placed = placeProceduralByChunk(worldName, targetX, targetY, targetZ, species, random);
            }
            if (!placed && finalVariant != null) {
                placed = platform.placeStructure(worldName, targetX, targetY, targetZ, finalVariant);
            }
            if (placed) {
                if (farmStore != null) {
                    int radius = SaplingDropResolver.estimateCanopyRadius(species);
                    int height = SaplingDropResolver.estimateHeight(species);
                    farmStore.recordTree(worldName, targetX, targetY, targetZ, radius, height, species.id());
                }
                platform.sendMessage(ctx.playerId(), "A " + species.displayName() + " springs up!", false);
            } else {
                if (!ctx.creativeMode()) {
                    platform.dropSapling(worldName, targetX, targetY, targetZ, species, 1);
                }
                platform.sendMessage(ctx.playerId(), "The sapling failed to grow.", true);
            }
        }, 0L);
    }

    public void onBlockBreak(BlockBreakContext ctx) {
        if (ctx == null) return;
        if (growthManager != null) {
            growthManager.onBlockBroken(ctx.worldName(), ctx.x(), ctx.y(), ctx.z(), ctx.blockKey());
        }
        String keyLower = ctx.blockKey().toLowerCase(Locale.ROOT);
        if (!keyLower.endsWith("leaves")) {
            return;
        }
        maybeDropSapling(ctx.worldName(), ctx.x(), ctx.y(), ctx.z(), ctx.blockKey(), ctx.biomeKey());
    }

    public void onLeavesDecay(LeavesDecayContext ctx) {
        if (ctx == null) return;
        maybeDropSapling(ctx.worldName(), ctx.x(), ctx.y(), ctx.z(), ctx.blockKey(), ctx.biomeKey());
    }

    private void maybeDropSapling(String worldName, int x, int y, int z, String blockKey, String biomeKey) {
        TreeGenConfig cfg = configRef.get();
        if (cfg == null) return;
        double chance = cfg.saplingDropChance();
        if (chance <= 0.0) return;

        TreeSpecies species = resolveDroppedSpecies(cfg, worldName, x, y, z, blockKey, biomeKey);
        if (species == null) return;
        if (random.nextDouble() >= chance) return;

        platform.dropSapling(worldName, x, y, z, species, 1);
    }

    private TreeSpecies resolveDroppedSpecies(TreeGenConfig cfg, String worldName, int x, int y, int z,
                                              String blockKey, String biomeKey) {
        if (farmStore != null) {
            String trackedId = farmStore.resolve(worldName, x, y, z);
            if (trackedId != null) {
                TreeSpecies tracked = cfg.get(trackedId);
                if (tracked != null) return tracked;
            }
        }
        String effectiveBiome = (biomeKey != null && !biomeKey.isEmpty())
                ? biomeKey
                : platform.getBiomeKey(worldName, x, y, z);
        return SaplingDropResolver.resolveByBiome(cfg.species().values(), effectiveBiome, blockKey);
    }

    public void onChunkLoad(ChunkLoadContext ctx) {
        if (ctx == null) return;
        platform.recordChunkLoad(ctx.newChunk());
        String worldName = ctx.worldName();
        int chunkX = ctx.chunkX();
        int chunkZ = ctx.chunkZ();
        if (worldName != null) {
            seenChunks.add(new WorldChunkKey(worldName, chunkX, chunkZ));
        }
        flushPendingOverhangs(worldName, chunkX, chunkZ);
        if (farmStore != null) {
            farmStore.flushPendingChunk(worldName, chunkX, chunkZ);
        }
        if (!ctx.newChunk()) {
            if (growthManager != null) {
                growthManager.resumeChunk(worldName, chunkX, chunkZ);
            }
            return;
        }
        TreeGenConfig cfg = configRef.get();
        if (cfg == null || !cfg.useProcedural() || proceduralGenerator == null) {
            return;
        }
        if (!platform.isChunkLoaded(worldName, chunkX, chunkZ)) {
            return;
        }

        long worldSeed = ctx.worldSeed();
        for (TreeSpecies species : cfg.species().values()) {
            if (!species.worldgen() || (!proceduralGenerator.canGenerate(species) && species.isFeatureTreesOnly())) {
                continue;
            }
            if (!proceduralGenerator.canGenerate(species)) {
                continue;
            }

            int spacing = Math.max(1, species.spacing());
            int salt = species.effectiveSalt();
            int[] targetChunk = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(chunkX, chunkZ, spacing, worldSeed, salt);
            if (chunkX != targetChunk[0] || chunkZ != targetChunk[1]) {
                continue;
            }
            Random gridRandom = new Random(
                    io.github.dailystruggle.rtp.common.predict.DistributionSampler
                            .variantSeed(species.id(), (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL) ^ worldSeed));

            int chosenX = Integer.MIN_VALUE;
            int chosenY = Integer.MIN_VALUE;
            int chosenZ = Integer.MIN_VALUE;
            for (int attempt = 0; attempt < 4; attempt++) {
                int candX = (chunkX << 4) + gridRandom.nextInt(16);
                int candZ = (chunkZ << 4) + gridRandom.nextInt(16);
                int candY = platform.findTreeGroundY(worldName, candX, candZ);
                if (candY == Integer.MIN_VALUE) {
                    continue;
                }
                if (!cfg.allowsWaterGrowth(species.id())
                        && (isLiquidOrAquaticState(platform.getBlockState(worldName, candX, candY, candZ))
                                || isLiquidOrAquaticState(platform.getBlockState(worldName, candX, candY + 1, candZ)))) {
                    continue;
                }
                String exactBiome = platform.getBiomeKey(worldName, candX, candY, candZ);
                if (species.allowsWorldgenBiome(exactBiome)) {
                    chosenX = candX;
                    chosenY = candY;
                    chosenZ = candZ;
                    break;
                }
            }
            if (chosenY == Integer.MIN_VALUE) {
                continue;
            }
            int x = chosenX;
            int y = chosenY;
            int z = chosenZ;

            Random treeRandom = new Random(gridRandom.nextLong());
            if (species.replaceVanilla()) {
                Map<Long, Map<TreeModel.BlockPos, String>> clearingChunks = new HashMap<>();
                int radius = 2;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dy = 1; dy <= 40; dy++) {
                            int bx = x + dx;
                            int by = y + dy;
                            int bz = z + dz;
                            int bcx = bx >> 4;
                            int bcz = bz >> 4;
                            long key = TreeModel.chunkKey(bcx, bcz);
                            clearingChunks.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                    .put(new TreeModel.BlockPos(bx, by, bz), "minecraft:void_air");
                        }
                    }
                }
                clearingChunks.forEach((key, blocks) -> {
                    int bcx = TreeModel.chunkX(key);
                    int bcz = TreeModel.chunkZ(key);
                    placeOrDeferChunkBlocks(worldName, bcx, bcz, blocks);
                });
            }
            placeProceduralByChunk(worldName, x, y + 1, z, species, treeRandom);
            if (farmStore != null) {
                int radius = SaplingDropResolver.estimateCanopyRadius(species);
                int height = SaplingDropResolver.estimateHeight(species);
                farmStore.recordTree(worldName, x, y + 1, z, radius, height, species.id());
            }
        }
    }

    private static boolean isLiquidOrAquaticState(String canonicalState) {
        if (canonicalState == null || canonicalState.isBlank()) {
            return false;
        }
        int bracket = canonicalState.indexOf('[');
        String base = (bracket >= 0 ? canonicalState.substring(0, bracket) : canonicalState)
                .toLowerCase(Locale.ROOT);
        if (!base.contains(":")) {
            base = "minecraft:" + base;
        }
        return base.equals("minecraft:water")
                || base.equals("minecraft:lava")
                || base.equals("minecraft:bubble_column")
                || base.equals("minecraft:kelp")
                || base.equals("minecraft:kelp_plant")
                || base.equals("minecraft:seagrass")
                || base.equals("minecraft:tall_seagrass");
    }

    /**
     * Generates a procedural tree at {@code (ox, oy, oz)} and places each per-chunk slice
     * immediately if its chunk is loaded, retroactively patches saved chunks on disk via
     * {@link io.github.dailystruggle.anvil.RegionFileResolver#patchChunkBlocks}, or defers
     * ungenerated neighbor overhang slices until their chunk fires
     * {@link #onChunkLoad(ChunkLoadContext)} (S-005).
     */
    boolean placeProceduralByChunk(
            String worldName, int ox, int oy, int oz, TreeSpecies species, Random rng) {
        if (proceduralGenerator == null || species == null) return false;
        SegmentedTreeModel model = proceduralGenerator.getOrGenerateModel(species, rng);
        if (model == null) {
            platform.logger().warning("Placement failed: buildModel returned null for species " + species.id());
            return false;
        }
        Map<Long, Map<TreeModel.BlockPos, String>> chunks = model.partitionByChunk(ox, oy, oz);
        List<Long> chunkKeys = new ArrayList<>(chunks.keySet());
        java.util.Collections.sort(chunkKeys);
        for (long key : chunkKeys) {
            int cx = TreeModel.chunkX(key);
            int cz = TreeModel.chunkZ(key);
            placeOrDeferChunkBlocks(worldName, cx, cz, chunks.get(key));
        }
        return true;
    }

    private void placeOrDeferChunkBlocks(
            String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        if (platform.isChunkLoaded(worldName, chunkX, chunkZ)) {
            Map<TreeModel.BlockPos, String> replaceable = filterLoadedChunkReplaceable(worldName, chunkX, chunkZ, blocks);
            if (!replaceable.isEmpty()) {
                platform.setBlocks(worldName, chunkX, chunkZ, replaceable);
            }
        } else if (!patchSavedChunkIfPresent(worldName, chunkX, chunkZ, blocks)) {
            WorldChunkKey key = new WorldChunkKey(worldName, chunkX, chunkZ);
            boolean[] firstForKey = {false};
            pendingChunkSlices.compute(key, (k, existing) -> {
                List<Map<TreeModel.BlockPos, String>> list =
                        existing != null ? existing : new ArrayList<>();
                if (list.isEmpty()) {
                    firstForKey[0] = true;
                }
                if (list.size() < MAX_PENDING_SLICES_PER_CHUNK) {
                    list.add(new LinkedHashMap<>(blocks));
                }
                return list;
            });
            if (firstForKey[0] && seenChunks.contains(key)) {
                platform.scheduleDelayedTask(
                        worldName, chunkX, chunkZ, () -> retryPendingSavedChunkPatch(key), 20L);
            }
        }
    }

    private void retryPendingSavedChunkPatch(WorldChunkKey key) {
        if (key == null) return;
        if (platform.isChunkLoaded(key.worldName(), key.chunkX(), key.chunkZ())) {
            flushPendingOverhangs(key.worldName(), key.chunkX(), key.chunkZ());
            return;
        }
        List<Map<TreeModel.BlockPos, String>> slices = pendingChunkSlices.get(key);
        if (slices == null || slices.isEmpty()) {
            return;
        }
        Map<TreeModel.BlockPos, String> merged = new LinkedHashMap<>();
        synchronized (slices) {
            for (Map<TreeModel.BlockPos, String> slice : slices) {
                if (slice != null) {
                    merged.putAll(slice);
                }
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        if (patchSavedChunkIfPresent(key.worldName(), key.chunkX(), key.chunkZ(), merged)) {
            pendingChunkSlices.remove(key, slices);
        }
    }

    private boolean patchSavedChunkIfPresent(
            String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
        if (worldName == null || blocks == null || blocks.isEmpty()) {
            return false;
        }
        java.nio.file.Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder == null) {
            return false;
        }
        List<io.github.dailystruggle.anvil.RegionFileReader.BlockPatch> patches =
                new ArrayList<>(blocks.size());
        for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
            TreeModel.BlockPos pos = e.getKey();
            patches.add(new io.github.dailystruggle.anvil.RegionFileReader.BlockPatch(
                    pos.x(), pos.y(), pos.z(), e.getValue()));
        }
        try {
            return io.github.dailystruggle.anvil.RegionFileResolver.patchChunkBlocks(
                    worldFolder, "", chunkX, chunkZ, patches, TreeGenEventController::isSavedStateReplaceable);
        } catch (java.io.IOException ex) {
            platform.logger().warning("Failed to patch saved chunk (" + chunkX + "," + chunkZ + ") in "
                    + worldName + ": " + ex.getMessage());
            return false;
        }
    }

    private static boolean isSavedStateReplaceable(String canonicalState) {
        if (canonicalState == null || canonicalState.isBlank()) {
            return true;
        }
        int bracket = canonicalState.indexOf('[');
        String base = (bracket >= 0 ? canonicalState.substring(0, bracket) : canonicalState)
                .toLowerCase(Locale.ROOT);
        if (!base.contains(":")) {
            base = "minecraft:" + base;
        }
        return base.equals("minecraft:air")
                || base.equals("minecraft:cave_air")
                || base.equals("minecraft:void_air")
                || base.equals("minecraft:short_grass")
                || base.equals("minecraft:grass")
                || base.equals("minecraft:tall_grass")
                || base.equals("minecraft:fern")
                || base.equals("minecraft:large_fern")
                || base.equals("minecraft:dead_bush")
                || base.equals("minecraft:vine")
                || base.equals("minecraft:snow")
                || base.endsWith("_leaves")
                || base.endsWith("_sapling");
    }

    /**
     * Flushes any deferred overhang slices waiting for chunk {@code (chunkX, chunkZ)} in {@code
     * worldName}, re-checking {@link Platform#isReplaceable(String, int, int, int)} per block and
     * skipping non-replaceable blocks so deferred overhangs never overwrite player builds.
     */
    public void flushPendingOverhangs(String worldName, int chunkX, int chunkZ) {
        if (worldName == null || !platform.isChunkLoaded(worldName, chunkX, chunkZ)) return;
        List<Map<TreeModel.BlockPos, String>> slices =
                pendingChunkSlices.remove(new WorldChunkKey(worldName, chunkX, chunkZ));
        if (slices == null || slices.isEmpty()) return;
        for (Map<TreeModel.BlockPos, String> slice : slices) {
            Map<TreeModel.BlockPos, String> replaceable = filterLoadedChunkReplaceable(worldName, chunkX, chunkZ, slice);
            if (!replaceable.isEmpty()) {
                platform.setBlocks(worldName, chunkX, chunkZ, replaceable);
            }
        }
    }

    /**
     * Filters {@code slice} for already-loaded chunk {@code (chunkX, chunkZ)} without synchronous
     * chunk loading (S-005).
     */
    private Map<TreeModel.BlockPos, String> filterLoadedChunkReplaceable(
            String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> slice) {
        if (!platform.isChunkLoaded(worldName, chunkX, chunkZ)) {
            return Map.of();
        }
        Map<TreeModel.BlockPos, String> replaceable = new LinkedHashMap<>();
        for (Map.Entry<TreeModel.BlockPos, String> entry : slice.entrySet()) {
            TreeModel.BlockPos pos = entry.getKey();
            String state = entry.getValue();
            if ("minecraft:void_air".equals(state)
                    || isLoadedBlockReplaceable(worldName, pos.x(), pos.y(), pos.z())) {
                replaceable.put(pos, state);
            }
        }
        return replaceable;
    }

    private boolean isLoadedBlockReplaceable(String worldName, int x, int y, int z) {
        if (!platform.isChunkLoaded(worldName, x >> 4, z >> 4)) {
            return false;
        }
        return platform.isReplaceable(worldName, x, y, z);
    }

    /** Returns the number of chunks currently holding deferred overhang slices. */
    public int pendingOverhangChunkCount() {
        return pendingChunkSlices.size();
    }

    public void onSaplingGrow(SaplingGrowContext ctx) {
        if (ctx == null || !isVanillaSaplingOrTree(ctx.blockKey())) {
            return;
        }
        TreeGenConfig cfg = configRef.get();
        if (cfg == null) return;
        TreeRegistry registry = registryRef.get();
        String worldName = ctx.worldName();
        String templateWorld = platform.resolveTemplateWorld(worldName);
        String biome = (ctx.biomeKey() != null && !ctx.biomeKey().isEmpty())
                ? ctx.biomeKey()
                : platform.getBiomeKey(worldName, ctx.x(), ctx.y(), ctx.z());

        List<TreeSpecies> candidates = new ArrayList<>();
        for (TreeSpecies species : cfg.species().values()) {
            if (!species.replaceVanilla() || !species.worldgen()) {
                continue;
            }
            if (!species.allowsWorldgenBiome(biome) || species.isFeatureTreesOnly()) {
                continue;
            }
            boolean hasVariants = registry != null && !registry.variantsFor(templateWorld, species).isEmpty();
            boolean hasProcedural = proceduralGenerator != null && proceduralGenerator.canGenerate(species);
            if (hasVariants || hasProcedural) {
                candidates.add(species);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        TreeSpecies species = selectMatchingCandidate(candidates, ctx.blockKey());
        if (species == null) {
            return;
        }

        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        if (!cfg.allowsWaterGrowth(species.id()) && ctx.onLiquid()) {
            ctx.cancel();
            return;
        }

        if (species.growth() != null && species.growth().enabled()
                && proceduralGenerator != null && proceduralGenerator.canGenerate(species)
                && growthManager != null) {
            if (growthManager.startGrowth(worldName, x, y, z, species, random.nextLong())) {
                ctx.cancel();
            }
            return;
        }

        String previousBlock = platform.getBlockState(worldName, x, y, z);
        if (previousBlock == null || previousBlock.isBlank()) {
            previousBlock = "minecraft:oak_sapling";
        }
        platform.setBlock(worldName, x, y, z, "minecraft:air");

        boolean placed = false;
        if (proceduralGenerator != null && proceduralGenerator.canGenerate(species)) {
            placed = placeProceduralByChunk(worldName, x, y, z, species, random);
        }
        if (!placed) {
            List<String> variants = registry == null ? List.of() : registry.variantsFor(templateWorld, species);
            if (!variants.isEmpty()) {
                String variant = variants.get(random.nextInt(variants.size()));
                placed = platform.placeStructure(worldName, x, y, z, variant);
            }
        }

        if (placed) {
            ctx.cancel();
            if (farmStore != null) {
                int radius = SaplingDropResolver.estimateCanopyRadius(species);
                int height = SaplingDropResolver.estimateHeight(species);
                farmStore.recordTree(worldName, x, y, z, radius, height, species.id());
            }
        } else {
            platform.setBlock(worldName, x, y, z, previousBlock);
        }
    }

    private TreeSpecies selectMatchingCandidate(List<TreeSpecies> candidates, String blockKey) {
        String typeName = blockKey == null ? "" : blockKey.toLowerCase(Locale.ROOT);
        List<TreeSpecies> woodMatches = new ArrayList<>();
        for (TreeSpecies s : candidates) {
            String sapling = s.saplingItem() != null ? s.saplingItem().toLowerCase(Locale.ROOT) : "";
            if ((typeName.contains("dark_oak") && sapling.contains("dark_oak"))
                    || (typeName.contains("birch") && sapling.contains("birch"))
                    || ((typeName.contains("redwood") || typeName.contains("pine") || typeName.contains("spruce")) && sapling.contains("spruce"))
                    || (typeName.contains("jungle") && sapling.contains("jungle"))
                    || (typeName.contains("acacia") && sapling.contains("acacia"))
                    || (typeName.contains("cherry") && sapling.contains("cherry"))
                    || (typeName.contains("mangrove") && sapling.contains("mangrove"))
                    || (typeName.contains("azalea") && sapling.contains("azalea"))
                    || ((typeName.equals("tree") || typeName.equals("big_tree") || typeName.contains("swamp") || typeName.contains("oak"))
                            && sapling.contains("oak") && !sapling.contains("dark_oak"))) {
                woodMatches.add(s);
            }
        }
        List<TreeSpecies> pool = woodMatches.isEmpty() ? candidates : woodMatches;
        return pool.get(random.nextInt(pool.size()));
    }

    private static boolean isVanillaSaplingOrTree(String typeKey) {
        if (typeKey == null || typeKey.isEmpty()) return false;
        String name = typeKey.toUpperCase(Locale.ROOT);
        return name.contains("SAPLING") || name.contains("PROPAGULE") || name.contains("OAK")
                || name.contains("BIRCH") || name.contains("SPRUCE") || name.contains("JUNGLE")
                || name.contains("ACACIA") || name.contains("PINE") || name.contains("REDWOOD")
                || name.contains("CHERRY") || name.contains("MANGROVE") || name.contains("AZALEA")
                || name.contains("TREE");
    }

    private static String resolveTag(PlayerInteractBlockContext ctx, String... keys) {
        for (String key : keys) {
            String val = ctx.customTag(key);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }
}
