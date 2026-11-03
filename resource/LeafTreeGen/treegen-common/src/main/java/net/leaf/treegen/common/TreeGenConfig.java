package net.leaf.treegen.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Platform-agnostic configuration parser.
 */
public final class TreeGenConfig {

    public enum GenerationMode {
        DATAPACK,
        PROCEDURAL,
        BOTH,
        NONE
    }

    private final Map<String, TreeSpecies> species;
    private final String packName;
    private final String packDescription;
    private final GenerationMode mode;
    private final double timeAllocationMs;
    private final int maxBlocksPerTick;
    private boolean datapackGenerateEnabled = true;
    /**
     * Global chance (0..1) that a decaying or broken custom-tree leaf drops a
     * species-tagged sapling. Defaults to roughly the vanilla sapling drop rate.
     */
    private double saplingDropChance = 0.05;
    /**
     * Species ids (lowercased) that are permitted to take root on or in water.
     * Every other species is rejected on liquid/waterlogged blocks. Defaults to
     * the swamp species.
     */
    private Set<String> waterGrowthTrees = new HashSet<>(Set.of("swamp"));

    public TreeGenConfig(Map<String, TreeSpecies> species, String packName, String packDescription, GenerationMode mode, double timeAllocationMs, int maxBlocksPerTick) {
        this.species = species;
        this.packName = packName;
        this.packDescription = packDescription;
        this.mode = mode;
        this.timeAllocationMs = timeAllocationMs;
        this.maxBlocksPerTick = maxBlocksPerTick;
    }

    public TreeGenConfig(Map<String, TreeSpecies> species, String packName, String packDescription, GenerationMode mode, double timeAllocationMs) {
        this(species, packName, packDescription, mode, timeAllocationMs, 1000);
    }

    public TreeGenConfig(Map<String, TreeSpecies> species, String packName, String packDescription, GenerationMode mode) {
        this(species, packName, packDescription, mode, 5.0, 1000);
    }

    public Map<String, TreeSpecies> species() { return species; }
    public String packName() { return packName; }
    public String packDescription() { return packDescription; }
    public GenerationMode mode() { return mode; }
    public double timeAllocationMs() { return timeAllocationMs; }
    public int maxBlocksPerTick() { return maxBlocksPerTick; }
    public boolean datapackGenerateEnabled() { return datapackGenerateEnabled; }
    public void setDatapackGenerateEnabled(boolean datapackGenerateEnabled) { this.datapackGenerateEnabled = datapackGenerateEnabled; }
    public double saplingDropChance() { return saplingDropChance; }
    public void setSaplingDropChance(double saplingDropChance) { this.saplingDropChance = saplingDropChance; }
    public Set<String> waterGrowthTrees() { return waterGrowthTrees; }
    public void setWaterGrowthTrees(Set<String> waterGrowthTrees) { this.waterGrowthTrees = waterGrowthTrees; }

    /**
     * True if the given tree species is allowed to grow on/in water (e.g. swamp trees).
     */
    public boolean allowsWaterGrowth(String speciesId) {
        if (speciesId == null) return false;
        return waterGrowthTrees.contains(speciesId.toLowerCase(Locale.ROOT));
    }

    public boolean generateDatapack() {
        return datapackGenerateEnabled && (mode == GenerationMode.DATAPACK || mode == GenerationMode.BOTH);
    }

    public boolean useProcedural() {
        return mode == GenerationMode.PROCEDURAL || mode == GenerationMode.BOTH;
    }

