package net.leaf.treegen.common;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-agnostic tree species.
 */
public record TreeSpecies(
        String id,
        String displayName,
        String namespace,
        String group,
        Set<String> biomes,
        String saplingItem, // Material name (e.g. "OAK_SAPLING")
        boolean worldgen,
        boolean replaceVanilla,
        int spacing,
        int separation,
        int salt,
        String spreadType,
        String step,
        List<TreeVariant> variants,
        ProceduralParams procedural,
        List<WeightedSpecies> trees, // Optional: for group placement
        Map<String, ProceduralParams> treeDefinitions, // Named tree definitions within this species
        int weight, // Species-level weight for placement
        GrowthParams growth, // Staged-growth settings (never null; disabled by default)
        FeatureConfig featureConfig // Optional: vanilla minecraft:tree feature placement for vanilla-like density; null when unused
) {

        /**
         * Backwards-compatible constructor without the {@link GrowthParams} component.
         * Defaults growth to {@link GrowthParams#disabled()} so existing call sites that
         * predate the growth mechanic keep compiling and behave as before (instant placement).
         */
        public TreeSpecies(
                String id, String displayName, String namespace, String group, Set<String> biomes,
                String saplingItem, boolean worldgen, boolean replaceVanilla, int spacing, int separation,
                int salt, String spreadType, String step, List<TreeVariant> variants, ProceduralParams procedural,
                List<WeightedSpecies> trees, Map<String, ProceduralParams> treeDefinitions, int weight) {
                this(id, displayName, namespace, group, biomes, saplingItem, worldgen, replaceVanilla, spacing,
                        separation, salt, spreadType, step, variants, procedural, trees, treeDefinitions, weight,
                        GrowthParams.disabled(), null);
        }

        /**
         * Backwards-compatible constructor without the {@link FeatureConfig} component.
         * Defaults {@code featureConfig} to {@code null} (NBT/jigsaw placement, the historical
         * behavior) so existing call sites that predate vanilla-feature placement keep compiling.
         */
        public TreeSpecies(
                String id, String displayName, String namespace, String group, Set<String> biomes,
                String saplingItem, boolean worldgen, boolean replaceVanilla, int spacing, int separation,
                int salt, String spreadType, String step, List<TreeVariant> variants, ProceduralParams procedural,
                List<WeightedSpecies> trees, Map<String, ProceduralParams> treeDefinitions, int weight,
                GrowthParams growth) {
                this(id, displayName, namespace, group, biomes, saplingItem, worldgen, replaceVanilla, spacing,
                        separation, salt, spreadType, step, variants, procedural, trees, treeDefinitions, weight,
                        growth, null);
        }

        /** Compact canonical constructor: never store a null {@link GrowthParams}. */
        public TreeSpecies {
                if (growth == null) growth = GrowthParams.disabled();
        }

        /**
         * Staged-growth settings for a species. The admin authors only whether growth is
         * {@code enabled} and the total wall-clock time (in ticks) a planted tree takes to
         * fully reveal; all stage boundaries and per-stage durations are derived from the
         * baked structure by {@link GrowthPlanner}.
         */
        public record GrowthParams(boolean enabled, long totalTime) {
                private static final GrowthParams DISABLED = new GrowthParams(false, 0L);

                /** Shared disabled instance (growth off, instant placement). */
                public static GrowthParams disabled() {
                        return DISABLED;
                }
        }

        public record TreeVariant(String location, int weight) {}

        /**
         * Optional vanilla-feature placement for a species. When present, the datapack
         * generator places vanilla {@code minecraft:tree} configured features (selected from
         * {@link #trees}) via the species' jigsaw structure, applying a per-placement
         * {@link #treesPerPlacement} count so a single structure placement scatters multiple trees. This yields
         * vanilla-like tree density (e.g. old-growth pine taiga) which the one-tree-per-structure
         * NBT path cannot reach. Cross-platform: pure datapack, no custom worldgen feature.
         */
        public record FeatureConfig(int treesPerPlacement, List<WeightedFeature> trees) {}

        /** A vanilla configured-feature id (e.g. {@code minecraft:mega_pine}) with a selection weight. */
        public record WeightedFeature(String feature, int weight) {}

        public record WeightedSpecies(String id, int weight) {}

        public record ProceduralParams(
                String trunkBlock,
                String leafBlock,
                int minHeight,
                int maxHeight,
                String profile,
                double trunkWidth,
                String trunkShape,
                Map<String, Double> trunkShapeParams,
                boolean roundTrunk,
                double leanAngle,
                double leanAzimuth,
                String azimuthFn,
                Map<String, Double> azimuthParams,
                String curveFn,
                Map<String, Double> curveParams,
                String secondaryTrunk,
                double secondaryTrunkStart,
                double secondaryTrunkEnd,
                CanopyParams canopy,
                boolean capTrunk,
                int count,  // Number of distinct NBT variants to bake (variety only; does NOT affect placement frequency)
                int weight, // Relative placement weight, independent of count
                List<Decorator> decorators // Accent block placements (vines, cobwebs, fungus, etc.)
        ) {}

        /**
         * Accent block placement applied after the trunk/canopy are built. Mirrors the
         * offline Python {@code decorators.py} targets so the live/sapling generator and the
         * datapack baker render the same accents that the NBT export tool would.
         */
        public record Decorator(
                String target,  // branch_tip | trunk_surface | canopy_top | canopy_bottom | trunk_base
                String block,   // Block id to place
                double chance,  // Per-candidate placement chance (0..1)
                boolean axisAware // When true, inject facing=<away-from-trunk> on the block
        ) {}

        public record CanopyParams(
                String mode,
                double density,
                List<Layer> layers,
                BranchParams branches,
                String secondaryLeaves,
                double secondaryFraction,
                boolean volumeLayers,
                double leafVerticalScale,
                Double crownVolumeFraction,
                String undersideLeaves, // Block placed across the underside (bottom layer) of the canopy, e.g. glow blocks
                double undersideChance, // Per-block chance (0..1) of replacing a bottom-layer leaf with undersideLeaves
                double startAngle,
                double squish,
                List<WeightedBlock> secondaryLeafList
        ) {
                public CanopyParams(
                        String mode,
                        double density,
                        List<Layer> layers,
                        BranchParams branches,
                        String secondaryLeaves,
                        double secondaryFraction,
                        boolean volumeLayers,
                        double leafVerticalScale,
                        Double crownVolumeFraction,
                        String undersideLeaves,
                        double undersideChance
                ) {
                        this(mode, density, layers, branches, secondaryLeaves, secondaryFraction,
                                volumeLayers, leafVerticalScale, crownVolumeFraction, undersideLeaves,
                                undersideChance, 90.0, 1.0,
                                (secondaryLeaves != null && !secondaryLeaves.isBlank())
                                        ? List.of(new WeightedBlock(secondaryLeaves, 1))
                                        : List.of());
                }

                public CanopyParams {
                        if (startAngle <= 0.0) {
                                startAngle = 90.0;
                        }
                        if (squish <= 0.0) {
                                squish = 1.0;
                        }
                        if (secondaryLeafList == null) {
                                secondaryLeafList = (secondaryLeaves != null && !secondaryLeaves.isBlank())
                                        ? List.of(new WeightedBlock(secondaryLeaves, 1))
                                        : List.of();
                        }
                }

                public record Layer(int yOffset, double radius) {}
                public record WeightedBlock(String block, int weight) {}
        }

        public record BranchParams(
                int count,
                double minLength,
                double maxLength,
                String lengthFn,
                Map<String, Double> lengthParams,
                double minElevation,
                double maxElevation,
                double spacing,
                double startHeight,
                int clusterRadius,
                String clusterMode,
                double clusterDensity,
                String probFn,
                Map<String, Double> probParams,
                SubBranchParams subBranches,
                boolean leafStartUp
        ) {
                public BranchParams(
                        int count,
                        double minLength,
                        double maxLength,
                        String lengthFn,
                        Map<String, Double> lengthParams,
                        double minElevation,
                        double maxElevation,
                        double spacing,
                        double startHeight,
                        int clusterRadius,
                        String clusterMode,
                        double clusterDensity,
                        String probFn,
                        Map<String, Double> probParams,
                        SubBranchParams subBranches
                ) {
                        this(count, minLength, maxLength, lengthFn, lengthParams,
                                minElevation, maxElevation, spacing, startHeight,
                                clusterRadius, clusterMode, clusterDensity, probFn, probParams,
                                subBranches, false);
                }

                public record SubBranchParams(
                        int count,
                        double pitchDelta,
                        double yawDelta,
                        double lengthScale,
                        int clusterRadius,
                        String clusterMode,
                        double clusterDensity
                ) {}
        }

    public int effectiveSalt() {
        return salt >= 0 ? salt : (id.hashCode() & 0x7fffffff) % 1_000_000;
    }

    public int getPlacementWeight() {
        return weight;
    }

    public boolean allowsBiome(String biomeKey) {
        if (biomes == null || biomes.isEmpty()) return true;
        if (biomeKey == null || biomeKey.isBlank()) return false;
        String norm = biomeKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (matchesBiomeEntry(norm)) {
            return true;
        }
        for (String fallback : leafCustomBiomeEquivalents(norm)) {
            if (matchesBiomeEntry(fallback)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBiomeEntry(String norm) {
        String withNs = norm.contains(":") ? norm : "minecraft:" + norm;
        String bare = norm.contains(":") ? norm.substring(norm.indexOf(':') + 1) : norm;
        return biomes.contains(norm)
                || biomes.contains(withNs)
                || biomes.contains(bare)
                || biomes.contains("leaf:" + bare);
    }

    /**
     * Maps custom {@code leaf:*} worldgen biomes ({@code datapacks/leaf-worldgen}) to their
     * underlying vanilla biome families so sapling planting, leaf-decay sapling drops, and
     * procedural worldgen work seamlessly across both vanilla and {@code leaf:*} biomes.
     */
    public static List<String> leafCustomBiomeEquivalents(String normalizedBiome) {
        if (normalizedBiome == null || normalizedBiome.isEmpty()) {
            return List.of();
        }
        String bare = normalizedBiome.contains(":")
                ? normalizedBiome.substring(normalizedBiome.indexOf(':') + 1)
                : normalizedBiome;
        if (bare.startsWith("dark_forest_")) {
            return List.of("minecraft:dark_forest");
        }
        if (bare.equals("maple_forest") || bare.startsWith("maple_forest_")) {
            return List.of("minecraft:birch_forest", "minecraft:old_growth_birch_forest");
        }
        if (bare.startsWith("taiga_")) {
            return List.of("minecraft:taiga");
        }
        if (bare.startsWith("forest_")) {
            return List.of("minecraft:forest");
        }
        if (bare.startsWith("rainbow_forest_") || bare.equals("lavender_fields")) {
            return List.of("minecraft:flower_forest");
        }
        return switch (bare) {
            case "jacaranda_grove" -> List.of("minecraft:cherry_grove");
            case "redwood_grove" -> List.of(
                    "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga");
            case "boreal_shield" -> List.of("minecraft:snowy_taiga");
            case "bayou" -> List.of("minecraft:mangrove_swamp", "minecraft:swamp");
            case "alpine_meadow" -> List.of("minecraft:meadow");
            default -> List.of();
        };
    }

    /**
     * True when this species explicitly targets {@code biomeKey} for worldgen or
     * vanilla replacement (requires a non-empty {@link #biomes()} list so child
     * helper species with empty biome sets do not match every biome).
     */
    public boolean allowsWorldgenBiome(String biomeKey) {
        return biomes != null && !biomes.isEmpty() && allowsBiome(biomeKey);
    }

    /**
     * True when this species carries only vanilla {@code feature-trees} worldgen
     * entries and no custom structure variants, procedural definition, or group
     * children of its own.
     */
    public boolean isFeatureTreesOnly() {
        return featureConfig != null && featureConfig.trees() != null && !featureConfig.trees().isEmpty()
                && (variants == null || variants.isEmpty())
                && (trees == null || trees.isEmpty());
    }

    public List<String> variantLocations() {
        return variants.stream().map(TreeVariant::location).toList();
    }
}
