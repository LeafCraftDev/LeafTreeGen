package net.leaf.treegen.common;

import io.github.dailystruggle.rtp.common.predict.DistributionSampler;
import io.github.dailystruggle.rtp.common.predict.PerlinNoise;
import io.github.dailystruggle.rtp.common.predict.PolarCurve;
import io.github.dailystruggle.rtp.common.predict.UnitCurve;
import java.util.*;
import java.util.function.IntBinaryOperator;

/**
 * Port of tools/tree-gen/ procedural logic to Java.
 */
public final class ProceduralGenerator {

    /**
     * Stateless 3D biome lookup used by {@link #sampleChunkHalo} and {@link #generateChunkHalo}
     * when evaluating candidate tree origins in a surrounding chunk halo.
     */
    @FunctionalInterface
    public interface BiomeLookup {
        String biomeAt(int x, int y, int z);
    }

    public static double perlin(double x, double y, double z) {
        return PerlinNoise.noise(x, y, z);
    }

    private final Platform platform;
    private final TreeRegistry registry;
    private final TreeCache cache;
    private Map<String, TreeSpecies> configSpecies = null;

    public ProceduralGenerator(Platform platform, TreeRegistry registry) {
        this.platform = platform;
        this.registry = registry;
        this.cache = new TreeCache(100, 10); // N=100 cached trees, remove one on M=10 reuses
    }

    public void setConfigSpecies(Map<String, TreeSpecies> configSpecies) {
        this.configSpecies = configSpecies;
    }

    private TreeSpecies speciesMapLookup(String id) {
        String key = id.toLowerCase(java.util.Locale.ROOT);
        if (configSpecies != null && configSpecies.containsKey(key)) {
            return configSpecies.get(key);
        }
        return registry.getSpeciesMap().get(key);
    }

    /**
     * Resolves a group child species, given the id of the enclosing group.
     * <p>
     * A placement group authored in config.yml (e.g. {@code placement.forest})
     * shadows the JSON species of the same name in {@link #configSpecies}. When
     * such a group lists a child with its own id (e.g. {@code trees: { forest: 100 }}),
     * the intent is to reference the underlying JSON species, not the config
     * group itself. Resolving it through {@link #speciesMapLookup} would return
     * the group again, producing an infinite "cyclic species reference" loop.
     * For self-references we therefore resolve directly against the registry's
     * JSON species, falling back to the normal lookup otherwise.
     */
    private TreeSpecies resolveChild(String childId, String groupId) {
        String childKey = childId.toLowerCase(java.util.Locale.ROOT);
        if (groupId != null && childKey.equals(groupId.toLowerCase(java.util.Locale.ROOT))) {
            TreeSpecies jsonSpecies = registry.getSpeciesMap().get(childKey);
            if (jsonSpecies != null) {
                return jsonSpecies;
            }
        }
        return speciesMapLookup(childId);
    }

    public boolean generate(String worldName, int x, int y, int z, TreeSpecies species, Random random) {
        SegmentedTreeModel model = getOrGenerateModel(species, random);
        if (model != null) {
            model.place(platform, worldName, x, y, z);
            return true;
        } else {
            if (species != null) {
                platform.logger().warning("Placement failed: buildModel returned null for species " + species.id());
            }
            return false;
        }
    }

    /**
     * Returns a cached or newly built {@link SegmentedTreeModel} for a deterministic variant of
     * {@code species} chosen using {@code random}, or {@code null} if {@code species} cannot be
     * procedurally generated.
     */
    public SegmentedTreeModel getOrGenerateModel(TreeSpecies species, Random random) {
        if (species == null || random == null) return null;
        int variantCount = effectiveVariantCount(species);
        int variant = random.nextInt(variantCount);
        long seed = variantSeed(species.id(), variant);
        return cache.getOrGenerate(species.id(), seed, s -> {
            TreeModel built = buildModel(species, new Random(s));
            if (built != null) {
                built.rotate(new Random(s).nextInt(4));
            }
            return built;
        });
    }

    /**
     * Generates and places only the slice of a tree anchored at {@code (x, y, z)} that falls within
     * chunk {@code (targetChunkX, targetChunkZ)}, allowing world generators to stamp partial trees
     * into individual chunks without touching neighbouring chunks.
     */
    public boolean generateChunkSlice(
            String worldName,
            int x,
            int y,
            int z,
            int targetChunkX,
            int targetChunkZ,
            TreeSpecies species,
            Random random) {
        SegmentedTreeModel model = getOrGenerateModel(species, random);
        if (model == null) {
            return false;
        }
        return model.placeChunkSlice(platform, worldName, x, y, z, targetChunkX, targetChunkZ);
    }

    /**
     * Statelessly evaluates all deterministic worldgen trees whose origins lie within the {@code
     * (2 * haloRadius + 1) x (2 * haloRadius + 1)} chunk neighbourhood around {@code
     * (targetChunkX, targetChunkZ)} and returns the merged, placement-ordered block slice clipped
     * strictly to {@code (targetChunkX, targetChunkZ)}.
     *
     * <p>Because origin coordinates, variant seeds, and cached {@link SegmentedTreeModel} instances
     * depend only on {@code (srcChunkX, srcChunkZ, worldSeed)}, a world generator can invoke this
     * per chunk in any order (or in parallel) and each chunk will receive its exact overhang or
     * trunk portion without touching neighbouring chunks.
     */
    public Map<TreeModel.BlockPos, String> sampleChunkHalo(
            int targetChunkX,
            int targetChunkZ,
            long worldSeed,
            Collection<TreeSpecies> speciesPool,
            int haloRadius,
            IntBinaryOperator groundYAtXZ,
            BiomeLookup biomeAtXYZ) {
        if (speciesPool == null || speciesPool.isEmpty() || groundYAtXZ == null || biomeAtXYZ == null) {
            return Collections.emptyMap();
        }
        int r = Math.max(0, haloRadius);
        Map<TreeModel.BlockPos, String> merged = new LinkedHashMap<>();

        for (TreeSpecies species : speciesPool) {
            if (species == null || !species.worldgen() || species.isFeatureTreesOnly()) {
                continue;
            }
            int spacing = Math.max(1, species.spacing());
            int salt = species.effectiveSalt();

            for (int dcx = -r; dcx <= r; dcx++) {
                int srcCX = targetChunkX + dcx;
                for (int dcz = -r; dcz <= r; dcz++) {
                    int srcCZ = targetChunkZ + dcz;
                    int[] targetChunk = DistributionSampler.gridCellSample(
                            srcCX, srcCZ, spacing, worldSeed, salt);
                    if (srcCX != targetChunk[0] || srcCZ != targetChunk[1]) {
                        continue;
                    }
                    Random gridRandom = new Random(
                            DistributionSampler.variantSeed(
                                    species.id(),
                                    (((long) srcCX) << 32) ^ (srcCZ & 0xFFFFFFFFL) ^ worldSeed));

                    int centerX = (srcCX << 4) + 8;
                    int centerZ = (srcCZ << 4) + 8;
                    int centerY = groundYAtXZ.applyAsInt(centerX, centerZ);
                    String centerBiome = biomeAtXYZ.biomeAt(
                            centerX, centerY == Integer.MIN_VALUE ? 64 : centerY, centerZ);
                    if (!species.allowsWorldgenBiome(centerBiome)) {
                        continue;
                    }

                    int ox = (srcCX << 4) + gridRandom.nextInt(16);
                    int oz = (srcCZ << 4) + gridRandom.nextInt(16);
                    int groundY = groundYAtXZ.applyAsInt(ox, oz);
                    if (groundY == Integer.MIN_VALUE) {
                        continue;
                    }

                    String exactBiome = biomeAtXYZ.biomeAt(ox, groundY, oz);
                    if (!species.allowsWorldgenBiome(exactBiome)) {
                        continue;
                    }

                    Random treeRandom = new Random(gridRandom.nextLong());
                    SegmentedTreeModel model = getOrGenerateModel(species, treeRandom);
                    if (model == null || !model.intersectsChunk(ox, oz, targetChunkX, targetChunkZ)) {
                        continue;
                    }

                    Map<TreeModel.BlockPos, String> slice =
                            model.sliceForChunk(ox, groundY + 1, oz, targetChunkX, targetChunkZ);
                    for (Map.Entry<TreeModel.BlockPos, String> entry : slice.entrySet()) {
                        merged.merge(
                                entry.getKey(),
                                entry.getValue(),
                                (existing, incoming) -> isWood(existing) && !isWood(incoming)
                                        ? existing
                                        : incoming);
                    }
                }
            }
        }
        return merged;
    }

    /**
     * Statelessly samples and places all tree slices intersecting chunk {@code (targetChunkX,
     * targetChunkZ)} from candidate origins within {@code haloRadius} chunks.
     */
    public boolean generateChunkHalo(
            String worldName,
            int targetChunkX,
            int targetChunkZ,
            long worldSeed,
            Collection<TreeSpecies> speciesPool,
            int haloRadius,
            IntBinaryOperator groundYAtXZ,
            BiomeLookup biomeAtXYZ) {
        Map<TreeModel.BlockPos, String> slice = sampleChunkHalo(
                targetChunkX, targetChunkZ, worldSeed, speciesPool, haloRadius, groundYAtXZ, biomeAtXYZ);
        if (slice.isEmpty()) {
            return true;
        }
        return platform.setBlocks(worldName, targetChunkX, targetChunkZ, slice);
    }

    /**
     * Returns {@code true} if {@link #buildModel(TreeSpecies, Random)} can produce a
     * procedural model for {@code species} (either directly, via {@code treeDefinitions},
     * or via a referenced child species in {@code trees}).
     */
    public boolean canGenerate(TreeSpecies species) {
        return species != null && selectModelId(species, new Random(0)) != null;
    }

