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
    private static final int MAX_CACHED_ORIGINS = 8192;
    private static final int MAX_OVERHANG_HALO_CHUNKS = 3;
    private record WorldChunkKey(String worldName, int chunkX, int chunkZ) {}

    private record ChunkSpeciesKey(String worldName, int chunkX, int chunkZ, String speciesId) {}

    private record AdjacentEdgeKey(
            String worldName, int targetChunkX, int targetChunkZ, int originChunkX, int originChunkZ) {}

    private record ResolvedTreeOrigin(int x, int y, int z, long treeSeed) {
        Random treeRandom() {
            return new Random(treeSeed);
        }
    }

    private interface ChunkLandData {
        int findTreeGroundY(int x, int z);

        String getBlockState(int x, int y, int z);

        String getBiomeKey(int x, int y, int z);
    }

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
    private final java.util.Set<WorldChunkKey> spawnedChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<AdjacentEdgeKey> completedAdjacentEdges =
            ConcurrentHashMap.newKeySet();
    private final Map<ChunkSpeciesKey, java.util.Optional<ResolvedTreeOrigin>> resolvedOriginCache =
            new ConcurrentHashMap<>();

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
        resolvedOriginCache.clear();
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
        if (!ctx.newChunk() && growthManager != null) {
            growthManager.resumeChunk(worldName, chunkX, chunkZ);
        }
        TreeGenConfig cfg = configRef.get();
        if (cfg == null || !cfg.useProcedural() || proceduralGenerator == null) {
            return;
        }
        if (!platform.isChunkLoaded(worldName, chunkX, chunkZ)) {
            return;
        }

        long worldSeed = ctx.worldSeed();
        if (ctx.newChunk()) {
            if (worldName != null) {
                spawnedChunks.add(new WorldChunkKey(worldName, chunkX, chunkZ));
            }
            spawnChunkTrees(worldName, chunkX, chunkZ, worldSeed, cfg);
        }
        completeAdjacentPreexistingChunkTrees(
                worldName, chunkX, chunkZ, ctx.newChunk(), worldSeed, cfg);
    }

    private List<TreeSpecies> allKnownSpecies(TreeGenConfig cfg) {
        List<TreeSpecies> all = new ArrayList<>();
        if (cfg != null && cfg.species() != null) {
            all.addAll(cfg.species().values());
        }
        TreeRegistry reg = registryRef != null ? registryRef.get() : null;
        if (reg != null && reg.getSpeciesMap() != null) {
            all.addAll(reg.getSpeciesMap().values());
        }
        return all;
    }

    private int computeMaxOverhangHaloChunks(
            TreeGenConfig cfg, List<TreeSpecies> allSpecies) {
        int maxHalo = 1;
        if (cfg == null || cfg.species() == null) {
            return maxHalo;
        }
        for (TreeSpecies species : cfg.species().values()) {
            if (!species.worldgen() || !proceduralGenerator.canGenerate(species)) {
                continue;
            }
            int reach = SaplingDropResolver.estimateCanopyRadius(species, allSpecies) + 6;
            int chunks = Math.min(MAX_OVERHANG_HALO_CHUNKS, Math.max(1, (reach + 15) >> 4));
            if (chunks > maxHalo) {
                maxHalo = chunks;
            }
        }
        return maxHalo;
    }

    private void spawnChunkTrees(
            String worldName, int chunkX, int chunkZ, long worldSeed, TreeGenConfig cfg) {
        ChunkLandData liveLand = liveChunkLandData(worldName);
        List<TreeSpecies> allSpecies = allKnownSpecies(cfg);
        for (TreeSpecies species : cfg.species().values()) {
            if (!species.worldgen() || !proceduralGenerator.canGenerate(species)) {
                continue;
            }
            int spacing = Math.max(1, species.spacing());
            int salt = species.effectiveSalt();
            int[] targetChunk = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(chunkX, chunkZ, spacing, worldSeed, salt);
            if (chunkX != targetChunk[0] || chunkZ != targetChunk[1]) {
                continue;
            }
            ResolvedTreeOrigin origin =
                    resolveChunkTreeOrigin(species, chunkX, chunkZ, worldSeed, cfg, liveLand);
            if (worldName != null) {
                if (resolvedOriginCache.size() >= MAX_CACHED_ORIGINS) {
                    resolvedOriginCache.clear();
                }
                resolvedOriginCache.put(
                        new ChunkSpeciesKey(worldName, chunkX, chunkZ, species.id()),
                        java.util.Optional.ofNullable(origin));
            }
            if (origin == null) {
                continue;
            }
            int x = origin.x();
            int y = origin.y();
            int z = origin.z();
            placeProceduralWithClearingByChunk(
                    worldName, chunkX, chunkZ, x, y + 1, z, species, origin.treeRandom(), species.replaceVanilla());
            if (farmStore != null) {
                int radius = SaplingDropResolver.estimateCanopyRadius(species, allSpecies);
                int height = SaplingDropResolver.estimateHeight(species, allSpecies);
                farmStore.recordTree(worldName, x, y + 1, z, radius, height, species.id());
            }
        }
    }

    /**
     * Completes overhanging procedural trees between {@code (chunkX, chunkZ)} and surrounding
     * chunks in its overhang halo, evaluating both incoming and outgoing edges for already-loaded
     * neighbors so out-of-order pregenerated chunk loads never clip trees at chunk boundaries.
     */
    private void completeAdjacentPreexistingChunkTrees(
            String worldName,
            int chunkX,
            int chunkZ,
            boolean targetIsNewChunk,
            long worldSeed,
            TreeGenConfig cfg) {
        if (worldName == null || !platform.isChunkLoaded(worldName, chunkX, chunkZ)) {
            return;
        }
        List<TreeSpecies> allSpecies = allKnownSpecies(cfg);
        int maxHalo = computeMaxOverhangHaloChunks(cfg, allSpecies);
        for (int dcx = -maxHalo; dcx <= maxHalo; dcx++) {
            for (int dcz = -maxHalo; dcz <= maxHalo; dcz++) {
                if (dcx != 0 || dcz != 0) {
                    completeHaloPair(
                            worldName,
                            chunkX,
                            chunkZ,
                            chunkX + dcx,
                            chunkZ + dcz,
                            dcx,
                            dcz,
                            targetIsNewChunk,
                            worldSeed,
                            cfg,
                            allSpecies);
                }
            }
        }
    }

    private void completeHaloPair(
            String worldName,
            int chunkX,
            int chunkZ,
            int adjCX,
            int adjCZ,
            int dcx,
            int dcz,
            boolean targetIsNewChunk,
            long worldSeed,
            TreeGenConfig cfg,
            List<TreeSpecies> allSpecies) {
        WorldChunkKey adjKey = new WorldChunkKey(worldName, adjCX, adjCZ);
        boolean adjLoadedInMemory =
                seenChunks.contains(adjKey) && platform.isChunkLoaded(worldName, adjCX, adjCZ);
        boolean adjSpawnedInSession = spawnedChunks.contains(adjKey);
        boolean allowDiskFallback =
                targetIsNewChunk
                        && Math.abs(dcx) <= 1
                        && Math.abs(dcz) <= 1
                        && !adjSpawnedInSession;
        if (adjLoadedInMemory || allowDiskFallback || adjSpawnedInSession) {
            completeAdjacentChunkEdge(
                    worldName,
                    chunkX,
                    chunkZ,
                    adjCX,
                    adjCZ,
                    allowDiskFallback,
                    worldSeed,
                    cfg,
                    allSpecies);
        }
        if (adjLoadedInMemory) {
            completeAdjacentChunkEdge(
                    worldName,
                    adjCX,
                    adjCZ,
                    chunkX,
                    chunkZ,
                    false,
                    worldSeed,
                    cfg,
                    allSpecies);
        }
    }

    private void completeAdjacentChunkEdge(
            String worldName,
            int chunkX,
            int chunkZ,
            int adjCX,
            int adjCZ,
            boolean allowSavedDiskFallback,
            long worldSeed,
            TreeGenConfig cfg,
            List<TreeSpecies> allSpecies) {
        AdjacentEdgeKey edgeKey = new AdjacentEdgeKey(worldName, chunkX, chunkZ, adjCX, adjCZ);
        if (completedAdjacentEdges.contains(edgeKey)) {
            return;
        }
        List<TreeSpecies> matchingSpecies =
                matchingProceduralSpeciesForChunk(
                        adjCX, adjCZ, chunkX, chunkZ, worldSeed, cfg, allSpecies);
        if (matchingSpecies.isEmpty()) {
            completedAdjacentEdges.add(edgeKey);
            return;
        }
        ChunkLandData adjLand = null;
        boolean landResolutionAttempted = false;
        Map<TreeModel.BlockPos, String> combinedSlice = new LinkedHashMap<>();
        for (TreeSpecies species : matchingSpecies) {
            ChunkSpeciesKey cacheKey =
                    new ChunkSpeciesKey(worldName, adjCX, adjCZ, species.id());
            java.util.Optional<ResolvedTreeOrigin> cached = resolvedOriginCache.get(cacheKey);
            ResolvedTreeOrigin origin;
            if (cached != null) {
                origin = cached.orElse(null);
            } else {
                if (!landResolutionAttempted) {
                    landResolutionAttempted = true;
                    adjLand = resolvePreexistingChunkLandData(
                            worldName, adjCX, adjCZ, allowSavedDiskFallback);
                }
                if (adjLand == null) {
                    return;
                }
                origin = resolveOrCacheChunkTreeOrigin(
                        worldName, species, adjCX, adjCZ, worldSeed, cfg, adjLand);
            }
            if (origin != null) {
                accumulateAdjacentSpeciesSlice(
                        worldName, chunkX, chunkZ, species, origin, combinedSlice, allSpecies);
            }
        }
        completedAdjacentEdges.add(edgeKey);
        if (combinedSlice.isEmpty()) {
            return;
        }
        Map<TreeModel.BlockPos, String> replaceable =
                filterLoadedChunkReplaceable(worldName, chunkX, chunkZ, combinedSlice);
        if (!replaceable.isEmpty()) {
            platform.setBlocks(worldName, chunkX, chunkZ, replaceable);
        }
    }

    private List<TreeSpecies> matchingProceduralSpeciesForChunk(
            int adjCX,
            int adjCZ,
            int targetCX,
            int targetCZ,
            long worldSeed,
            TreeGenConfig cfg,
            List<TreeSpecies> allSpecies) {
        List<TreeSpecies> matching = new ArrayList<>();
        for (TreeSpecies species : cfg.species().values()) {
            if (!species.worldgen() || !proceduralGenerator.canGenerate(species)) {
                continue;
            }
            int spacing = Math.max(1, species.spacing());
            int[] targetChunk = io.github.dailystruggle.rtp.common.predict.DistributionSampler
                    .gridCellSample(adjCX, adjCZ, spacing, worldSeed, species.effectiveSalt());
            if (adjCX == targetChunk[0]
                    && adjCZ == targetChunk[1]
                    && canSpeciesReachTargetChunk(
                            species, adjCX, adjCZ, targetCX, targetCZ, worldSeed, allSpecies)) {
                matching.add(species);
            }
        }
        return matching;
    }

    private static boolean canSpeciesReachTargetChunk(
            TreeSpecies species,
            int adjCX,
            int adjCZ,
            int targetCX,
            int targetCZ,
            long worldSeed,
            List<TreeSpecies> allSpecies) {
        int maxReach = SaplingDropResolver.estimateCanopyRadius(species, allSpecies) + 6;
        int minTargetX = targetCX << 4;
        int maxTargetX = minTargetX + 15;
        int minTargetZ = targetCZ << 4;
        int maxTargetZ = minTargetZ + 15;
        Random gridRandom = new Random(
                io.github.dailystruggle.rtp.common.predict.DistributionSampler
                        .variantSeed(
                                species.id(),
                                (((long) adjCX) << 32) ^ (adjCZ & 0xFFFFFFFFL) ^ worldSeed));
        for (int attempt = 0; attempt < 4; attempt++) {
            int candX = (adjCX << 4) + gridRandom.nextInt(16);
            int candZ = (adjCZ << 4) + gridRandom.nextInt(16);
            if (candX + maxReach >= minTargetX
                    && candX - maxReach <= maxTargetX
                    && candZ + maxReach >= minTargetZ
                    && candZ - maxReach <= maxTargetZ) {
                return true;
            }
        }
        return false;
    }

    private void accumulateAdjacentSpeciesSlice(
            String worldName,
            int chunkX,
            int chunkZ,
            TreeSpecies species,
            ResolvedTreeOrigin origin,
            Map<TreeModel.BlockPos, String> combinedSlice,
            List<TreeSpecies> allSpecies) {
        SegmentedTreeModel model =
                proceduralGenerator.getOrGenerateModel(species, origin.treeRandom());
        if (model == null || !model.intersectsChunk(origin.x(), origin.z(), chunkX, chunkZ)) {
            return;
        }
        Map<TreeModel.BlockPos, String> slice =
                model.sliceForChunk(origin.x(), origin.y() + 1, origin.z(), chunkX, chunkZ);
        if (slice.isEmpty()) {
            return;
        }
        mergeSliceInto(combinedSlice, slice);
        if (farmStore != null) {
            int radius = SaplingDropResolver.estimateCanopyRadius(species, allSpecies);
            int height = SaplingDropResolver.estimateHeight(species, allSpecies);
            farmStore.recordTree(
                    worldName, origin.x(), origin.y() + 1, origin.z(), radius, height, species.id());
        }
    }
    private ResolvedTreeOrigin resolveOrCacheChunkTreeOrigin(
            String worldName,
            TreeSpecies species,
            int chunkX,
            int chunkZ,
            long worldSeed,
            TreeGenConfig cfg,
            ChunkLandData landData) {
        if (worldName == null) {
            return resolveChunkTreeOrigin(species, chunkX, chunkZ, worldSeed, cfg, landData);
        }
        ChunkSpeciesKey key = new ChunkSpeciesKey(worldName, chunkX, chunkZ, species.id());
        java.util.Optional<ResolvedTreeOrigin> cached = resolvedOriginCache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        ResolvedTreeOrigin resolved =
                resolveChunkTreeOrigin(species, chunkX, chunkZ, worldSeed, cfg, landData);
        if (resolvedOriginCache.size() >= MAX_CACHED_ORIGINS) {
            resolvedOriginCache.clear();
        }
        resolvedOriginCache.put(key, java.util.Optional.ofNullable(resolved));
        return resolved;
    }

    private ResolvedTreeOrigin resolveChunkTreeOrigin(
            TreeSpecies species,
            int chunkX,
            int chunkZ,
            long worldSeed,
            TreeGenConfig cfg,
            ChunkLandData landData) {
        Random gridRandom = new Random(
                io.github.dailystruggle.rtp.common.predict.DistributionSampler
                        .variantSeed(
                                species.id(),
                                (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL) ^ worldSeed));
        int chosenX = Integer.MIN_VALUE;
        int chosenY = Integer.MIN_VALUE;
        int chosenZ = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < 4; attempt++) {
            int candX = (chunkX << 4) + gridRandom.nextInt(16);
            int candZ = (chunkZ << 4) + gridRandom.nextInt(16);
            int candY = landData.findTreeGroundY(candX, candZ);
            if (candY == Integer.MIN_VALUE) {
                continue;
            }
            String exactBiome = landData.getBiomeKey(candX, candY, candZ);
            if (!species.allowsWorldgenBiome(exactBiome)) {
                continue;
            }
            if (!cfg.allowsWaterGrowth(species.id())
                    && (isLiquidOrAquaticState(landData.getBlockState(candX, candY, candZ))
                            || isLiquidOrAquaticState(
                                    landData.getBlockState(candX, candY + 1, candZ)))) {
                continue;
            }
            chosenX = candX;
            chosenY = candY;
            chosenZ = candZ;
            break;
        }
        if (chosenY == Integer.MIN_VALUE) {
            return null;
        }
        return new ResolvedTreeOrigin(chosenX, chosenY, chosenZ, gridRandom.nextLong());
    }

    private ChunkLandData resolvePreexistingChunkLandData(
            String worldName, int chunkX, int chunkZ, boolean allowSavedDiskFallback) {
        WorldChunkKey key = new WorldChunkKey(worldName, chunkX, chunkZ);
        if (seenChunks.contains(key) && platform.isChunkLoaded(worldName, chunkX, chunkZ)) {
            return liveChunkLandData(worldName);
        }
        if (!allowSavedDiskFallback) {
            return null;
        }
        io.github.dailystruggle.anvil.AnvilChunkView view =
                readSavedChunkView(worldName, chunkX, chunkZ);
        if (view != null) {
            return savedChunkLandData(worldName, view);
        }
        return null;
    }

    private ChunkLandData liveChunkLandData(String worldName) {
        return new ChunkLandData() {
            private final Map<Long, Integer> groundYMemo = new HashMap<>();

            @Override
            public int findTreeGroundY(int x, int z) {
                long colKey = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
                Integer cachedY = groundYMemo.get(colKey);
                if (cachedY != null) {
                    return cachedY;
                }
                int y = platform.findTreeGroundY(worldName, x, z);
                if (y == Integer.MIN_VALUE) {
                    groundYMemo.put(colKey, Integer.MIN_VALUE);
                    return Integer.MIN_VALUE;
                }
                int minY = platform.getMinHeight(worldName);
                while (y > minY) {
                    String state = platform.getBlockState(worldName, x, y, z);
                    if (state == null) {
                        groundYMemo.put(colKey, y);
                        return y;
                    }
                    if (!platform.isReplaceable(worldName, x, y, z)
                            && !isTreeCanopyOrTrunkState(state)) {
                        groundYMemo.put(colKey, y);
                        return y;
                    }
                    y--;
                }
                groundYMemo.put(colKey, Integer.MIN_VALUE);
                return Integer.MIN_VALUE;
            }

            @Override
            public String getBlockState(int x, int y, int z) {
                return platform.getBlockState(worldName, x, y, z);
            }

            @Override
            public String getBiomeKey(int x, int y, int z) {
                return platform.getBiomeKey(worldName, x, y, z);
            }
        };
    }

    private ChunkLandData savedChunkLandData(
            String worldName, io.github.dailystruggle.anvil.AnvilChunkView view) {
        return new ChunkLandData() {
            @Override
            public int findTreeGroundY(int x, int z) {
                return findSavedTreeGroundY(worldName, view, x & 15, z & 15);
            }

            @Override
            public String getBlockState(int x, int y, int z) {
                String state = view.blockIdAt(x & 15, y, z & 15);
                return state != null ? state : "minecraft:air";
            }

            @Override
            public String getBiomeKey(int x, int y, int z) {
                String biome = view.getBiomeAt(x & 15, y, z & 15);
                if (biome != null && !biome.isBlank()) {
                    return biome;
                }
                java.util.Set<String> present = view.getBiomesPresent();
                if (present != null) {
                    for (String candidate : present) {
                        return candidate;
                    }
                }
                return "minecraft:plains";
            }
        };
    }

    private int findSavedTreeGroundY(
            String worldName,
            io.github.dailystruggle.anvil.AnvilChunkView view,
            int localX,
            int localZ) {
        int maxSecY = Integer.MIN_VALUE;
        int minSecY = Integer.MAX_VALUE;
        for (io.github.dailystruggle.anvil.PaletteSection sec : view.sections()) {
            if (sec.sectionY() > maxSecY) maxSecY = sec.sectionY();
            if (sec.sectionY() < minSecY) minSecY = sec.sectionY();
        }
        if (maxSecY != Integer.MIN_VALUE) {
            int topY = maxSecY * 16 + 15;
            int minY = Math.min(platform.getMinHeight(worldName), minSecY * 16);
            for (int y = topY; y > minY; y--) {
                int secY = Math.floorDiv(y, 16);
                if (!view.hasSection(secY)) {
                    y = secY * 16;
                    continue;
                }
                String state = view.blockIdAt(localX, y, localZ);
                if (state != null
                        && !isSavedStateReplaceable(state)
                        && !isTreeCanopyOrTrunkState(state)) {
                    return y;
                }
            }
        }
        return view.getHeightmapTopY(localX, localZ);
    }

    private io.github.dailystruggle.anvil.AnvilChunkView readSavedChunkView(
            String worldName, int chunkX, int chunkZ) {
        if (worldName == null) {
            return null;
        }
        java.nio.file.Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder == null) {
            return null;
        }
        io.github.dailystruggle.anvil.RegionFileResolver.ResolvedRegion resolved =
                io.github.dailystruggle.anvil.RegionFileResolver.resolveExisting(
                        worldFolder, "", chunkX, chunkZ);
        if (resolved == null) {
            resolved = io.github.dailystruggle.anvil.RegionFileResolver.resolve(
                    worldFolder, "", chunkX, chunkZ);
        }
        if (resolved == null || resolved.path() == null || resolved.reader() == null) {
            return null;
        }
        int rx = chunkX & 31;
        int rz = chunkZ & 31;
        try {
            if (resolved.reader() == io.github.dailystruggle.anvil.LcaRegionReader.INSTANCE) {
                return io.github.dailystruggle.anvil.LcaRegionReader.readChunkViewFromFile(
                        resolved.path(), rx, rz);
            }
            if (resolved.reader() == io.github.dailystruggle.anvil.AnvilReader.INSTANCE) {
                io.github.dailystruggle.anvil.AnvilReader.ChunkEntry entry =
                        io.github.dailystruggle.anvil.AnvilSectorReader.readChunkEntry(
                                resolved.path(), chunkX, chunkZ);
                if (entry != null
                        && entry.root != null
                        && io.github.dailystruggle.anvil.RegionFileReader.isFullChunkRoot(entry.root)) {
                    return io.github.dailystruggle.anvil.AnvilReader.toView(entry.root);
                }
                return null;
            }
            try (io.github.dailystruggle.anvil.AnvilRegionByteCache.Lease lease =
                    io.github.dailystruggle.anvil.AnvilRegionByteCache.acquire(resolved.path())) {
                if (lease == null) {
                    return null;
                }
                io.github.dailystruggle.anvil.AnvilReader.ChunkEntry entry =
                        resolved.reader().readChunk(
                                lease.buffer(), lease.length(), rx, rz);
                if (entry != null
                        && entry.root != null
                        && io.github.dailystruggle.anvil.RegionFileReader.isFullChunkRoot(entry.root)) {
                    return io.github.dailystruggle.anvil.AnvilReader.toView(entry.root);
                }
                return null;
            }
        } catch (java.io.IOException | RuntimeException ex) {
            platform.logger().warning("Failed to read saved chunk (" + chunkX + "," + chunkZ
                    + ") in " + worldName + ": " + ex.getMessage());
            return null;
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

    private static boolean isTreeCanopyOrTrunkState(String canonicalState) {
        if (canonicalState == null || canonicalState.isBlank()) {
            return false;
        }
        if (isSavedStateReplaceable(canonicalState)) {
            return true;
        }
        int bracket = canonicalState.indexOf('[');
        String base = (bracket >= 0 ? canonicalState.substring(0, bracket) : canonicalState)
                .toLowerCase(Locale.ROOT);
        if (!base.contains(":")) {
            base = "minecraft:" + base;
        }
        return base.endsWith("_log")
                || base.endsWith("_wood")
                || base.endsWith("_stem")
                || base.endsWith("_hyphae")
                || base.endsWith("_mushroom_block")
                || base.equals("minecraft:shroomlight")
                || base.equals("minecraft:mangrove_roots")
                || base.equals("minecraft:bamboo")
                || base.equals("minecraft:glow_lichen")
                || base.equals("minecraft:cobweb")
                || base.equals("minecraft:pink_petals")
                || base.equals("minecraft:hanging_roots")
                || base.equals("minecraft:spore_blossom")
                || base.equals("minecraft:bee_nest")
                || base.equals("minecraft:cocoa");
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
        return placeProceduralWithClearingByChunk(
                worldName, ox >> 4, oz >> 4, ox, oy, oz, species, rng, false);
    }

    private boolean placeProceduralWithClearingByChunk(
            String worldName,
            int originChunkX,
            int originChunkZ,
            int ox,
            int oy,
            int oz,
            TreeSpecies species,
            Random rng,
            boolean clearVanillaColumn) {
        if (proceduralGenerator == null || species == null) return false;
        SegmentedTreeModel model = proceduralGenerator.getOrGenerateModel(species, rng);
        if (model == null) {
            platform.logger().warning("Placement failed: buildModel returned null for species " + species.id());
            return false;
        }
        Map<Long, Map<TreeModel.BlockPos, String>> treeChunks = model.partitionByChunk(ox, oy, oz);
        Map<Long, Map<TreeModel.BlockPos, String>> mergedByChunk = new HashMap<>();
        if (clearVanillaColumn) {
            populateClearingSlices(originChunkX, originChunkZ, ox, oy - 1, oz, treeChunks, mergedByChunk);
        }
        for (Map.Entry<Long, Map<TreeModel.BlockPos, String>> e : treeChunks.entrySet()) {
            mergedByChunk.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
                    .putAll(e.getValue());
        }
        List<Long> chunkKeys = new ArrayList<>(mergedByChunk.keySet());
        java.util.Collections.sort(chunkKeys);
        for (long key : chunkKeys) {
            int cx = TreeModel.chunkX(key);
            int cz = TreeModel.chunkZ(key);
            boolean wasLoaded = platform.isChunkLoaded(worldName, cx, cz);
            placeOrDeferChunkBlocks(worldName, cx, cz, mergedByChunk.get(key));
            if ((cx != originChunkX || cz != originChunkZ) && wasLoaded) {
                completedAdjacentEdges.add(
                        new AdjacentEdgeKey(worldName, cx, cz, originChunkX, originChunkZ));
            }
        }
        return true;
    }

    private static void populateClearingSlices(
            int originChunkX,
            int originChunkZ,
            int ox,
            int groundY,
            int oz,
            Map<Long, Map<TreeModel.BlockPos, String>> treeChunks,
            Map<Long, Map<TreeModel.BlockPos, String>> mergedByChunk) {
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int bx = ox + dx;
                int bz = oz + dz;
                if ((bx >> 4) != originChunkX || (bz >> 4) != originChunkZ) {
                    continue;
                }
                long key = TreeModel.chunkKey(originChunkX, originChunkZ);
                Map<TreeModel.BlockPos, String> treeSlice = treeChunks.get(key);
                for (int dy = 1; dy <= 40; dy++) {
                    int by = groundY + dy;
                    TreeModel.BlockPos pos = new TreeModel.BlockPos(bx, by, bz);
                    if (treeSlice == null || !treeSlice.containsKey(pos)) {
                        mergedByChunk.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                .put(pos, "minecraft:void_air");
                    }
                }
            }
        }
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
        Map<TreeModel.BlockPos, String> merged;
        synchronized (slices) {
            merged = mergeSlices(slices);
        }
        if (merged.isEmpty()) {
            return;
        }
        if (patchSavedChunkIfPresent(key.worldName(), key.chunkX(), key.chunkZ(), merged)) {
            pendingChunkSlices.remove(key, slices);
        }
    }

    private static Map<TreeModel.BlockPos, String> mergeSlices(
            List<Map<TreeModel.BlockPos, String>> slices) {
        Map<TreeModel.BlockPos, String> merged = new LinkedHashMap<>();
        for (Map<TreeModel.BlockPos, String> slice : slices) {
            if (slice != null) {
                mergeSliceInto(merged, slice);
            }
        }
        return merged;
    }

    private static void mergeSliceInto(
            Map<TreeModel.BlockPos, String> target, Map<TreeModel.BlockPos, String> incoming) {
        for (Map.Entry<TreeModel.BlockPos, String> e : incoming.entrySet()) {
            TreeModel.BlockPos pos = e.getKey();
            String nextState = e.getValue();
            if (nextState == null) continue;
            String existing = target.get(pos);
            if (existing != null
                    && !"minecraft:void_air".equals(existing)
                    && ("minecraft:void_air".equals(nextState)
                            || (GrowthPlanner.isWood(existing) && !GrowthPlanner.isWood(nextState)))) {
                continue;
            }
            target.put(pos, nextState);
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
            if (io.github.dailystruggle.anvil.RegionFileResolver.patchChunkBlocks(
                    worldFolder, "", chunkX, chunkZ, patches, TreeGenEventController::isSavedStateReplaceable)) {
                return true;
            }
            io.github.dailystruggle.anvil.RegionFileResolver.invalidateMemo();
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
        Map<TreeModel.BlockPos, String> merged = mergeSlices(slices);
        Map<TreeModel.BlockPos, String> replaceable =
                filterLoadedChunkReplaceable(worldName, chunkX, chunkZ, merged);
        if (!replaceable.isEmpty()) {
            platform.setBlocks(worldName, chunkX, chunkZ, replaceable);
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
            String current = platform.getBlockState(worldName, pos.x(), pos.y(), pos.z());
            if (state.equals(current)) {
                continue;
            }
            if ("minecraft:void_air".equals(state)) {
                if (current == null
                        || (!TreeBlockBatcher.isPlainAir(current)
                                && (isLoadedBlockReplaceable(worldName, pos.x(), pos.y(), pos.z())
                                        || isTreeCanopyOrTrunkState(current)))) {
                    replaceable.put(pos, state);
                }
            } else if (isLoadedBlockReplaceable(worldName, pos.x(), pos.y(), pos.z())) {
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