    /**
     * True if any configured species both replaces vanilla trees and participates
     * in worldgen. Such species require the vanilla {@code placed_feature}
     * overrides to be written to a datapack so the trees they replace never spawn.
     */
    public boolean hasVanillaSuppression() {
        for (TreeSpecies s : species.values()) {
            if (s.worldgen() && s.replaceVanilla()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if a datapack must be written at all. The full datapack is needed in
     * DATAPACK/BOTH mode; in PROCEDURAL mode we still need a suppression-only
     * datapack when any species replaces vanilla trees (placement itself is then
     * handled at runtime). Returns false when {@code datapack.generate} is false.
     */
    public boolean needsDatapack() {
        return datapackGenerateEnabled && (generateDatapack() || (useProcedural() && hasVanillaSuppression()));
    }

    /**
     * True when the datapack should contain only the vanilla suppression overrides
     * (no baked structures / template pools), i.e. PROCEDURAL mode where the plugin
     * places the trees itself at runtime.
     */
    public boolean suppressionOnly() {
        return !generateDatapack() && useProcedural() && hasVanillaSuppression();
    }

    public TreeSpecies get(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        TreeSpecies found = species.get(key);
        if (found == null) found = species.get(key.replace('_', '-'));
        if (found == null) found = species.get(key.replace('-', '_'));
        return found;
    }

    public List<String> ids() {
        return List.copyOf(species.keySet());
    }

    /**
     * Helper to load from RtpYamlConfig.
     */
    public static TreeGenConfig fromYaml(io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml, TreeRegistry registry, Platform platform) {
        Map<String, TreeSpecies> registered = registry.getSpeciesMap();

        try {
            yaml.loadWithComments();
        } catch (Exception e) {
             platform.logger().severe("CRITICAL: Failed to load config.yml: " + e.getMessage());
        }

        String packName = yaml.getString("datapack.name", "leaf-treegen-generated");
        String packDescription = yaml.getString("datapack.description", "LeafTreeGen generated worldgen.");
        boolean datapackGenerate = yaml.getBoolean("datapack.generate", true);

        GenerationMode mode = GenerationMode.DATAPACK;
        String modeStr = yaml.getString("generation-mode", "DATAPACK");
        try {
            mode = GenerationMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}

        double timeAllocationMs = yaml.getDouble("time-allocation-ms", 5.0);
        int maxBlocksPerTick = yaml.getInt("max-blocks-per-tick", 1000);
        double saplingDropChance = yaml.getDouble("sapling-drop-chance", 0.05);

        Set<String> waterGrowthTrees = new HashSet<>(Set.of("swamp"));
        Object waterTreesObj = yaml.get("water-growth-trees");
        if (waterTreesObj instanceof List<?> waterTreesList) {
            waterGrowthTrees = new HashSet<>();
            for (Object t : waterTreesList) waterGrowthTrees.add(t.toString().toLowerCase(Locale.ROOT));
        }

        Map<String, TreeSpecies> speciesMap = new LinkedHashMap<>();

        // Populate with registered species first
        platform.logger().info("Registered species available: " + String.join(", ", registered.keySet()));
        speciesMap.putAll(registered);

        // Placement overrides from config.yml
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection placementRoot = null;
        Object placementObj = yaml.get("placement");
        if (placementObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) {
            placementRoot = (io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) placementObj;
        }

        if (placementRoot != null) {
            for (String id : placementRoot.getKeys(false)) {
                Object val = placementRoot.get(id);
                if (!(val instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection sec)) {
                    continue;
                }

                String key = id.toLowerCase(Locale.ROOT).replace("-", "_");
                TreeSpecies existing = registered.get(key);
                if (existing == null) {
                    // Try hyphenated version
                    String hyphenated = id.toLowerCase(Locale.ROOT).replace("_", "-");
                    existing = registered.get(hyphenated);
                    if (existing != null) {
                        key = hyphenated;
                    }
                }

                if (existing == null) {
                    // Try with original key if normalization failed
                    key = id.toLowerCase(Locale.ROOT);
                    existing = registered.get(key);
                }

                if (existing == null && (sec.contains("trees") || sec.contains("weighted-trees") || sec.contains("weighted_trees"))) {
                    // Create a virtual species if it doesn't exist but has a tree group
                    existing = new TreeSpecies(
                        id.toLowerCase(Locale.ROOT), id, "minecraft", id.toLowerCase(Locale.ROOT),
                        new HashSet<>(), "OAK_SAPLING", true, false, 3, 2, -1, "linear", "surface_structures",
                        new ArrayList<>(), null, new ArrayList<>(), new HashMap<>(), 1
                    );
                    platform.logger().info("Created virtual species for '" + id + "'");
                }

                if (existing == null) {
                    platform.logger().warning("Placement override for unknown species: " + id + ". Key tried: '" + key + "'. Available: " + String.join(", ", registered.keySet()));
                    continue;
                }

                // If it was already in speciesMap (from registered), we are about to override it.
                // We should ensure we use the canonical key from the registry.
                key = existing.id().toLowerCase(Locale.ROOT);
                platform.logger().info("Applying config.yml override for species '" + key + "' (from key '" + id + "')");

                Set<String> biomes = new HashSet<>(existing.biomes());
                List<?> bList = sec.getList("biomes");
                if (bList != null) {
                    biomes = new HashSet<>();
                    for (Object b : bList) biomes.add(normaliseBiome(b.toString()));
                }
                platform.logger().info("  biomes: " + biomes.size());

                boolean worldgen = sec.contains("worldgen") ? sec.getBoolean("worldgen", !biomes.isEmpty()) : (sec.contains("world-gen") ? sec.getBoolean("world-gen", !biomes.isEmpty()) : (sec.contains("world_gen") ? sec.getBoolean("world_gen", !biomes.isEmpty()) : !biomes.isEmpty()));
                if (sec.contains("biomes") && !sec.contains("worldgen") && !sec.contains("world-gen") && !sec.contains("world_gen")) {
                    worldgen = true; // Auto-enable worldgen if biomes are explicitly provided in config.yml
                }
                platform.logger().info("  worldgen: " + worldgen);
                boolean replaceVanilla = sec.contains("replace-vanilla") ? sec.getBoolean("replace-vanilla", existing.replaceVanilla()) : (sec.contains("replace_vanilla") ? sec.getBoolean("replace_vanilla", existing.replaceVanilla()) : existing.replaceVanilla());
                int spacing = sec.getInt("spacing", existing.spacing());
                int separation = sec.getInt("separation", existing.separation());
                int salt = sec.getInt("salt", existing.salt());
                String spreadType = sec.getString("spread-type", existing.spreadType());
                String step = sec.getString("step", existing.step());
                int weight = sec.getInt("weight", existing.weight());

                // Optional vanilla-feature placement (density). When `feature-trees` lists vanilla
                // configured features, the species is placed as a jigsaw feature element wrapping a
                // `minecraft:count` placement modifier (`trees-per-placement`) so a single structure
                // placement scatters multiple trees -> vanilla-like density. Cross-platform datapack.
                TreeSpecies.FeatureConfig featureConfig = existing.featureConfig();
                int treesPerPlacement = sec.getInt("trees-per-placement",
                        sec.getInt("trees_per_placement",
                                featureConfig != null ? featureConfig.treesPerPlacement() : 0));
                List<?> ftList = sec.getList("feature-trees");
                if (ftList == null) ftList = sec.getList("feature_trees");
                if (ftList != null) {
                    List<TreeSpecies.WeightedFeature> fts = new ArrayList<>();
                    for (Object o : ftList) {
                        if (o == null) continue;
                        String s = o.toString().trim();
                        if (s.isEmpty()) continue;
                        int fWeight = 1;
                        int sp = s.lastIndexOf(' ');
                        if (sp > 0) {
                            try {
                                fWeight = Integer.parseInt(s.substring(sp + 1).trim());
                                s = s.substring(0, sp).trim();
                            } catch (NumberFormatException ignored) {}
                        }
                        if (!s.contains(":")) s = "minecraft:" + s;
                        fts.add(new TreeSpecies.WeightedFeature(s, Math.max(1, fWeight)));
                    }
                    if (!fts.isEmpty()) {
                        featureConfig = new TreeSpecies.FeatureConfig(
                                treesPerPlacement > 0 ? treesPerPlacement : 10, fts);
                    }
                } else if (featureConfig != null && treesPerPlacement > 0
                        && treesPerPlacement != featureConfig.treesPerPlacement()) {
                    featureConfig = new TreeSpecies.FeatureConfig(treesPerPlacement, featureConfig.trees());
                }

                Map<String, TreeSpecies.ProceduralParams> treeDefs = new HashMap<>(existing.treeDefinitions() != null ? existing.treeDefinitions() : Map.of());
                Object defsObj = sec.get("definitions");
                if (defsObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection dSec) {
                    for (String defName : dSec.getKeys(false)) {
                        TreeSpecies.ProceduralParams p = treeDefs.get(defName);
                        if (p != null) {
                            treeDefs.put(defName, new TreeSpecies.ProceduralParams(
                                p.trunkBlock(), p.leafBlock(), p.minHeight(), p.maxHeight(), p.profile(),
                                p.trunkWidth(), p.trunkShape(), p.trunkShapeParams(), p.roundTrunk(),
                                p.leanAngle(), p.leanAzimuth(), p.azimuthFn(), p.azimuthParams(),
                                p.curveFn(), p.curveParams(), p.secondaryTrunk(), p.secondaryTrunkStart(),
                                p.secondaryTrunkEnd(), p.canopy(), p.capTrunk(), p.count(), dSec.getInt(defName, p.weight()), p.decorators()
                            ));
                        }
                    }
                }

                List<TreeSpecies.WeightedSpecies> trees = existing.trees();
                Object treesObj = sec.get("trees");
                if (treesObj == null) treesObj = sec.get("weighted-trees");
                if (treesObj == null) treesObj = sec.get("weighted_trees");

                if (treesObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection tSec) {
                    trees = new ArrayList<>();
                    for (String tId : tSec.getKeys(false)) {
                        String resolvedId = tId.toLowerCase(Locale.ROOT);
                        if (!registered.containsKey(resolvedId)) {
                            String alt = resolvedId.replace("_", "-");
                            if (registered.containsKey(alt)) {
                                resolvedId = alt;
                            } else {
                                alt = resolvedId.replace("-", "_");
                                if (registered.containsKey(alt)) {
                                    resolvedId = alt;
                                }
                            }
                        }
                        trees.add(new TreeSpecies.WeightedSpecies(resolvedId, tSec.getInt(tId, 1)));
                    }
                }

                TreeSpecies.GrowthParams growth = existing.growth();
                Object growthObj = sec.get("growth");
                if (growthObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection gSec) {
                    boolean gEnabled = gSec.getBoolean("enabled", growth != null && growth.enabled());
                    long gTime = gSec.contains("total-time")
                            ? gSec.getLong("total-time", 24000L)
                            : (gSec.contains("total_time")
                                    ? gSec.getLong("total_time", 24000L)
                                    : gSec.getLong("totalTime", growth != null && growth.totalTime() > 0 ? growth.totalTime() : 24000L));
                    growth = gEnabled ? new TreeSpecies.GrowthParams(true, gTime) : TreeSpecies.GrowthParams.disabled();
                }

                String resolvedSapling = existing.saplingItem();
                if (sec.contains("sapling-item")) {
                    resolvedSapling = sec.getString("sapling-item", resolvedSapling);
                } else if (sec.contains("sapling_item")) {
                    resolvedSapling = sec.getString("sapling_item", resolvedSapling);
                } else if ((resolvedSapling == null || "OAK_SAPLING".equalsIgnoreCase(resolvedSapling))
                        && trees != null && !trees.isEmpty()) {
                    for (TreeSpecies.WeightedSpecies ws : trees) {
                        String childId = ws.id().split(":", 2)[0].toLowerCase(Locale.ROOT);
                        TreeSpecies child = registered.get(childId);
                        if (child == null) child = registered.get(childId.replace("_", "-"));
                        if (child == null) child = registered.get(childId.replace("-", "_"));
                        if (child != null && child.saplingItem() != null && !child.saplingItem().isBlank()) {
                            resolvedSapling = child.saplingItem();
                            break;
                        }
                    }
                }

                speciesMap.put(key, new TreeSpecies(
                    existing.id(), existing.displayName(), existing.namespace(), existing.group(),
                    biomes, resolvedSapling, worldgen, replaceVanilla, spacing, separation, salt, spreadType, step,
                    existing.variants(), existing.procedural(), trees, treeDefs, weight, growth, featureConfig
                ));
                platform.logger().info("Configured species '" + key + "': worldgen=" + worldgen + ", biomes=" + biomes.size());
            }
        }

        // Only include species that have worldgen enabled AND (at least one biome OR a weighted tree list)
        int sizeBefore = speciesMap.size();
        speciesMap.values().removeIf(s -> {
            boolean hasBiomes = s.biomes() != null && !s.biomes().isEmpty();
            boolean hasTrees = s.trees() != null && !s.trees().isEmpty();
            boolean keep = s.worldgen() && (hasBiomes || hasTrees);
            if (!keep) {
                platform.logger().info("Species '" + s.id() + "' removed: worldgen=" + s.worldgen() + ", hasBiomes=" + hasBiomes + " (" + (s.biomes() != null ? s.biomes().size() : 0) + "), hasTrees=" + hasTrees);
            }
            return !keep;
        });
        int sizeAfter = speciesMap.size();

        platform.logger().info("Configured " + sizeAfter + " active species for generation (removed " + (sizeBefore - sizeAfter) + " inactive).");

        // Second pass: if any species are defined as 'trees' in active species, we MUST keep them too
        // even if they don't have their own biome mapping, so they can be referenced.
        Map<String, TreeSpecies> finalMap = new LinkedHashMap<>(speciesMap);
        boolean added;
        do {
            added = false;
            List<TreeSpecies> current = new ArrayList<>(finalMap.values());
            for (TreeSpecies s : current) {
                if (s.trees() == null) continue;
                for (TreeSpecies.WeightedSpecies ws : s.trees()) {
                    String refId = ws.id().split(":")[0].toLowerCase(Locale.ROOT);
                    if (!finalMap.containsKey(refId) && registered.containsKey(refId)) {
                        platform.logger().info("Re-adding referenced species (deep): " + refId);
                        finalMap.put(refId, registered.get(refId));
                        added = true;
                    }
                }
            }
        } while (added);

        TreeGenConfig config = new TreeGenConfig(finalMap, packName, packDescription, mode, timeAllocationMs, maxBlocksPerTick);
        config.setDatapackGenerateEnabled(datapackGenerate);
        config.setSaplingDropChance(saplingDropChance);
        config.setWaterGrowthTrees(waterGrowthTrees);
        return config;
    }

    /**
     * Helper to load from a generic map (e.g. from snakeyaml or other parsers).
     * @deprecated Use fromYaml for better multi-platform support.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static TreeGenConfig fromMap(Map<String, Object> map, Platform platform) {
        Map<String, TreeSpecies> out = new LinkedHashMap<>();

        Map<String, Object> dp = (Map<String, Object>) map.get("datapack");
        String packName = dp != null ? (String) dp.getOrDefault("name", "leaf-treegen-generated") : "leaf-treegen-generated";
        String packDescription = dp != null ? (String) dp.getOrDefault("description", "LeafTreeGen generated worldgen.") : "LeafTreeGen generated worldgen.";

        GenerationMode mode = GenerationMode.DATAPACK;
        String modeStr = (String) map.get("generation-mode");
        if (modeStr != null) {
            try {
                mode = GenerationMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        Map<String, Object> speciesRoot = (Map<String, Object>) map.get("species");
        if (speciesRoot == null) {
            platform.logger().warning("No 'species' section in config.");
            return new TreeGenConfig(out, packName, packDescription, mode);
        }

        for (Map.Entry<String, Object> entry : speciesRoot.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> sec = (Map<String, Object>) entry.getValue();
            String key = id.toLowerCase(Locale.ROOT);
            String displayName = (String) sec.getOrDefault("display-name", id);
            String namespace = (String) sec.getOrDefault("namespace", "minecraft");
            String group = (String) sec.getOrDefault("group", key);

            Set<String> biomes = new HashSet<>();
            List<String> bList = (List<String>) sec.get("biomes");
            if (bList != null) {
                for (String b : bList) biomes.add(normaliseBiome(b));
            }

            String sapling = (String) sec.getOrDefault("sapling-item", "OAK_SAPLING");
            boolean worldgen = sec.containsKey("worldgen") ? (boolean) sec.get("worldgen") : !biomes.isEmpty();
            boolean replaceVanilla = sec.containsKey("replace-vanilla") ? (boolean) sec.get("replace-vanilla") : (sec.containsKey("replace_vanilla") ? (boolean) sec.get("replace_vanilla") : false);
            int spacing = (int) sec.getOrDefault("spacing", 12);
            int separation = (int) sec.getOrDefault("separation", 6);
            int salt = (int) sec.getOrDefault("salt", -1);
            String spreadType = ((String) sec.getOrDefault("spread-type", "linear")).toLowerCase(Locale.ROOT);
            String step = (String) sec.getOrDefault("step", "surface_structures");
            int weight = (int) sec.getOrDefault("weight", 1);

            List<TreeSpecies.TreeVariant> variants = new ArrayList<>();
            Map<String, Object> variantsMap = (Map<String, Object>) sec.get("variants");
            if (variantsMap != null) {
                for (Map.Entry<String, Object> vEntry : variantsMap.entrySet()) {
                    String vName = vEntry.getKey();
                    Object vVal = vEntry.getValue();
                    String location;
                    int vWeight = 1;
                    if (vVal instanceof Map) {
                        Map<String, Object> vv = (Map<String, Object>) vVal;
                        location = (String) vv.getOrDefault("location", vName);
                        vWeight = (int) vv.getOrDefault("weight", 1);
                    } else if (vVal instanceof Number n) {
                        location = vName;
                        vWeight = n.intValue();
                    } else {
                        location = vVal != null ? vVal.toString() : vName;
                    }
                    if (!location.contains(":")) location = namespace + ":" + group + "/" + location;
                    variants.add(new TreeSpecies.TreeVariant(location, vWeight));
                }
            }

            out.put(key, new TreeSpecies(key, displayName, namespace, group, biomes, sapling,
                    worldgen, replaceVanilla, spacing, separation, salt, spreadType, step, variants, null, null, null, weight));
        }

        return new TreeGenConfig(out, packName, packDescription, mode);
    }

    private static String normaliseBiome(String raw) {
        String b = raw.trim().toLowerCase(Locale.ROOT);
        if (b.isEmpty()) return b;
        return b.contains(":") ? b : "minecraft:" + b;
    }
}