    /**
     * Number of distinct pre-computed variants kept in memory for a species at
     * runtime. Derived from the configured {@code count} (the same value the
     * datapack path bakes into N structure templates): a single procedural model
     * contributes its own {@code count}, a multi-definition container sums the
     * {@code count} of every definition, and a group contributes one slot per
     * referenced child. Always at least one.
     */
    private int effectiveVariantCount(TreeSpecies species) {
        int count = 0;
        if (species.procedural() != null) {
            count = species.procedural().count();
        } else if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
            for (TreeSpecies.ProceduralParams p : species.treeDefinitions().values()) {
                count += Math.max(1, p.count());
            }
        } else if (species.trees() != null && !species.trees().isEmpty()) {
            count = species.trees().size();
        }
        return Math.max(1, count);
    }

    /** Deterministic seed for the given species variant, so the TreeCache can reuse models. */
    private static long variantSeed(String speciesId, int variant) {
        return DistributionSampler.variantSeed(speciesId, variant);
    }

    public TreeModel buildModel(TreeSpecies species, Random random) {
        return buildModel(species, random, new HashSet<>());
    }

    /**
     * Resolves which model id {@link #buildModel(TreeSpecies, Random)} would select for the
     * given species and random source, WITHOUT building the (potentially very expensive)
     * procedural geometry. The selection logic mirrors {@link #buildModel} exactly, so this
     * is safe to use in tests that only need to verify variant/definition selection coverage.
     */
    public String selectModelId(TreeSpecies species, Random random) {
        return selectModelId(species, random, new HashSet<>());
    }

    private String selectModelId(TreeSpecies species, Random random, Set<String> visited) {
        if (species.trees() != null && !species.trees().isEmpty()) {
            if (!visited.add(species.id().toLowerCase(java.util.Locale.ROOT))) {
                TreeSpecies jsonSpecies = registry.getSpeciesMap().get(species.id().toLowerCase(java.util.Locale.ROOT));
                if (jsonSpecies != null && jsonSpecies != species
                        && (jsonSpecies.trees() == null || jsonSpecies.trees().isEmpty())) {
                    return selectModelId(jsonSpecies, random, visited);
                }
                return null;
            }
            int[] weights = species.trees().stream().mapToInt(TreeSpecies.WeightedSpecies::weight).toArray();
            int chosen = DistributionSampler.sampleWeightedIndex(weights, random::nextInt);
            if (chosen >= 0) {
                TreeSpecies.WeightedSpecies ws = species.trees().get(chosen);
                String[] parts = ws.id().split(":", 2);
                TreeSpecies child = resolveChild(parts[0], species.id());
                if (child != null) {
                    if (parts.length > 1) {
                        TreeSpecies.ProceduralParams named = child.treeDefinitions() != null ? child.treeDefinitions().get(parts[1]) : null;
                        if (named != null) {
                            return child.id() + ":" + parts[1];
                        }
                    }
                    return selectModelId(child, random, visited);
                }
            }
            return null;
        }

        TreeSpecies.ProceduralParams params = species.procedural();
        if (params == null) {
            List<String> variants = species.variantLocations();
            if (!variants.isEmpty()) {
                return null;
            } else if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
                Map<String, TreeSpecies.ProceduralParams> defs = species.treeDefinitions();
                List<String> names = new ArrayList<>(defs.keySet());
                int[] weights = new int[names.size()];
                for (int i = 0; i < names.size(); i++) {
                    weights[i] = Math.max(1, defs.get(names.get(i)).weight());
                }
                int idx = DistributionSampler.sampleWeightedIndex(weights, random::nextInt);
                String selected = names.get(Math.max(0, idx));
                return species.id() + ":" + selected;
            }
            return null;
        }

        return species.id();
    }

    private TreeModel buildModel(TreeSpecies species, Random random, Set<String> visited) {
        if (species.trees() != null && !species.trees().isEmpty()) {
            if (!visited.add(species.id().toLowerCase(java.util.Locale.ROOT))) {
                TreeSpecies jsonSpecies = registry.getSpeciesMap().get(species.id().toLowerCase(java.util.Locale.ROOT));
                if (jsonSpecies != null && jsonSpecies != species
                        && (jsonSpecies.trees() == null || jsonSpecies.trees().isEmpty())) {
                    return buildModel(jsonSpecies, random, visited);
                }
                platform.logger().warning("Group selection aborted: cyclic species reference detected at '" + species.id() + "' (visited: " + visited + ")");
                return null;
            }
            int[] weights = species.trees().stream().mapToInt(TreeSpecies.WeightedSpecies::weight).toArray();
            int chosen = DistributionSampler.sampleWeightedIndex(weights, random::nextInt);
            if (chosen >= 0) {
                TreeSpecies.WeightedSpecies ws = species.trees().get(chosen);
                String[] parts = ws.id().split(":", 2);
                TreeSpecies child = resolveChild(parts[0], species.id());
                if (child != null) {
                    if (parts.length > 1) {
                        TreeSpecies.ProceduralParams named = child.treeDefinitions() != null ? child.treeDefinitions().get(parts[1]) : null;
                        if (named != null) {
                            return buildProceduralModel(child.id() + ":" + parts[1], named, random);
                        } else {
                            platform.logger().warning("Group selection failed: definition '" + parts[1] + "' not found in child species '" + child.id() + "'");
                        }
                    }
                    return buildModel(child, random, visited);
                } else {
                    platform.logger().warning("Group selection failed: child species '" + parts[0] + "' not found");
                }
            }
            return null;
        }

        TreeSpecies.ProceduralParams params = species.procedural();
        if (params == null) {
            List<String> variants = species.variantLocations();
            if (!variants.isEmpty()) {
                return null;
            } else if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
                Map<String, TreeSpecies.ProceduralParams> defs = species.treeDefinitions();
                List<String> names = new ArrayList<>(defs.keySet());
                int[] weights = new int[names.size()];
                for (int i = 0; i < names.size(); i++) {
                    weights[i] = Math.max(1, defs.get(names.get(i)).weight());
                }
                int idx = DistributionSampler.sampleWeightedIndex(weights, random::nextInt);
                String selected = names.get(Math.max(0, idx));
                TreeSpecies.ProceduralParams named = defs.get(selected);
                return buildProceduralModel(species.id() + ":" + selected, named, random);
            }
            return null;
        }

        return buildProceduralModel(species.id(), params, random);
    }

    public TreeModel buildProceduralModel(String speciesId, TreeSpecies.ProceduralParams params, Random random) {
        if (params == null) {
            platform.logger().warning("buildProceduralModel called with null params for " + speciesId);
            return null;
        }
        int minH = Math.max(1, params.minHeight());
        int maxH = Math.max(minH, params.maxHeight());
        int height = minH + (maxH > minH ? random.nextInt(maxH - minH + 1) : 0);
        List<double[]> offsets = new ArrayList<>();

        // 1. Generate Trunk
        double prevCx = 0, prevCz = 0;

        Map<TreeModel.BlockPos, String> trunkBlocks = new HashMap<>();
        List<double[]> branchEndpoints = new ArrayList<>();

        // Per-object irregular phases
        double[] phases = {
                random.nextDouble() * Math.PI * 2,
                random.nextDouble() * Math.PI * 2,
                random.nextDouble() * Math.PI * 2
        };

        for (int i = 0; i < height; i++) {
            double[] offset = calculateLeanOffset(i, height, params.leanAzimuth(), params.leanAngle(), params.curveFn(), params.curveParams(), params.azimuthFn(), params.azimuthParams());
            offsets.add(offset);

            double cx = offset[0];
            double cz = offset[1];
            double w = calculateWidth(i, height, params.trunkWidth(), params.trunkShape(), params.trunkShapeParams());

            String block = params.trunkBlock();
            if (params.secondaryTrunk() != null) {
                double t = (double) i / Math.max(height - 1, 1);
                if (t >= params.secondaryTrunkStart() && t <= params.secondaryTrunkEnd()) {
                    block = params.secondaryTrunk();
                }
            }

            // Add axis if orientable
            String axis = calculateAxis(cx - prevCx, 1.0, cz - prevCz);
            String orientedBlock = applyAxis(block, axis);

            placeTrunkSlice(trunkBlocks, 0, i, 0, cx, cz, w, orientedBlock, params.roundTrunk(), random, phases, false);

            // Fill gaps. Mirrors trunk.py: intermediate slices are placed at the
            // rounded fractional height between the two layers (so a leaning/spiral
            // trunk bridges diagonally instead of smearing flat) and they only fill
            // empty cells, never overwriting already-placed trunk blocks.
            if (i > 0) {
                double dx = cx - prevCx;
                double dz = cz - prevCz;
                double dist = Math.hypot(dx, dz);
                if (dist > 1.0) {
                    int steps = (int) Math.ceil(dist);
                    for (int s = 1; s < steps; s++) {
                        double f = (double) s / steps;
                        double icx = UnitCurve.linear(f, prevCx, cx);
                        double icz = UnitCurve.linear(f, prevCz, cz);
                        int fillY = (int) Math.round(UnitCurve.linear(f, i - 1.0, i));
                        placeTrunkSlice(trunkBlocks, 0, fillY, 0, icx, icz, w, orientedBlock, params.roundTrunk(), random, phases, true);
                    }
                }
            }
            prevCx = cx;
            prevCz = cz;
        }

        // Apply wood variants for exposed trunk ends
        applyTrunkWoodVariants(trunkBlocks, params.trunkBlock(), params.secondaryTrunk());

            // 2. Generate Canopy
        Map<TreeModel.BlockPos, String> canopyBlocks = new HashMap<>();
        TreeSpecies.CanopyParams canopy = params.canopy();
        if (canopy != null) {
            String profile = params.profile() != null ? params.profile().toUpperCase() : "OAK";
            Random canopyRandom = new Random(random.nextLong());

            List<TreeSpecies.CanopyParams.Layer> layers = canopy.layers();
            if (layers == null || layers.isEmpty()) {
                // Mirror to_procedural.py / canopy.py: a tree is "branch-driven" whenever
                // a branches block is present (independent of any explicit count). Branch
                // driven trees anchor the volume crown at the trunk tip (crown_extra=0) so
                // the canopy stays attached; using count>0 here left configs that omit
                // "count" (e.g. the dark-oak titans) with a crown floating height/4 blocks
                // above the trunk.
                layers = generatePresetLayers(profile, height, canopy.branches() != null);
            }

            // Volume layers
            if (canopy.volumeLayers()) {
                double squish = canopy.squish();
                double startAngle = canopy.startAngle();

                List<TreeSpecies.CanopyParams.Layer> volumeLayers = layers;
                if (canopy.branches() != null && layers != null && !layers.isEmpty()) {
                    Double crownFrac = canopy.crownVolumeFraction();
                    if (crownFrac != null) {
                        int n = Math.max(1, (int) Math.round(layers.size() * crownFrac));
                        volumeLayers = layers.subList(layers.size() - n, layers.size());
                    } else {
                        volumeLayers = List.of(layers.get(layers.size() - 1));
                    }
                }

                if (volumeLayers != null) {
                    for (TreeSpecies.CanopyParams.Layer layer : volumeLayers) {
                        int cy = layer.yOffset();
                        double radius = layer.radius();

                        int halfH = (startAngle >= 180.0) ? 0 : (int) Math.ceil(radius * squish);
                        int downH = (startAngle < 90.0) ? (int) Math.ceil(radius * Math.cos(Math.toRadians(startAngle)) * squish) : 0;

                        for (int y = cy - downH; y <= cy + halfH; y++) {
                            int dy = y - cy;
                            double semiAxis = dy >= 0 ? halfH : downH;
                            double layerR = (semiAxis > 0) ? PolarCurve.spheroidSliceRadius(radius, dy, semiAxis) : radius;

                            if (layerR < 0.5) continue;

                            // Use trunk offset at THIS height
                            double[] layerOffset = offsets.isEmpty()
                                    ? new double[]{0.0, 0.0}
                                    : (y >= 0 && y < offsets.size()) ? offsets.get(y) : offsets.get(offsets.size() - 1);
                            placeLeafDisc(canopyBlocks, layerOffset[0], y, layerOffset[1], layerR, params.leafBlock(), canopy.mode(), canopy.density(), canopyRandom, canopy.secondaryLeafList(), canopy.secondaryFraction(), trunkBlocks, phases, canopy.leafVerticalScale());
                        }
                    }
                }
            }

            // Branches
            if (canopy.branches() != null) {
                generateBranches(canopyBlocks, height, offsets, params.trunkBlock(), params.leafBlock(), canopy, canopyRandom, trunkBlocks, branchEndpoints, layers);
            }

            // Cap trunk tip
            if (params.capTrunk()) {
                capTrunkTip(canopyBlocks, trunkBlocks, params.leafBlock(), canopy.mode(), canopy.density(), canopyRandom, canopy.secondaryLeafList(), canopy.secondaryFraction(), phases, canopy.leafVerticalScale());
            }

            // Underside glow: replace the lowest leaf block in each (x,z) column of the
            // canopy with the configured underside block (e.g. shroomlight), producing a
            // single glow layer across the bottom of the cap rather than scattered blocks.
            if (canopy.undersideLeaves() != null) {
                applyUndersideLeaves(canopyBlocks, params.leafBlock(), canopy.secondaryLeafList(),
                        canopy.undersideLeaves(), canopy.undersideChance(), canopyRandom);
            }
        }

        Map<TreeModel.BlockPos, String> finalBlocks = new HashMap<>(trunkBlocks);
        canopyBlocks.forEach((pos, b) -> {
            if (!finalBlocks.containsKey(pos)) {
                finalBlocks.put(pos, b);
            }
        });

        connectLeavesWithBranches(finalBlocks, params.trunkBlock());

        // Hide the decay-prevention branches inside the canopy. The connector has to
        // run wood out close to the leaf shell to keep wide canopies (big cherry,
        // ancient oak) within vanilla's 6-block decay radius, but on thin canopies
        // that wood inevitably surfaces, leaving logs scattered across the outer
        // leaves. Skinning every air-exposed face of the freshly placed branch wood
        // with a leaf buries those logs again; because each skin leaf sits directly
        // against wood it is at decay distance 1 and never decays itself.
        if (!lastConnectionWood.isEmpty()) {
            String wrapLeaves = params.leafBlock();
            List<TreeSpecies.CanopyParams.WeightedBlock> wrapSecondary = canopy != null ? canopy.secondaryLeafList() : List.of();
            double wrapFraction = canopy != null ? canopy.secondaryFraction() : 0.0;
            buryExposedConnectionWood(finalBlocks, wrapLeaves, wrapSecondary, wrapFraction,
                    new Random(random.nextLong()));
        }

        // Re-cap exposed log tops AFTER connecting leaves with branches. The branch
        // connector rasterises wood out to orphan leaves and can convert the leaf
        // that previously capped the trunk apex (or push a new wood tip above it)
        // into wood, reintroducing the one-block air gap above the highest log.
        // Capping again on the merged geometry guarantees no exposed log top is
        // left bare regardless of what the connector did.
        if (params.capTrunk() && canopy != null) {
            capExposedLogTops(finalBlocks, params.leafBlock(), new Random(random.nextLong()),
                    canopy.secondaryLeafList(), canopy.secondaryFraction());
        }

        // Accent decorators (ornaments, vines, snow, base scatter, etc.). Mirrors the
        // offline Python decorators.py, which decorates LAST. This must run after the
        // leaf/branch connector, the exposed-wood burying and the log-top re-capping:
        // those passes re-skin branch tips and apexes with leaves/wood and would
        // otherwise erase any ornament placed earlier. canopy_bottom is intentionally
        // handled by the undersideLeaves path above and excluded at parse time.
        if (params.decorators() != null && !params.decorators().isEmpty()) {
            applyDecorators(params.decorators(), trunkBlocks, finalBlocks, branchEndpoints, new Random(random.nextLong()));
        }

        // Bake each leaf's final decay distance into its block-state. The decay
        // "distance" of a placed leaf is fixed the moment it is written (the engine
        // does not retroactively lower it when a closer log/leaf is added later, and
        // some placement paths never recompute it at all), so leaves left at the
        // default distance 7 decay on the next random tick even though the finished
        // geometry keeps them in range. Computing the real distance here and writing
        // it (with persistent=false) makes the placed state already correct for both
        // the runtime and the datapack-baked NBT path, while still letting the leaves
        // decay normally once the player removes the supporting wood.
        bakeLeafDecayDistances(finalBlocks);

        return new TreeModel(speciesId, finalBlocks, false);
    }

    /**
     * Rewrites every leaf block-state in the finished model to carry its true
     * vanilla decay distance (a multi-source BFS from the wood, capped at
     * {@value #MAX_LEAF_DISTANCE}+1) plus {@code persistent=false}. Existing
     * properties on a leaf state are preserved; {@code distance}/{@code persistent}
     * are overwritten. Non-leaf blocks (logs, decorators, underside glow) are left
     * untouched.
     */
    private void bakeLeafDecayDistances(Map<TreeModel.BlockPos, String> blocks) {
        Set<TreeModel.BlockPos> wood = new HashSet<>();
        List<TreeModel.BlockPos> leaves = new ArrayList<>();
        Set<TreeModel.BlockPos> leafSet = new HashSet<>();
        for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
            String v = e.getValue();
            if (isWood(v)) {
                wood.add(e.getKey());
            } else if (isLeaf(v)) {
                leaves.add(e.getKey());
                leafSet.add(e.getKey());
            }
        }
        if (leaves.isEmpty()) return;

        Map<TreeModel.BlockPos, Integer> dist =
                TreeModel.computeLeafDistancesFromWoodSet(leaves, leafSet, wood, MAX_LEAF_DISTANCE);

        for (TreeModel.BlockPos leaf : leaves) {
            int d = dist.getOrDefault(leaf, MAX_LEAF_DISTANCE + 1);
            blocks.put(leaf, withLeafDecayState(blocks.get(leaf), d));
        }
    }

    /**
     * Returns {@code state} with its {@code distance} set to {@code distance} and
     * {@code persistent=false}, preserving any other existing block-state
     * properties (e.g. {@code waterlogged}).
     */
    private static String withLeafDecayState(String state, int distance) {
        String base = state;
        Map<String, String> props = new LinkedHashMap<>();
        int lb = state.indexOf('[');
        if (lb >= 0) {
            base = state.substring(0, lb);
            int rb = state.indexOf(']', lb);
            String inner = state.substring(lb + 1, rb < 0 ? state.length() : rb);
            for (String pair : inner.split(",")) {
                String p = pair.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq > 0) props.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            }
        }
        props.put("distance", Integer.toString(distance));
        props.put("persistent", "false");
        StringBuilder sb = new StringBuilder(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    /** Vanilla destroys any leaf whose distance to the nearest log exceeds this. */
    private static final int MAX_LEAF_DISTANCE = 6;

    /** The six orthogonal neighbour offsets vanilla leaf-decay propagates through. */
    private static final int[][] ORTHOGONAL = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * Positions of the wood the leaf-decay connector placed during the current
     * build. {@link #buryExposedConnectionWood} skins their exposed faces with
     * leaves so the branches stay hidden inside the canopy.
     */
    final Set<TreeModel.BlockPos> lastConnectionWood = new HashSet<>();

    /**
     * Ensures every leaf stays within Minecraft's leaf-decay radius by extending
     * wood "branches" out to any leaf that would otherwise be too far from a log.
     *
     * <p>Vanilla recomputes each leaf's distance to the nearest log (propagating
     * through up to six orthogonal leaf-to-leaf steps) and destroys leaves whose
     * distance exceeds {@value #MAX_LEAF_DISTANCE}. That prunes the wide canopies
     * our procedural and datapack-baked trees produce. Rather than tracking grown
     * trees in a side store (which datapack-placed trees never populate), we make
     * the geometry self-sufficient: a thin branch of wood is rasterised out to
     * each orphaned leaf so the standing tree keeps its full canopy, yet the
     * leaves decay normally once the player removes the wood.
     */
    private void connectLeavesWithBranches(Map<TreeModel.BlockPos, String> blocks, String trunkBlock) {
        lastConnectionWood.clear();
        String branchWood = (trunkBlock == null || trunkBlock.isBlank())
                ? "minecraft:oak_log"
                : trunkBlock.split("\\[")[0];

        // A single pass connects every orphaned leaf. The outer guard only needs to
        // re-run if rasterising those branches somehow leaves a new orphan, which it
        // never should (each orphan ends up orthogonally adjacent to wood), so this
        // normally returns after the first pass.
        for (int guard = 0; guard < 16; guard++) {
            Set<TreeModel.BlockPos> wood = new HashSet<>();
            List<TreeModel.BlockPos> leaves = new ArrayList<>();
            for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
                String v = e.getValue();
                if (isWood(v)) {
                    wood.add(e.getKey());
                } else if (isLeaf(v)) {
                    leaves.add(e.getKey());
                }
            }
            if (wood.isEmpty() || leaves.isEmpty()) return;

            // Spatial hash of every wood block so nearest-wood queries are near O(1)
            // instead of scanning the entire wood set. On wide canopies the linear
            // scan (O(orphans * wood)) was the dominant remaining cost.
            WoodGrid grid = new WoodGrid(8);
            for (TreeModel.BlockPos w : wood) grid.add(w);

            Set<TreeModel.BlockPos> leafSet = new HashSet<>(leaves);

            Map<TreeModel.BlockPos, Integer> dist =
                    TreeModel.computeLeafDistancesFromWoodSet(leaves, leafSet, wood, MAX_LEAF_DISTANCE);

            // Collect every orphan (leaf still out of decay range) and connect the
            // farthest ones first so each branch can also rescue the intermediate
            // leaves it threads through. Earlier versions connected a single orphan
            // per pass and rescanned the whole model each time, which made wide
            // canopies (e.g. titan mega oaks) quadratic-per-orphan and effectively
            // stalled. Connecting all orphans in one pass against an incrementally
            // grown wood set keeps it to a single scan.
            List<TreeModel.BlockPos> orphans = new ArrayList<>();
            Map<TreeModel.BlockPos, Long> orphanDistSq = new HashMap<>();
            for (TreeModel.BlockPos leaf : leaves) {
                if (!dist.containsKey(leaf)) {
                    orphans.add(leaf);
                    // Cache each orphan's distance to its nearest wood once. Computing it
                    // inside the sort comparator instead would rescan the whole wood set
                    // on every comparison (O(orphans*log(orphans)*wood)), which dominated
                    // the runtime for wide canopies.
                    orphanDistSq.put(leaf, squaredDistance(leaf, grid.nearest(leaf)));
                }
            }
            if (orphans.isEmpty()) return; // every leaf is within range

            orphans.sort((a, b) -> Long.compare(orphanDistSq.get(b), orphanDistSq.get(a)));

            // Greedy set cover: a single buried branch keeps every leaf within its
            // 6-block decay neighbourhood alive, so one branch typically rescues
            // hundreds of orphans, not just the one it points at. Connecting EVERY
            // orphan (the old behaviour) buried a separate log beside each one,
            // flooding wide canopies (cherry, ancient oak) with hundreds of logs
            // that surfaced on the leaf shell. Instead we branch to the farthest
            // remaining orphan, then mark every orphan now within decay range of
            // the freshly placed wood as covered and skip it.
            Set<TreeModel.BlockPos> remaining = new HashSet<>(orphans);
            for (TreeModel.BlockPos orphan : orphans) {
                if (!remaining.contains(orphan)) continue; // already covered by an earlier branch
                // Prefer routing from wood that is close to the trunk axis (x=0,z=0)
                // rather than purely the geometrically nearest wood block. A lateral
                // branch tip far from the trunk is geometrically close but produces a
                // long horizontal connector that surfaces on the canopy exterior.
                // Score each candidate as: distToOrphan + trunkDist * 0.5, where
                // trunkDist is the horizontal distance of the wood block from x=0,z=0.
                // We only search within a 2x radius of the nearest distance to keep it fast.
                TreeModel.BlockPos geometricNearest = grid.nearest(orphan);
                if (geometricNearest == null) continue;
                long nearestSq = squaredDistance(geometricNearest, orphan);
                long searchSq = nearestSq * 4 + 1; // 2x radius
                TreeModel.BlockPos best = geometricNearest;
                double bestScore = Double.MAX_VALUE;
                for (TreeModel.BlockPos w : wood) {
                    long dSq = squaredDistance(w, orphan);
                    if (dSq > searchSq) continue;
                    double trunkDist = Math.sqrt((double) w.x() * w.x() + (double) w.z() * w.z());
                    double score = Math.sqrt((double) dSq) + trunkDist * 0.5;
                    if (score < bestScore) { bestScore = score; best = w; }
                }
                TreeModel.BlockPos nearest = best;
                List<TreeModel.BlockPos> placed =
                        rasterizeWoodLine(blocks, nearest, orphan, branchWood, wood, grid);
                // The path leaves we converted to wood are no longer leaves.
                placed.forEach(leafSet::remove);
                lastConnectionWood.addAll(placed);
                markCovered(placed, leafSet, remaining);
            }
        }
    }

    /**
     * Multi-source BFS from freshly placed branch wood through the surrounding
     * leaves, marking every orphan reachable within {@value #MAX_LEAF_DISTANCE}
     * orthogonal leaf steps as covered (removing it from {@code remaining}). This
     * lets one buried branch satisfy the decay radius for a whole neighbourhood of
     * orphan leaves instead of growing a separate exposed log beside each one.
     */
    private void markCovered(List<TreeModel.BlockPos> placedWood,
                             Set<TreeModel.BlockPos> leafSet, Set<TreeModel.BlockPos> remaining) {
        Map<TreeModel.BlockPos, Integer> dist =
                TreeModel.computeLeafDistancesFromWoodSources(placedWood, leafSet, MAX_LEAF_DISTANCE);
        remaining.removeAll(dist.keySet());
    }

    /**
     * Skins every air-exposed face of the wood the decay connector just placed with
     * a leaf, hiding those branch logs inside the canopy. The added leaves border
     * wood directly, so they sit at decay distance 1 and never decay themselves;
     * they simply fill the holes where the connection branches would otherwise have
     * poked through the outer leaf shell.
     */
    private void buryExposedConnectionWood(Map<TreeModel.BlockPos, String> blocks, String leafBlock,
                                           List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, double secondaryFraction, Random random) {
        for (TreeModel.BlockPos w : lastConnectionWood) {
            for (int[] o : ORTHOGONAL) {
                TreeModel.BlockPos n = new TreeModel.BlockPos(w.x() + o[0], w.y() + o[1], w.z() + o[2]);
                if (!blocks.containsKey(n)) {
                    blocks.put(n, resolveLeaf(leafBlock, secondaryLeaves, secondaryFraction, random));
                }
            }
        }
    }

    private static boolean isWood(String v) {
        return v.contains("log") || v.contains("wood") || v.contains("stem") || v.contains("hyphae");
    }

    private static boolean isLeaf(String v) {
        return v.contains("leaves");
    }

    /**
     * Uniform spatial hash over wood block positions, supporting fast nearest-wood
     * queries. Buckets are cubes of {@code cell} blocks; a nearest query expands
     * Chebyshev shells outward from the query cell and stops once no closer wood
     * can possibly exist in an unexplored shell.
     */
    private static final class WoodGrid {
        private final int cell;
        private final Map<Long, List<TreeModel.BlockPos>> buckets = new HashMap<>();
        private int minCx = Integer.MAX_VALUE, minCy = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE;
        private int maxCx = Integer.MIN_VALUE, maxCy = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;

        WoodGrid(int cell) {
            this.cell = cell;
        }

        private static long key(int cx, int cy, int cz) {
            return (cx & 0x1FFFFFL) | ((cy & 0x1FFFFFL) << 21) | ((cz & 0x1FFFFFL) << 42);
        }

        void add(TreeModel.BlockPos p) {
            int cx = Math.floorDiv(p.x(), cell);
            int cy = Math.floorDiv(p.y(), cell);
            int cz = Math.floorDiv(p.z(), cell);
            buckets.computeIfAbsent(key(cx, cy, cz), k -> new ArrayList<>()).add(p);
            if (cx < minCx) minCx = cx;
            if (cy < minCy) minCy = cy;
            if (cz < minCz) minCz = cz;
            if (cx > maxCx) maxCx = cx;
            if (cy > maxCy) maxCy = cy;
            if (cz > maxCz) maxCz = cz;
        }

        TreeModel.BlockPos nearest(TreeModel.BlockPos q) {
            if (buckets.isEmpty()) return null;
            int qcx = Math.floorDiv(q.x(), cell);
            int qcy = Math.floorDiv(q.y(), cell);
            int qcz = Math.floorDiv(q.z(), cell);
            // Bound the shell expansion by how far the grid actually extends.
            int maxR = 0;
            maxR = Math.max(maxR, Math.max(Math.abs(qcx - minCx), Math.abs(qcx - maxCx)));
            maxR = Math.max(maxR, Math.max(Math.abs(qcy - minCy), Math.abs(qcy - maxCy)));
            maxR = Math.max(maxR, Math.max(Math.abs(qcz - minCz), Math.abs(qcz - maxCz)));

            TreeModel.BlockPos best = null;
            long bestSq = Long.MAX_VALUE;
            for (int r = 0; r <= maxR; r++) {
                if (best != null) {
                    // Closest possible point in any cell at Chebyshev radius r is at
                    // least (r-1)*cell away; once that exceeds the best found, stop.
                    long minDist = (long) (r - 1) * cell;
                    if (minDist > 0 && minDist * minDist > bestSq) break;
                }
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                            List<TreeModel.BlockPos> b = buckets.get(key(qcx + dx, qcy + dy, qcz + dz));
                            if (b == null) continue;
                            for (TreeModel.BlockPos w : b) {
                                long sq = squaredDistance(q, w);
                                if (sq < bestSq) {
                                    bestSq = sq;
                                    best = w;
                                }
                            }
                        }
                    }
                }
            }
            return best;
        }
    }

    private static long squaredDistance(TreeModel.BlockPos a, TreeModel.BlockPos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Walks orthogonally (one axis per step, largest remaining delta first) from a
     * log towards an orphan leaf, turning the air cells it crosses into wood.
     *
     * <p>Rather than extending wood until it sits directly beside the orphan
     * (which buried a log in the outermost canopy leaf and left it exposed on the
     * surface, e.g. the spruce logs poking through the leaf shell), the branch
     * stops short: the last few cells nearest the orphan are kept as leaves so the
     * orphan stays within {@value #MAX_LEAF_DISTANCE} orthogonal leaf steps of the
     * new wood tip while that tip remains tucked inside the canopy. We leave the
     * longest leaf-only suffix of the path (capped so the orphan's decay distance
     * never exceeds the radius) untouched and only rasterise wood up to it.
     */
    private List<TreeModel.BlockPos> rasterizeWoodLine(Map<TreeModel.BlockPos, String> blocks,
                                   TreeModel.BlockPos from, TreeModel.BlockPos to, String branchWood,
                                   Set<TreeModel.BlockPos> wood, WoodGrid grid) {
        List<TreeModel.BlockPos> placed = new ArrayList<>();
        if (from == null || to == null) return placed;

        List<int[]> rawPath = PolarCurve.traceSteepestOrthogonalLine(
                from.x(), from.y(), from.z(), to.x(), to.y(), to.z());
        List<TreeModel.BlockPos> path = new ArrayList<>(rawPath.size());
        for (int[] step : rawPath) {
            path.add(new TreeModel.BlockPos(step[0], step[1], step[2]));
        }

        int suffix = 0;
        for (int i = path.size() - 1; i >= 0 && suffix < MAX_LEAF_DISTANCE - 1; i--) {
            String cur = blocks.get(path.get(i));
            if (cur != null && isLeaf(cur)) {
                suffix++;
            } else {
                break;
            }
        }

        int placeUpTo = path.size() - suffix;
        for (int i = 0; i < placeUpTo; i++) {
            TreeModel.BlockPos pos = path.get(i);
            String cur = blocks.get(pos);
            if (cur == null || isLeaf(cur)) {
                blocks.put(pos, branchWood);
                if (wood != null) wood.add(pos);
                if (grid != null) grid.add(pos);
                placed.add(pos);
            }
        }
        return placed;
    }

    private void applyTrunkWoodVariants(Map<TreeModel.BlockPos, String> blocks, String trunkBlock, String secondaryTrunk) {
        String wood = logToWood(trunkBlock);
        String sWood = secondaryTrunk != null ? logToWood(secondaryTrunk) : null;

        String trunkBase = trunkBlock.split("\\[")[0];
        String sTrunkBase = secondaryTrunk != null ? secondaryTrunk.split("\\[")[0] : null;

        Map<TreeModel.BlockPos, String> updates = new HashMap<>();
        blocks.forEach((pos, block) -> {
            String base = block.split("\\[")[0];
            if (base.equals(trunkBase)) {
                if (!blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() + 1, pos.z())) || !blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() - 1, pos.z()))) {
                    updates.put(pos, wood);
                }
            } else if (sTrunkBase != null && base.equals(sTrunkBase)) {
                if (!blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() + 1, pos.z())) || !blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() - 1, pos.z()))) {
                    updates.put(pos, sWood);
                }
            }
        });
        blocks.putAll(updates);
    }

    private String logToWood(String log) {
        String base = log.split("\\[")[0];
        String wood = switch (base) {
            case "minecraft:oak_log" -> "minecraft:oak_wood";
            case "minecraft:birch_log" -> "minecraft:birch_wood";
            case "minecraft:spruce_log" -> "minecraft:spruce_wood";
            case "minecraft:jungle_log" -> "minecraft:jungle_wood";
            case "minecraft:acacia_log" -> "minecraft:acacia_wood";
            case "minecraft:dark_oak_log" -> "minecraft:dark_oak_wood";
            case "minecraft:cherry_log" -> "minecraft:cherry_wood";
            case "minecraft:mangrove_log" -> "minecraft:mangrove_wood";
            default -> base;
        };
        return wood;
    }

    private void capTrunkTip(Map<TreeModel.BlockPos, String> canopy, Map<TreeModel.BlockPos, String> trunk, String leafBlock, String mode, double density, Random random, List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, double secondaryFraction, double[] phases, double verticalScale) {
        Map<TreeModel.BlockPos, String> combined = new HashMap<>(trunk);
        combined.putAll(canopy);

        List<TreeModel.BlockPos> logs = combined.entrySet().stream()
                .filter(e -> isWood(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (logs.isEmpty()) return;

        int topY = logs.stream().mapToInt(TreeModel.BlockPos::y).max().orElse(0);
        List<TreeModel.BlockPos> exposed = logs.stream()
                .filter(p -> p.y() >= topY - 1 && !combined.containsKey(new TreeModel.BlockPos(p.x(), p.y() + 1, p.z())))
                .toList();

        if (exposed.isEmpty()) return;

        // Enclose the apex by filling the cell directly above EVERY exposed top log.
        // The previous approach placed a single shared leaf disc centred on the
        // exposed-log centroid; when the exposed set was spread out (a leaning/spiral
        // trunk tip plus nearby branch logs) the trunk-tip column landed on the
        // disc's sparse density edge and was skipped, leaving a one-block air gap
        // between the trunk and its canopy. Capping each log individually guarantees
        // no log top is left bare and no gap appears above the trunk apex.
        for (TreeModel.BlockPos p : exposed) {
            TreeModel.BlockPos above = new TreeModel.BlockPos(p.x(), p.y() + 1, p.z());
            if (!combined.containsKey(above)) {
                String leaf = resolveLeaf(leafBlock, secondaryLeaves, secondaryFraction, random);
                canopy.put(above, leaf);
                combined.put(above, leaf);
            }
        }
    }

    /**
     * Fills the cell directly above every exposed top log in a single merged block
     * map with a leaf, so the canopy stays connected to the trunk apex. Unlike
     * {@link #capTrunkTip}, this operates on the final, already-merged geometry and
     * is therefore safe to run after {@link #connectLeavesWithBranches}, which can
     * otherwise turn the capping leaf into wood and reopen the air gap.
     */
    private void capExposedLogTops(Map<TreeModel.BlockPos, String> blocks, String leafBlock, Random random, List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, double secondaryFraction) {
        List<TreeModel.BlockPos> logs = blocks.entrySet().stream()
                .filter(e -> isWood(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (logs.isEmpty()) return;

        int topY = logs.stream().mapToInt(TreeModel.BlockPos::y).max().orElse(0);
        List<TreeModel.BlockPos> exposed = logs.stream()
                .filter(p -> p.y() >= topY - 1 && !blocks.containsKey(new TreeModel.BlockPos(p.x(), p.y() + 1, p.z())))
                .toList();

        for (TreeModel.BlockPos p : exposed) {
            TreeModel.BlockPos above = new TreeModel.BlockPos(p.x(), p.y() + 1, p.z());
            if (!blocks.containsKey(above)) {
                String leaf = resolveLeaf(leafBlock, secondaryLeaves, secondaryFraction, random);
                blocks.put(above, leaf);
            }
        }
    }

    private void generateBranches(Map<TreeModel.BlockPos, String> canopyBlocks, int height, List<double[]> offsets, String trunkBlock, String leafBlock, TreeSpecies.CanopyParams canopy, Random random, Map<TreeModel.BlockPos, String> trunkBlocks, List<double[]> branchEndpoints, List<TreeSpecies.CanopyParams.Layer> layers) {
        TreeSpecies.BranchParams b = canopy.branches();
        int startY;
        if (b.leafStartUp() && layers != null && !layers.isEmpty()) {
            int minLayerY = layers.stream().mapToInt(TreeSpecies.CanopyParams.Layer::yOffset).min().orElse(0);
            startY = Math.max(0, minLayerY);
        } else {
            startY = Math.max(0, (int) (height * b.startHeight()));
        }

        for (int y = startY; y < height; y++) {
            double t = (double) y / Math.max(height - 1, 1);
            double p = calculateBranchProbability(t, b.probFn(), b.probParams());

            if (random.nextDouble() > p) {
                continue;
            }

            double length = calculateBranchLength(t, b.lengthFn(), b.lengthParams(), b.minLength(), b.maxLength());
            double azDeg = random.nextDouble() * 360.0;
            double elDeg = b.minElevation() + random.nextDouble() * (b.maxElevation() - b.minElevation());
            double az = Math.toRadians(azDeg);
            double el = Math.toRadians(elDeg);

            double[] offset = offsets.isEmpty()
                    ? new double[]{0.0, 0.0}
                    : (y >= 0 && y < offsets.size()) ? offsets.get(y) : offsets.get(offsets.size() - 1);
            double ox = offset[0];
            double oy = y;
            double oz = offset[1];

            double[] branchVec = PolarCurve.sphericalOffset(length, el, az);
            double ex = ox + branchVec[0];
            double ey = oy + branchVec[1];
            double ez = oz + branchVec[2];

            rasterizeBranch(canopyBlocks, ox, oy, oz, ex, ey, ez, trunkBlock, trunkBlocks);
            placeLeafCluster(canopyBlocks, ex, ey, ez, b.clusterRadius(), leafBlock, b.clusterMode(), b.clusterDensity(), random, canopy.secondaryLeafList(), canopy.secondaryFraction(), trunkBlocks, canopy.leafVerticalScale());
            if (branchEndpoints != null) branchEndpoints.add(new double[]{ex, ey, ez, ox, oz});

            TreeSpecies.BranchParams.SubBranchParams sub = b.subBranches();
            if (sub != null && sub.count() > 0) {
                for (int si = 0; si < sub.count(); si++) {
                    double yawOffset = PolarCurve.fanAngleOffset(si, sub.count(), sub.yawDelta());
                    double subAz = Math.toRadians(azDeg + yawOffset);
                    double subEl = Math.toRadians(elDeg + sub.pitchDelta());
                    double subLen = length * sub.lengthScale();

                    double[] subVec = PolarCurve.sphericalOffset(subLen, subEl, subAz);
                    double sx = ex + subVec[0];
                    double sy = ey + subVec[1];
                    double sz = ez + subVec[2];

                    rasterizeBranch(canopyBlocks, ex, ey, ez, sx, sy, sz, trunkBlock, trunkBlocks);
                    placeLeafCluster(canopyBlocks, sx, sy, sz, sub.clusterRadius(), leafBlock, sub.clusterMode(), sub.clusterDensity(), random, canopy.secondaryLeafList(), canopy.secondaryFraction(), trunkBlocks, canopy.leafVerticalScale());
                    if (branchEndpoints != null) branchEndpoints.add(new double[]{sx, sy, sz, ex, ez});
                }
            }
        }
    }

    private double calculateBranchProbability(double t, String fn, Map<String, Double> params) {
        if (fn == null) return UnitCurve.power(t, 2.0); // Default to TOP_HEAVY (exponent=2)
        return switch (fn.toUpperCase(Locale.ROOT)) {
            case "CONSTANT" -> params != null ? params.getOrDefault("p", 0.5) : 0.5;
            case "LINEAR" -> {
                double baseP = params != null ? params.getOrDefault("base_p", 0.0) : 0.0;
                double crownP = params != null ? params.getOrDefault("crown_p", 1.0) : 1.0;
                yield UnitCurve.linear(t, baseP, crownP);
            }
            case "SIGMOID" -> {
                double steepness = params != null ? params.getOrDefault("steepness", 10.0) : 10.0;
                double midpoint = params != null ? params.getOrDefault("midpoint", 0.7) : 0.7;
                yield UnitCurve.sigmoid(t, midpoint, steepness);
            }
            case "GAUSSIAN" -> {
                double mean = params != null ? params.getOrDefault("mean", 0.7) : 0.7;
                double std = params != null ? params.getOrDefault("std", 0.15) : 0.15;
                yield UnitCurve.gaussian(t, mean, Math.max(std, 1e-6));
            }
            case "NOISE" -> {
                double scale = params != null ? params.getOrDefault("scale", 4.0) : 4.0;
                long seed = params != null ? params.getOrDefault("seed", 0.0).longValue() : 0L;
                yield valueNoise1D(t * scale, seed);
            }
            default -> UnitCurve.power(t, params != null ? params.getOrDefault("exponent", 2.0) : 2.0);
        };
    }

    private static double hashNoise(long x, long y, long z, long seed) {
        long h = seed ^ (x * 374761393L) ^ (y * 668265263L) ^ (z * 2147483647L);
        h = (h ^ (h >>> 13)) * 1274126177L;
        h = h ^ (h >>> 16);
        return ((h & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL);
    }

    private static double valueNoise1D(double x, long seed) {
        long xi = (long) Math.floor(x);
        double xf = x - xi;
        double u = xf * xf * (3.0 - 2.0 * xf);
        double a = hashNoise(xi, 0L, 0L, seed);
        double b = hashNoise(xi + 1L, 0L, 0L, seed);
        return a + u * (b - a);
    }

    private double calculateBranchLength(double t, String fn, Map<String, Double> params, double minL, double maxL) {
        if (fn == null) return UnitCurve.linear(t, minL, maxL);
        double len = switch (fn.toUpperCase(Locale.ROOT)) {
            case "CONSTANT" -> params != null ? params.getOrDefault("length", 3.0) : 3.0;
            case "SIGMOID" -> {
                double maxLen = params != null ? params.getOrDefault("max_len", 4.0) : 4.0;
                double steepness = params != null ? params.getOrDefault("steepness", 5.0) : 5.0;
                yield maxLen * UnitCurve.sigmoid(t, 0.5, steepness);
            }
            case "LOG" -> {
                double maxLen = params != null ? params.getOrDefault("max_len", 4.0) : 4.0;
                yield maxLen * UnitCurve.logarithmic(t, Math.E);
            }
            case "PARABOLIC" -> {
                double maxLen = params != null ? params.getOrDefault("max_len", 4.0) : 4.0;
                yield maxLen * UnitCurve.parabolicCrown(t);
            }
            default -> {
                double base = params != null ? params.getOrDefault("base", 1.0) : 1.0;
                double crown = params != null ? params.getOrDefault("crown", 4.0) : 4.0;
                yield UnitCurve.linear(t, base, crown);
            }
        };
        return Math.max(1.0, len);
    }

    private void rasterizeBranch(Map<TreeModel.BlockPos, String> blocks, double x0, double y0, double z0, double x1, double y1, double z1, String block, Map<TreeModel.BlockPos, String> existing) {
        String axis = calculateAxis(x1 - x0, y1 - y0, z1 - z0);
        String orientedBlock = applyAxis(block, axis);

        for (int[] step : PolarCurve.trace6ConnectedLine(x0, y0, z0, x1, y1, z1)) {
            placeBranchBlock(blocks, existing, step[0], step[1], step[2], orientedBlock);
        }
    }

    private void placeBranchBlock(Map<TreeModel.BlockPos, String> blocks, Map<TreeModel.BlockPos, String> existing, int x, int y, int z, String block) {
        TreeModel.BlockPos p = new TreeModel.BlockPos(x, y, z);
        if (!existing.containsKey(p)) {
            blocks.put(p, block);
        }
    }

    private String calculateAxis(double dx, double dy, double dz) {
        double ax = Math.abs(dx);
        double ay = Math.abs(dy) * 1.8;
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) return "y";
        if (ax >= az) return "x";
        return "z";
    }

    private String applyAxis(String block, String axis) {
        if (block.contains("[")) return block;
        String low = block.toLowerCase();
        // mushroom_stem has no "axis" property (unlike crimson_stem / warped_stem),
        // so appending [axis=...] produces an unparseable block state.
        if (low.contains("mushroom_stem")) {
            return block;
        }
        if (low.contains("log") || low.contains("wood") || low.contains("stem") || low.contains("hyphae") || low.contains("pillar")) {
            return block + "[axis=" + axis + "]";
        }
        return block;
    }

    private List<TreeSpecies.CanopyParams.Layer> generatePresetLayers(String profile, int height, boolean branchDriven) {
        double scale = switch (profile) {
            case "BIRCH" -> 0.7;
            case "SPRUCE", "PINE", "TAIGA" -> 0.55;
            case "JUNGLE", "MEGA_JUNGLE" -> 0.45;
            case "ACACIA" -> 0.6;
            case "DARK_OAK_FLAT" -> 1.25;
            default -> 1.0;
        };

        int baseRadius = Math.max(3, (int) Math.round(height / 2.0 * scale));

        double[][] fractions = switch (profile) {
            case "BIRCH" -> new double[][]{{0.6, 0.45}, {0.7, 0.75}, {0.8, 0.85}, {0.88, 0.75}, {0.94, 0.45}, {0.99, 0.2}};
            case "SPRUCE", "PINE", "TAIGA" -> new double[][]{{0.3, 1.0}, {0.42, 0.85}, {0.54, 0.7}, {0.65, 0.55}, {0.75, 0.4}, {0.84, 0.25}, {0.91, 0.15}, {0.97, 0.05}};
            case "JUNGLE", "MEGA_JUNGLE" -> new double[][]{{0.82, 0.4}, {0.88, 0.8}, {0.93, 1.0}, {0.97, 0.7}, {1.0, 0.3}};
            case "ACACIA" -> new double[][]{{0.9, 0.5}, {0.95, 0.8}, {0.99, 0.4}};
            case "DARK_OAK" -> new double[][]{{0.5, 0.7}, {0.62, 1.0}, {0.72, 1.0}, {0.82, 0.9}, {0.91, 0.6}, {0.97, 0.3}};
            case "DARK_OAK_FLAT" -> new double[][]{{0.74, 0.62}, {0.83, 1.0}, {0.92, 1.0}, {1.0, 0.6}};
            case "CHERRY" -> new double[][]{{0.5, 0.5}, {0.62, 0.9}, {0.74, 1.0}, {0.84, 1.0}, {0.92, 0.7}, {0.98, 0.4}};
            default -> new double[][]{{0.55, 0.6}, {0.65, 0.9}, {0.75, 1.0}, {0.85, 0.9}, {0.92, 0.65}, {0.98, 0.35}};
        };

        int crownExtra = branchDriven ? 0 : Math.max(2, height / 4);
        int yTop = (height - 1) + crownExtra;

        List<TreeSpecies.CanopyParams.Layer> layers = new ArrayList<>();
        for (double[] f : fractions) {
            int yOff = (int) Math.round(f[0] * yTop);
            double radius = Math.max(1.5, baseRadius * f[1]);
            layers.add(new TreeSpecies.CanopyParams.Layer(yOff, radius));
        }
        return layers;
    }

    private double[] calculateLeanOffset(int y, int height, double azimuthDeg, double angleDeg, String curveFn, Map<String, Double> cParams, String azFn, Map<String, Double> azParams) {
        double t = UnitCurve.clamp01((double) y / Math.max(height - 1, 1));
        double progress = switch (curveFn.toUpperCase(Locale.ROOT)) {
            case "LOG" -> UnitCurve.logarithmic(t, Math.E);
            case "SIGMOID" -> UnitCurve.sigmoid(t, 0.5, cParams.getOrDefault("steepness", 8.0));
            case "PARABOLIC" -> UnitCurve.smoothstep(t);
            default -> t;
        };

        double maxReach = height * Math.tan(Math.toRadians(angleDeg));
        double reach = maxReach * progress;

        double az = switch (azFn.toUpperCase(Locale.ROOT)) {
            case "LINEAR" -> UnitCurve.linear(t, azParams.getOrDefault("start", 0.0), azParams.getOrDefault("end", 0.0));
            case "SPIRAL" -> Math.toDegrees(PolarCurve.spiralAngle(t, azParams.getOrDefault("turns", 1.0), Math.toRadians(azParams.getOrDefault("start", 0.0))));
            case "SINE" -> UnitCurve.harmonic(t, azParams.getOrDefault("offset", 0.0), azParams.getOrDefault("amplitude", 90.0), Math.max(azParams.getOrDefault("period", 1.0), 1e-6), 0.0);
            default -> azimuthDeg;
        };

        double azRad = Math.toRadians(az);
        return new double[]{reach * Math.sin(azRad), reach * Math.cos(azRad)};
    }

    private double calculateWidth(int y, int height, double baseWidth, String shape, Map<String, Double> params) {
        double t = UnitCurve.clamp01((double) y / Math.max(height - 1, 1));
        double mult = switch (shape.toUpperCase(Locale.ROOT)) {
            case "LINEAR" -> UnitCurve.linear(t, params.getOrDefault("start", 1.0), params.getOrDefault("end", 0.5));
            case "SIGMOID" -> UnitCurve.sigmoid(t, 0.5, -params.getOrDefault("steepness", 5.0));
            case "LOG" -> t <= 0 ? 1.0 : UnitCurve.logarithmicTaper(t, params.getOrDefault("base", Math.E));
            case "SINE" -> UnitCurve.harmonic(t, 1.0, params.getOrDefault("amplitude", 0.2), params.getOrDefault("period", 1.0), 0.0);
            case "PARABOLIC" -> UnitCurve.parabolicValley(
                    t, params.getOrDefault("peak_offset", 0.5), params.getOrDefault("floor", 0.5));
            default -> 1.0;
        };
        // Mirror trunk.py width_at: never let the trunk taper below a single block.
        // Without this clamp, log/sigmoid shapes round to width 0 in the upper
        // trunk, leaving gaps so the trunk is non-contiguous and can stop short of
        // the canopy.
        return Math.max(1, Math.round(baseWidth * mult));
    }

    private void placeTrunkSlice(Map<TreeModel.BlockPos, String> blocks, int ox, int oy, int oz, double cx, double cz, double width, String block, boolean round, Random random, double[] phases, boolean fillEmptyOnly) {
        int w = (int) Math.round(width);
        if (w % 2 == 1) {
            int half = w / 2;
            int icx = (int) Math.round(cx);
            int icz = (int) Math.round(cz);
            double rad = half + 0.5;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (round && Math.hypot(dx, dz) > irregularRadius(rad, dx, dz, random, phases)) continue;
                    TreeModel.BlockPos p = new TreeModel.BlockPos(ox + icx + dx, oy, oz + icz + dz);
                    if (!fillEmptyOnly || !blocks.containsKey(p)) blocks.put(p, block);
                }
            }
        } else {
            int half = w / 2;
            int iox = (int) Math.floor(cx) - half + 1;
            int ioz = (int) Math.floor(cz) - half + 1;
            double rad = width / 2.0;
            double ccx = iox + (w - 1) / 2.0;
            double ccz = ioz + (w - 1) / 2.0;
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < w; dz++) {
                    double ddx = (iox + dx) - ccx;
                    double ddz = (ioz + dz) - ccz;
                    if (round && Math.hypot(ddx, ddz) > irregularRadius(rad, ddx, ddz, random, phases)) continue;
                    TreeModel.BlockPos p = new TreeModel.BlockPos(ox + iox + dx, oy, oz + ioz + dz);
                    if (!fillEmptyOnly || !blocks.containsKey(p)) blocks.put(p, block);
                }
            }
        }
    }

    private double irregularRadius(double rad, double dx, double dz, Random random, double[] phases) {
        double theta = Math.atan2(dz, dx);
        if (phases == null) {
            return PolarCurve.harmonicRadius(rad, theta, 0.0, 0.0, 0.0);
        }
        return PolarCurve.harmonicRadius(rad, theta, phases[0], phases[1], phases[2]);
    }

    private void placeLeafDisc(Map<TreeModel.BlockPos, String> blocks, double cx, double cy, double cz, double radius, String block, String mode, double density, Random random, List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, double secondaryFraction, Map<TreeModel.BlockPos, String> existing, double[] phases, double verticalScale) {
        int r = (int) Math.ceil(radius);
        int icx = (int) Math.round(cx);
        int icy = (int) Math.round(cy);
        int icz = (int) Math.round(cz);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                // Leaf discs are plain circles (matches canopy.py _place_leaf_disc).
                // The angular-noise irregular radius is reserved for the round trunk
                // cross-section; applying it to leaf discs produced ragged edges and
                // stray single leaves that read as detached/floating in-world.
                if (mode != null && mode.equalsIgnoreCase("TRIMMED")) {
                    if (dist > radius || (Math.abs(dx) == r && Math.abs(dz) == r)) continue;
                } else {
                    if (dist > radius) continue;
                }

                if (mode != null && (mode.equalsIgnoreCase("DENSITY") || mode.equalsIgnoreCase("NOISE"))) {
                    if (random.nextDouble() > UnitCurve.radialFalloff(dist, radius, density)) continue;
                }

                TreeModel.BlockPos p = new TreeModel.BlockPos(icx + dx, icy, icz + dz);
                if (!existing.containsKey(p) && !blocks.containsKey(p)) {
                    String activeBlock = resolveLeaf(block, secondaryLeaves, secondaryFraction, random);
                    blocks.put(p, activeBlock);
                }
            }
        }
    }

    private void placeLeafCluster(Map<TreeModel.BlockPos, String> blocks, double cx, double cy, double cz, int radius, String block, String mode, double density, Random random, List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, double secondaryFraction, Map<TreeModel.BlockPos, String> existing, double verticalScale) {
        int icx = (int) Math.round(cx);
        int icy = (int) Math.round(cy);
        int icz = (int) Math.round(cz);

        int yRad = (int) Math.ceil(radius * verticalScale);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -yRad; dy <= yRad; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double dist = PolarCurve.ellipsoidDistance(dx, dy, dz, verticalScale);
                    if (mode != null && mode.equalsIgnoreCase("TRIMMED")) {
                        if (dist > radius || (Math.abs(dx) == radius && Math.abs(dz) == radius)) continue;
                    } else {
                        if (dist > radius) continue;
                    }

                    if (mode != null && (mode.equalsIgnoreCase("DENSITY") || mode.equalsIgnoreCase("NOISE"))) {
                        if (random.nextDouble() > UnitCurve.radialFalloff(dist, radius, density)) continue;
                    }

                    TreeModel.BlockPos p = new TreeModel.BlockPos(icx + dx, icy + dy, icz + dz);
                    if (!existing.containsKey(p) && !blocks.containsKey(p)) {
                        String activeBlock = resolveLeaf(block, secondaryLeaves, secondaryFraction, random);
                        blocks.put(p, activeBlock);
                    }
                }
            }
        }
    }

    private String resolveLeaf(String leafBlock, List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, double secondaryFraction, Random random) {
        if (secondaryLeaves == null || secondaryLeaves.isEmpty() || secondaryFraction <= 0.0) {
            return leafBlock;
        }
        if (random.nextDouble() >= secondaryFraction) {
            return leafBlock;
        }
        if (secondaryLeaves.size() == 1) {
            return secondaryLeaves.get(0).block();
        }
        int totalWeight = 0;
        for (TreeSpecies.CanopyParams.WeightedBlock wb : secondaryLeaves) {
            totalWeight += Math.max(1, wb.weight());
        }
        int roll = random.nextInt(Math.max(1, totalWeight));
        int acc = 0;
        for (TreeSpecies.CanopyParams.WeightedBlock wb : secondaryLeaves) {
            acc += Math.max(1, wb.weight());
            if (roll < acc) {
                return wb.block();
            }
        }
        return secondaryLeaves.get(0).block();
    }

    /**
     * Replaces the bottom-most leaf block of every (x,z) column in the canopy with the
     * configured underside block (e.g. shroomlight), creating a single glow layer across
     * the underside of the cap. Only leaf blocks (the primary leaf block or the configured
     * secondary leaves) are considered, so branch logs/wood are never overwritten.
     */
    private void applyUndersideLeaves(Map<TreeModel.BlockPos, String> canopyBlocks, String leafBlock,
                                      List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeaves, String undersideLeaves, double chance, Random random) {
        Set<String> leafBases = new HashSet<>();
        if (leafBlock != null) {
            leafBases.add(leafBlock.split("\\[")[0]);
        }
        if (secondaryLeaves != null) {
            for (TreeSpecies.CanopyParams.WeightedBlock wb : secondaryLeaves) {
                if (wb.block() != null) {
                    leafBases.add(wb.block().split("\\[")[0]);
                }
            }
        }

        // Find the lowest leaf block position for each (x,z) column.
        Map<Long, TreeModel.BlockPos> lowest = new HashMap<>();
        canopyBlocks.forEach((pos, block) -> {
            String base = block.split("\\[")[0];
            if (!leafBases.contains(base)) return;
            long key = TreeModel.columnKey(pos.x(), pos.z());
            TreeModel.BlockPos current = lowest.get(key);
            if (current == null || pos.y() < current.y()) {
                lowest.put(key, pos);
            }
        });

        for (TreeModel.BlockPos pos : lowest.values()) {
            if (chance >= 1.0 || random.nextDouble() < chance) {
                canopyBlocks.put(pos, undersideLeaves);
            }
        }
    }

    /**
     * Applies accent decorators to the assembled tree, mirroring the offline Python
     * {@code decorators.py}. Decorator blocks are only placed into empty cells (never
     * overwriting trunk/canopy blocks or each other) and are added into the canopy map
     * so they become part of the final model.
     */
    private void applyDecorators(List<TreeSpecies.Decorator> decorators,
                                 Map<TreeModel.BlockPos, String> trunkBlocks,
                                 Map<TreeModel.BlockPos, String> canopyBlocks,
                                 List<double[]> branchEndpoints, Random random) {
        // Existing occupancy: trunk + canopy blocks. Decorators never overwrite these.
        Map<TreeModel.BlockPos, String> existing = new HashMap<>(trunkBlocks);
        canopyBlocks.forEach(existing::putIfAbsent);
        Map<TreeModel.BlockPos, String> result = new HashMap<>();

        for (TreeSpecies.Decorator dec : decorators) {
            String target = dec.target() == null ? "branch_tip" : dec.target();
            switch (target) {
                case "branch_tip" -> applyBranchTip(result, existing, dec, branchEndpoints, random);
                case "trunk_surface" -> applyTrunkSurface(result, existing, dec, trunkBlocks.keySet(), random);
                case "canopy_top" -> applyCanopyTop(result, existing, dec, random);
                case "trunk_base" -> applyTrunkBase(result, existing, dec, trunkBlocks.keySet(), random);
                case "leaf_exterior" -> applyLeafExterior(result, existing, dec, random);
                default -> { /* unknown target: ignore (e.g. canopy_bottom handled elsewhere) */ }
            }
        }

        // Merge decorator blocks into the canopy (they live in the final model).
        // Use put (not putIfAbsent) so branch_tip ornaments can overwrite the leaf
        // block sitting on the branch tip; every other decorator target only ever
        // writes into air cells, so overwriting is a no-op for them.
        result.forEach(canopyBlocks::put);
    }

    /** Cardinal direction facing away from the trunk center, for orientable accents. */
    private String facingAway(double cx, double cz, int x, int z) {
        double dx = x - cx;
        double dz = z - cz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    /**
     * Appends an orientation blockstate property when {@code facing} is supplied and supported
     * by {@code block} (multiface {@code <face>=true}, pillar {@code axis=x|z}, or horizontal
     * {@code facing=north|south|east|west}), leaving non-orientable blocks unchanged.
     */
    private String expandBlock(String block, String facing) {
        if (block == null || block.contains("[") || facing == null) return block;
        String lower = block.toLowerCase(Locale.ROOT);
        if (isMultifaceBlock(lower)) {
            String attach = oppositeHorizontalFace(facing);
            return attach != null ? block + "[" + attach + "=true]" : block;
        }
        if (isAxisOrientedBlock(lower)) {
            String axis = ("east".equals(facing) || "west".equals(facing)) ? "x" : "z";
            return block + "[axis=" + axis + "]";
        }
        if (isNonDirectionalDecoratorBlock(lower)) {
            return block;
        }
        return block + "[facing=" + facing + "]";
    }

    private static boolean isMultifaceBlock(String lower) {
        return lower.endsWith("glow_lichen")
                || lower.endsWith(":vine")
                || lower.equals("vine")
                || lower.endsWith("sculk_vein")
                || lower.endsWith("resin_clump");
    }

    private static boolean isAxisOrientedBlock(String lower) {
        return lower.endsWith("_log")
                || lower.endsWith("_wood")
                || lower.endsWith("_stem")
                || lower.endsWith("_hyphae")
                || lower.endsWith("bamboo_block")
                || lower.endsWith("bone_block")
                || lower.endsWith("basalt")
                || lower.endsWith("froglight")
                || lower.endsWith(":chain")
                || lower.equals("chain");
    }

    private static boolean isNonDirectionalDecoratorBlock(String lower) {
        return lower.endsWith("mushroom")
                || lower.endsWith("fungus")
                || lower.endsWith("roots")
                || lower.endsWith("carpet")
                || lower.endsWith("shroomlight")
                || lower.endsWith("glowstone")
                || lower.endsWith("sea_lantern")
                || lower.endsWith("cobweb")
                || lower.endsWith("spore_blossom")
                || lower.endsWith("cave_vines")
                || lower.endsWith("weeping_vines")
                || lower.endsWith("twisting_vines")
                || lower.endsWith("hanging_moss")
                || lower.endsWith("lantern")
                || lower.endsWith("wart_block")
                || lower.endsWith("_leaves")
                || lower.endsWith(":snow")
                || lower.endsWith("ice");
    }

    private static String oppositeHorizontalFace(String facing) {
        return switch (facing) {
            case "east" -> "west";
            case "west" -> "east";
            case "south" -> "north";
            case "north" -> "south";
            default -> null;
        };
    }

    /**
     * Place the decorator near each branch endpoint with the configured chance.
     * Ornaments hang on the leafy shell around a branch tip; the first non-wood
     * candidate cell (just outside the tip, the tip itself, or just below it) is
     * used, overwriting a leaf there. Structural wood and cells already taken by
     * another decorator are left untouched.
     */
    private void applyBranchTip(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                TreeSpecies.Decorator dec, List<double[]> branchEndpoints, Random random) {
        if (branchEndpoints == null || branchEndpoints.isEmpty()) return;
        for (double[] ep : branchEndpoints) {
            if (random.nextDouble() > dec.chance()) continue;
            int tx = (int) Math.round(ep[0]);
            int ty = (int) Math.round(ep[1]);
            int tz = (int) Math.round(ep[2]);
            // Try all 6 orthogonal neighbors of the tip plus the tip itself, preferring
            // the outward direction first. The first cell that is not structural wood and
            // not already claimed by a different decorator wins.
            int odx = (int) Math.signum(ep[0] - ep[3]);
            int odz = (int) Math.signum(ep[2] - ep[4]);
            TreeModel.BlockPos[] candidates = {
                    new TreeModel.BlockPos(tx + odx, ty, tz + odz),
                    new TreeModel.BlockPos(tx, ty, tz),
                    new TreeModel.BlockPos(tx, ty - 1, tz),
                    new TreeModel.BlockPos(tx, ty + 1, tz),
                    new TreeModel.BlockPos(tx + (odx == 0 ? 1 : 0), ty, tz),
                    new TreeModel.BlockPos(tx - (odx == 0 ? 1 : 0), ty, tz),
                    new TreeModel.BlockPos(tx, ty, tz + (odz == 0 ? 1 : 0)),
                    new TreeModel.BlockPos(tx, ty, tz - (odz == 0 ? 1 : 0))
            };
            for (TreeModel.BlockPos pos : candidates) {
                if (result.containsKey(pos)) continue;
                String occupant = existing.get(pos);
                if (occupant != null && isStructuralWood(occupant)) continue;
                String facing = dec.axisAware() ? facingAway(ep[3], ep[4], pos.x(), pos.z()) : null;
                result.put(pos, expandBlock(dec.block(), facing));
                break;
            }
        }
    }

    /** Whether the block is load-bearing trunk/branch wood that ornaments must not replace. */
    private boolean isStructuralWood(String block) {
        String base = block.split("\\[")[0];
        return base.contains("log") || base.contains("wood") || base.contains("stem") || base.contains("hyphae");
    }

    /**
     * Place the decorator on the air cell adjacent to each exterior leaf block.
     * "Exterior" means the leaf has at least one non-underside neighbor (N/S/E/W/up)
     * that is empty (not in existing tree blocks or already-placed decorators).
     * The underside (y-1) is skipped so ornaments don't hang below the canopy.
     * Each qualifying air cell is independently rolled against dec.chance().
     */
    private void applyLeafExterior(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                   TreeSpecies.Decorator dec, Random random) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}; // N/S/E/W/up, no underside
        for (TreeModel.BlockPos pos : existing.keySet()) {
            String block = existing.get(pos);
            if (block == null || isStructuralWood(block)) continue; // only leaf cells
            for (int[] d : dirs) {
                TreeModel.BlockPos neighbor = new TreeModel.BlockPos(pos.x() + d[0], pos.y() + d[1], pos.z() + d[2]);
                if (existing.containsKey(neighbor) || result.containsKey(neighbor)) continue;
                if (random.nextDouble() > dec.chance()) continue;
                result.put(neighbor, expandBlock(dec.block(), null));
            }
        }
    }

    /** Place the decorator on air-facing horizontal sides of trunk blocks (vines, lichen). */
    private void applyTrunkSurface(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                   TreeSpecies.Decorator dec, Set<TreeModel.BlockPos> trunkPositions, Random random) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        String[] attachFaces = {"west", "east", "north", "south"};
        String[] outwardFacings = {"east", "west", "south", "north"};
        for (TreeModel.BlockPos t : trunkPositions) {
            for (int i = 0; i < dirs.length; i++) {
                TreeModel.BlockPos neighbor = new TreeModel.BlockPos(t.x() + dirs[i][0], t.y(), t.z() + dirs[i][1]);
                if (existing.containsKey(neighbor) || result.containsKey(neighbor)) continue;
                if (random.nextDouble() > dec.chance()) continue;
                result.put(
                        neighbor,
                        expandTrunkSurfaceBlock(
                                dec.block(),
                                attachFaces[i],
                                dec.axisAware() ? outwardFacings[i] : null));
            }
        }
    }

    private String expandTrunkSurfaceBlock(String block, String attachFace, String outwardFacing) {
        if (block == null || block.contains("[")) return block;
        String lower = block.toLowerCase(Locale.ROOT);
        if (lower.endsWith("glow_lichen")
                || lower.endsWith(":vine")
                || lower.equals("vine")
                || lower.endsWith("sculk_vein")
                || lower.endsWith("resin_clump")) {
            return attachFace != null ? block + "[" + attachFace + "=true]" : block;
        }
        return expandBlock(block, outwardFacing);
    }

    /** Place the decorator on top of the highest occupied block in each (x,z) column. */
    private void applyCanopyTop(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                TreeSpecies.Decorator dec, Random random) {
        Map<Long, Integer> colTop = new HashMap<>();
        for (TreeModel.BlockPos pos : existing.keySet()) {
            long key = TreeModel.columnKey(pos.x(), pos.z());
            Integer top = colTop.get(key);
            if (top == null || pos.y() > top) colTop.put(key, pos.y());
        }
        for (Map.Entry<Long, Integer> e : colTop.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (e.getKey() & 0xffffffffL);
            TreeModel.BlockPos above = new TreeModel.BlockPos(x, e.getValue() + 1, z);
            if (existing.containsKey(above) || result.containsKey(above)) continue;
            if (random.nextDouble() > dec.chance()) continue;
            result.put(above, expandBlock(dec.block(), null));
        }
    }

    /** Scatter the decorator in the y=0 ring around the trunk base (deadwood, mushrooms). */
    private void applyTrunkBase(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                TreeSpecies.Decorator dec, Set<TreeModel.BlockPos> trunkPositions, Random random) {
        Set<Long> baseXz = new HashSet<>();
        for (TreeModel.BlockPos t : trunkPositions) {
            if (t.y() == 0) baseXz.add(TreeModel.columnKey(t.x(), t.z()));
        }
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (long key : baseXz) {
            int x = (int) (key >> 32);
            int z = (int) (key & 0xffffffffL);
            for (int[] d : dirs) {
                TreeModel.BlockPos pos = new TreeModel.BlockPos(x + d[0], 0, z + d[1]);
                if (existing.containsKey(pos) || result.containsKey(pos)) continue;
                if (random.nextDouble() > dec.chance()) continue;
                result.put(pos, expandBlock(dec.block(), null));
            }
        }
    }
}
