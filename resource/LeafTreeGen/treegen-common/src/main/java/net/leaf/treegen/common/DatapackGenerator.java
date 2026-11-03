package net.leaf.treegen.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a vanilla worldgen datapack from the configured {@link TreeSpecies}.
 * Platform-agnostic (uses java.nio.file.Path).
 */
public final class DatapackGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Version of the datapack emission logic. Bump this whenever the generated
     * datapack layout/contents change in a way that is not already captured by the
     * config or the platform data version (for example, new placement filters).
     * It is folded into the fingerprint so that deploying a new plugin build forces
     * a one-time regeneration of an otherwise unchanged datapack, ensuring fixes
     * such as the logs/leaves placement filter actually reach existing worlds.
     */
    private static final int GENERATOR_FORMAT_VERSION = 4;
    private static final java.util.regex.Pattern NON_NAMESPACE_CHARS = java.util.regex.Pattern.compile("[^a-z0-9._-]");
    private static final java.util.regex.Pattern NON_PATH_CHARS = java.util.regex.Pattern.compile("[^a-z0-9/._-]");

    private final Platform platform;

    public DatapackGenerator(Platform platform) {
        this.platform = platform;
    }

    /**
     * Removes the generated worldgen datapack directory if it exists (for example when
     * {@code generation-mode} is switched to {@code NONE} or {@code datapack.generate} is disabled).
     *
     * @return {@code true} if an existing datapack directory was removed
     */
    public boolean removeIfPresent(String worldName, TreeGenConfig config) {
        if (config == null) return false;
        Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder == null) return false;
        Path packRoot = worldFolder.resolve("datapacks").resolve(config.packName());
        if (!Files.exists(packRoot)) {
            return false;
        }
        try {
            wipe(packRoot);
            platform.logger().info("Removed generated worldgen datapack '" + config.packName()
                    + "' from world '" + worldName + "' (datapack generation disabled for mode "
                    + config.mode() + ").");
            return true;
        } catch (IOException | UncheckedIOException ex) {
            platform.logger().warning("Failed to remove generated datapack at " + packRoot + ": " + ex.getMessage());
            return false;
        }
    }

    public int generate(String worldName, TreeGenConfig config, TreeRegistry registry) {
        Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder == null) return 0;

        Path packRoot = worldFolder.resolve("datapacks").resolve(config.packName());
        int dataVersion = platform.getDataVersion();

        // Avoid the (expensive) full regeneration -- including NBT baking -- on every
        // startup. We persist a fingerprint of the inputs that determine the datapack
        // contents and skip regeneration when nothing relevant changed.
        String fingerprint = computeFingerprint(config, dataVersion);
        Integer cached = readFingerprintIfUnchanged(packRoot, fingerprint);
        if (cached != null) {
            platform.logger().info("Worldgen datapack '" + config.packName()
                    + "' is up to date (config unchanged); skipping regeneration of " + cached + " species.");
            return cached;
        }

        try {
            wipe(packRoot);
            Files.createDirectories(packRoot);
            writePackMeta(packRoot, config.packDescription());

            int written = 0;
            Map<String, TreeSpecies> activeSpecies = config.species();
            if (activeSpecies.isEmpty()) {
                platform.logger().warning("No species found in config for datapack generation.");
            }

            // In PROCEDURAL mode the plugin places trees itself at runtime, so the
            // datapack must contain ONLY the vanilla suppression overrides (no
            // structure sets / template pools), otherwise vanilla worldgen would
            // place jigsaw structures on top of the runtime trees.
            boolean suppressionOnly = config.suppressionOnly();

            // Procedural models are baked into vanilla structure templates so the
            // pure-datapack worldgen path can place them without any runtime block
            // placement. Reuse a single generator (with config species for group
            // resolution) and the platform's data version for all variants.
            ProceduralGenerator bakeGenerator = new ProceduralGenerator(platform, registry);
            bakeGenerator.setConfigSpecies(activeSpecies);

            for (TreeSpecies species : activeSpecies.values()) {
                if (!species.worldgen()) {
                    platform.logger().info("Species '" + species.id() + "': worldgen disabled; skipping.");
                    continue;
                }
                if (species.biomes().isEmpty()) {
                    platform.logger().warning("Species '" + species.id() + "': no biomes set; skipping.");
                    continue;
                }

                if (suppressionOnly) {
                    // Runtime (PROCEDURAL) mode: emit only the vanilla suppression
                    // overrides for species that replace vanilla trees. The trees
                    // themselves are placed by the plugin at runtime.
                    if (species.replaceVanilla()) {
                        writeSuppression(packRoot, species);
                        platform.logger().info("Species '" + species.id()
                                + "': generated vanilla suppression features (runtime placement).");
                        written++;
                    }
                    continue;
                }

                String ns = species.namespace();
                String id = species.id();
                String group = species.group();
                String poolName = ns + ":" + group + "/" + id;

                // Write structure common parts
                writeStructureCommon(packRoot, species, ns, id, poolName);
                // No need to duplicate into minecraft namespace in the outer loop anymore
                // as writeStructureCommon handles it internally now.

                if (species.featureConfig() != null && species.featureConfig().trees() != null
                        && !species.featureConfig().trees().isEmpty()) {
                    // Vanilla-feature placement: place vanilla minecraft:tree configured features
                    // via this species' jigsaw structure, scattering several per placement so the
                    // biome reaches vanilla-like density (the NBT path is one tree per structure).
                    writeFeaturePool(packRoot, species, ns, id, group, poolName,
                            config.allowsWaterGrowth(species.id()));
                } else if (species.trees() != null && !species.trees().isEmpty()) {
                    writeGroupPool(packRoot, species, ns, id, group, poolName, config, registry,
                            worldName, bakeGenerator, dataVersion);
                } else {
                    List<String> variants = registry.variantsFor(worldName, species);
                    if (variants.isEmpty() && species.procedural() == null && (species.treeDefinitions() == null || species.treeDefinitions().isEmpty())) {
                        platform.logger().warning("Species '" + species.id() + "': no variants, procedural config, or tree definitions found; skipping.");
                        continue;
                    }
                    writeSpeciesPool(packRoot, species, ns, id, group, poolName, variants, bakeGenerator, dataVersion);
                }

                if (species.replaceVanilla()) {
                    writeSuppression(packRoot, species);
                    platform.logger().info("Species '" + species.id() + "': generated vanilla suppression features.");
                }
                written++;
            }

            writeFingerprint(packRoot, fingerprint, written);
            platform.logger().info("Generated worldgen datapack '" + config.packName() + "' with "
                    + written + " species at " + packRoot);
            return written;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to generate datapack at " + packRoot, ex);
        }
    }

    private void writeStructureCommon(Path packRoot, TreeSpecies species, String ns, String id, String poolName) throws IOException {
        // structure_set
        Map<String, Object> structureEntry = new LinkedHashMap<>();
        structureEntry.put("structure", ns + ":" + id);
        structureEntry.put("weight", 1);
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("type", "minecraft:random_spread");
        placement.put("salt", species.effectiveSalt());
        placement.put("spacing", species.spacing());
        placement.put("separation", species.separation());
        placement.put("spread_type", species.spreadType());
        Map<String, Object> structureSet = new LinkedHashMap<>();
        structureSet.put("structures", List.of(structureEntry));
        structureSet.put("placement", placement);
        writeJson(dataFile(packRoot, ns, "worldgen/structure_set/" + id + ".json"), structureSet);
        if (!ns.equals("minecraft")) {
            writeJson(dataFile(packRoot, "minecraft", "worldgen/structure_set/" + id + ".json"), structureSet);
        }

        // structure
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("type", "minecraft:jigsaw");
        structure.put("biomes", "#" + ns + ":has_structure/" + id);
        structure.put("step", species.step());
        structure.put("spawn_overrides", new LinkedHashMap<>());
        structure.put("terrain_adaptation", "none");
        structure.put("start_pool", poolName);
        structure.put("size", 1);
        structure.put("start_height", Map.of("absolute", 0));
        structure.put("project_start_to_heightmap", "WORLD_SURFACE_WG");
        structure.put("max_distance_from_center", 80);
        structure.put("use_expansion_hack", false);
        writeJson(dataFile(packRoot, ns, "worldgen/structure/" + id + ".json"), structure);
        if (!ns.equals("minecraft")) {
            writeJson(dataFile(packRoot, "minecraft", "worldgen/structure/" + id + ".json"), structure);
        }

        // has_structure biome tag: mark non-vanilla custom biomes (e.g. leaf:*) as
        // optional ("required": false) so the datapack loads cleanly on vanilla worlds
        // when the external leaf-worldgen datapack is not installed, while still
        // binding to leaf:* biomes automatically when leaf-worldgen is present.
        List<String> biomes = new ArrayList<>(species.biomes());
        biomes.sort(Comparator.naturalOrder());
        List<Object> tagValues = new ArrayList<>(biomes.size());
        for (String b : biomes) {
            if (b != null && !b.startsWith("minecraft:") && !b.startsWith("#minecraft:") && b.contains(":")) {
                Map<String, Object> optEntry = new LinkedHashMap<>();
                optEntry.put("id", b);
                optEntry.put("required", false);
                tagValues.add(optEntry);
            } else {
                tagValues.add(b);
            }
        }
        writeJson(dataFile(packRoot, ns, "tags/worldgen/biome/has_structure/" + id + ".json"),
                Map.of("values", tagValues));
        if (!ns.equals("minecraft")) {
            writeJson(dataFile(packRoot, "minecraft", "tags/worldgen/biome/has_structure/" + id + ".json"),
                    Map.of("values", tagValues));
        }
    }

    private void writeGroupPool(Path packRoot, TreeSpecies species, String ns, String id, String group, String poolName,
                                TreeGenConfig config, TreeRegistry registry, String worldName,
                                ProceduralGenerator bakeGenerator, int dataVersion) throws IOException {
        // template_pool (Grouped). For each referenced child species we bake any
        // procedural/treeDefinition variants into NBT here too, so the group pool is
        // fully self-contained even when the child species is not independently active.
        List<Map<String, Object>> elements = new ArrayList<>();
        for (TreeSpecies.WeightedSpecies ws : species.trees()) {
            String[] parts = ws.id().split(":", 2);
            String speciesId = parts[0];
            String treeName = parts.length > 1 ? parts[1] : null;

            TreeSpecies child = config.get(speciesId);
            if (child == null) {
                // Try hyphen/underscore normalization
                child = config.get(speciesId.replace("_", "-"));
                if (child == null) child = config.get(speciesId.replace("-", "_"));
            }

            if (child == null) {
                platform.logger().warning("Group '" + id + "' references unknown species '" + speciesId + "'");
                continue;
            }

            List<Map<String, Object>> childElements;
            if (treeName != null) {
                // Specific named tree reference: bake the named definition (honouring count).
                childElements = bakeNamedTreeElements(packRoot, child, treeName, bakeGenerator, dataVersion);
            } else {
                // Entire species reference - bake + flatten all of its variants.
                List<String> variants = registry.variantsFor(worldName, child);
                childElements = bakeAndCollectElements(packRoot, child, variants, bakeGenerator, dataVersion);
            }

            for (Map<String, Object> childEntry : childElements) {
                int baseWeight = ((Number) childEntry.get("weight")).intValue();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("weight", Math.max(1, ws.weight()) * baseWeight);
                entry.put("element", childEntry.get("element"));
                elements.add(entry);
            }
        }

        normalizePoolWeights(elements);
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", poolName);
        pool.put("fallback", "minecraft:empty");
        pool.put("elements", elements);
        writeJson(dataFile(packRoot, ns, "worldgen/template_pool/" + group + "/" + id + ".json"), pool);
    }

    /**
     * Bakes the {@code treeName} definition of {@code child} (honouring its {@code count})
     * and returns the matching pool entries (each with {@code weight}/{@code element}).
     * Falls back to a single direct location reference when the name is not a procedural
     * tree definition.
     */
    private List<Map<String, Object>> bakeNamedTreeElements(Path packRoot, TreeSpecies child, String treeName,
                                                            ProceduralGenerator bakeGenerator, int dataVersion) {
        List<Map<String, Object>> result = new ArrayList<>();
        String ns = child.namespace();
        String group = child.group();
        TreeSpecies.ProceduralParams params =
                child.treeDefinitions() == null ? null : child.treeDefinitions().get(treeName);
        if (params != null) {
            int variantCount = Math.max(1, params.count());
            int perVariantWeight = variantPoolWeight(params.weight(), variantCount);
            for (int i = 0; i < variantCount; i++) {
                String variantName = (variantCount > 1) ? treeName + "_" + (i + 1) : treeName;
                String location = ns + ":" + group + "/" + variantName;
                bakeVariant(packRoot, location, child.id() + ":" + treeName, params, bakeGenerator,
                        variantSeed(child, variantName), dataVersion);
                result.add(poolEntry(location, perVariantWeight));
            }
        } else {
            // Not a procedural definition: reference the location directly (best effort).
            result.add(poolEntry(ns + ":" + group + "/" + treeName, variantPoolWeight(1, 1)));
        }
        return result;
    }

    /**
     * Bakes (when procedural) and collects all pool entries for a species: named
     * {@code treeDefinitions} (honouring {@code count}), explicit {@code variants},
     * a single {@code procedural} block, or pre-existing variant locations.
     */
    private List<Map<String, Object>> bakeAndCollectElements(Path packRoot, TreeSpecies species, List<String> variants,
                                                             ProceduralGenerator bakeGenerator, int dataVersion) {
        List<Map<String, Object>> elements = new ArrayList<>();
        String ns = species.namespace();
        String group = species.group();
        String id = species.id();

        if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
            for (Map.Entry<String, TreeSpecies.ProceduralParams> entry : species.treeDefinitions().entrySet()) {
                String treeName = entry.getKey();
                TreeSpecies.ProceduralParams params = entry.getValue();
                int variantCount = Math.max(1, params.count());
                int perVariantWeight = variantPoolWeight(params.weight(), variantCount);
                for (int i = 0; i < variantCount; i++) {
                    String variantName = (variantCount > 1) ? treeName + "_" + (i + 1) : treeName;
                    String location = ns + ":" + group + "/" + variantName;
                    bakeVariant(packRoot, location, species.id() + ":" + treeName, params, bakeGenerator,
                            variantSeed(species, variantName), dataVersion);
                elements.add(poolEntry(location, perVariantWeight));
                }
            }
        } else if (!species.variants().isEmpty()) {
            for (TreeSpecies.TreeVariant v : species.variants()) {
                elements.add(poolEntry(v.location(), variantPoolWeight(v.weight(), 1)));
            }
        } else if (species.procedural() != null) {
            int variantCount = Math.max(1, species.procedural().count());
            int perVariantWeight = variantPoolWeight(species.procedural().weight(), variantCount);
            for (int i = 0; i < variantCount; i++) {
                String variantName = (variantCount > 1) ? id + "_" + (i + 1) : id;
                String location = ns + ":" + group + "/" + variantName;
                bakeVariant(packRoot, location, species.id(), species.procedural(), bakeGenerator,
                        variantSeed(species, variantName), dataVersion);
                elements.add(poolEntry(location, perVariantWeight));
            }
        } else {
            for (String variant : variants) {
                elements.add(poolEntry(variant, variantPoolWeight(1, 1)));
            }
        }
        return elements;
    }

    /** Per-variant pool weight so that {@code count} variants together carry placement weight {@code weight}. */
    private int variantPoolWeight(int weight, int count) {
        return io.github.dailystruggle.rtp.common.predict.DistributionSampler.spreadPoolWeight(weight, count);
    }

    /**
     * Maximum weight Minecraft accepts for a {@code template_pool} element. Weights are
     * validated against the inclusive range {@code [1, 150]} during registry loading, so
     * the internally-scaled per-variant weights must be normalised back into this range.
     */
    private static final int MAX_POOL_WEIGHT = 150;

    /**
     * Scales every element weight in a pool proportionally so the largest weight fits within
     * {@link #MAX_POOL_WEIGHT}, clamping each result into the inclusive {@code [1, 150]} range
     * Minecraft requires. Relative placement frequencies are preserved as closely as integer
     * rounding allows. No-op when the pool is empty or already in range.
     */
    private void normalizePoolWeights(List<Map<String, Object>> elements) {
        if (elements.isEmpty()) {
            return;
        }
        int[] raw = new int[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            raw[i] = ((Number) elements.get(i).get("weight")).intValue();
        }
        int[] normalized =
                io.github.dailystruggle.rtp.common.predict.DistributionSampler.normalizePoolWeights(
                        raw, MAX_POOL_WEIGHT);
        for (int i = 0; i < elements.size(); i++) {
            elements.get(i).put("weight", normalized[i]);
        }
    }

    /** Builds a single jigsaw {@code single_pool_element} pool entry. */
    private Map<String, Object> poolEntry(String location, int weight) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("element_type", "minecraft:single_pool_element");
        element.put("location", sanitizeLocation(location));
        element.put("processors", "minecraft:empty");
        element.put("projection", "rigid");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("weight", weight);
        entry.put("element", element);
        return entry;
    }

    private void writeSpeciesPool(Path packRoot, TreeSpecies species, String ns, String id, String group, String poolName,
                                  List<String> variants, ProceduralGenerator bakeGenerator, int dataVersion) throws IOException {
        // template_pool: bake + collect all variant entries for this species.
        List<Map<String, Object>> elements =
                bakeAndCollectElements(packRoot, species, variants, bakeGenerator, dataVersion);
        normalizePoolWeights(elements);
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", poolName);
        pool.put("fallback", "minecraft:empty");
        pool.put("elements", elements);
        writeJson(dataFile(packRoot, ns, "worldgen/template_pool/" + group + "/" + id + ".json"), pool);
    }

    /**
     * Writes the jigsaw pool and placed features for a vanilla-feature species. Each entry in
     * {@link TreeSpecies.FeatureConfig#trees()} becomes a generated {@code placed_feature} that
     * wraps the referenced vanilla {@code minecraft:tree} configured feature with the standard
     * tree placement modifiers ({@code count} = {@code treesPerPlacement}, {@code in_square},
     * {@code heightmap}, {@code biome}). The species' start pool selects between those placed
     * features via weighted {@code feature_pool_element}s, so a single structure placement
     * scatters {@code treesPerPlacement} trees - reaching vanilla-like density purely via the
     * datapack, with no custom worldgen feature and no biome JSON edits.
     */
    private void writeFeaturePool(Path packRoot, TreeSpecies species, String ns, String id, String group,
                                  String poolName, boolean allowWaterGrowth) throws IOException {
        TreeSpecies.FeatureConfig fc = species.featureConfig();
        int perPlacement = Math.max(1, fc.treesPerPlacement());

        List<Map<String, Object>> elements = new ArrayList<>();
        for (TreeSpecies.WeightedFeature wf : fc.trees()) {
            String featureId = wf.feature();
            String local = featureId.contains(":") ? featureId.substring(featureId.indexOf(':') + 1) : featureId;
            local = NON_NAMESPACE_CHARS.matcher(local.toLowerCase(java.util.Locale.ROOT)).replaceAll("_");
            String placedPath = group + "/" + id + "/" + local;
            String placedId = ns + ":" + placedPath;

            // placed_feature: the vanilla configured feature plus tree placement modifiers.
            // NOTE: no minecraft:biome modifier here. These placed features are referenced from a
            // structure feature_pool_element, so they are placed during jigsaw structure post-processing
            // rather than as registered biome decorations. The vanilla BiomeFilter (minecraft:biome)
            // resolves the "top" placed feature from the biome's feature lists to biome-check it; for a
            // structure-placed feature that lookup fails, throwing
            // "Tried to biome check an unregistered feature, or a feature that should not restrict the biome".
            List<Object> placement = new ArrayList<>();
            placement.add(Map.of("type", "minecraft:count", "count", perPlacement));
            placement.add(Map.of("type", "minecraft:in_square"));
            placement.add(Map.of("type", "minecraft:heightmap", "heightmap", "WORLD_SURFACE_WG"));
            // Reject placements that land on or in liquid. WORLD_SURFACE_WG snaps the
            // tree to the top of the water column, so without these filters vanilla tree
            // features get scattered on top of oceans, rivers and swamps where vanilla
            // worldgen would never put them. surface_water_depth_filter rejects any
            // location with water above the surface, and the block directly below the
            // placement must additionally be solid ground. Species flagged for water
            // growth (e.g. swamp/mangrove) skip the filters so they keep their amphibious
            // behaviour.
            //
            // The solid check on its own still lets a tree land on top of an already
            // generated tree, because tree trunks (logs) are solid: WORLD_SURFACE_WG
            // snaps the placement to the top of an existing canopy/trunk and the block
            // directly below is a log, which passes a bare "solid" test. To stop trees
            // spawning on top of other trees we additionally reject placements whose
            // supporting block is a log or leaf block.
            if (!allowWaterGrowth) {
                placement.add(Map.of(
                        "type", "minecraft:surface_water_depth_filter",
                        "max_water_depth", 0));
                placement.add(Map.of(
                        "type", "minecraft:block_predicate_filter",
                        "predicate", Map.of(
                                "type", "minecraft:all_of",
                                "predicates", List.of(
                                        Map.of(
                                                "type", "minecraft:solid",
                                                "offset", List.of(0, -1, 0)),
                                        Map.of(
                                                "type", "minecraft:not",
                                                "predicate", Map.of(
                                                        "type", "minecraft:matching_block_tag",
                                                        "tag", "minecraft:logs",
                                                        "offset", List.of(0, -1, 0))),
                                        Map.of(
                                                "type", "minecraft:not",
                                                "predicate", Map.of(
                                                        "type", "minecraft:matching_block_tag",
                                                        "tag", "minecraft:leaves",
                                                        "offset", List.of(0, -1, 0)))))));
            }
            Map<String, Object> placed = new LinkedHashMap<>();
            placed.put("feature", featureId);
            placed.put("placement", placement);
            writeJson(dataFile(packRoot, ns, "worldgen/placed_feature/" + placedPath + ".json"), placed);

            // feature_pool_element referencing the placed feature.
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("element_type", "minecraft:feature_pool_element");
            element.put("feature", placedId);
            element.put("projection", "rigid");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("weight", Math.max(1, wf.weight()));
            entry.put("element", element);
            elements.add(entry);
        }
        normalizePoolWeights(elements);

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", poolName);
        pool.put("fallback", "minecraft:empty");
        pool.put("elements", elements);
        writeJson(dataFile(packRoot, ns, "worldgen/template_pool/" + group + "/" + id + ".json"), pool);
    }

    /**
     * Builds a procedural model for one variant and writes it as a vanilla
     * structure template at the location the template pool references. Failures to
     * build a model are logged but do not abort datapack generation.
     */
    private void bakeVariant(Path packRoot, String location, String modelId,
                             TreeSpecies.ProceduralParams params, ProceduralGenerator bakeGenerator,
                             long seed, int dataVersion) {
        try {
            TreeModel model = bakeGenerator.buildProceduralModel(modelId, params, new java.util.Random(seed));
            if (model == null || model.getBlocks().isEmpty()) {
                platform.logger().warning("Procedural bake produced no blocks for '" + location + "'");
                return;
            }
            StructureNbtWriter.write(nbtFileFor(packRoot, location), model.getBlocks(), dataVersion);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to bake structure for '" + location + "'", ex);
        } catch (RuntimeException ex) {
            platform.logger().warning("Failed to bake structure for '" + location + "': " + ex.getMessage());
        }
    }

    /** Resolves the {@code .nbt} file for a structure location, matching {@link #sanitizeLocation}. */
    private static Path nbtFileFor(Path packRoot, String location) {
        String sanitized = sanitizeLocation(location);
        int colon = sanitized.indexOf(':');
        String ns = sanitized.substring(0, colon);
        String path = sanitized.substring(colon + 1);
        return dataFile(packRoot, ns, "structure/" + path + ".nbt");
    }

    /** Deterministic per-variant seed so regenerating the datapack is stable. */
    private static long variantSeed(TreeSpecies species, String variantName) {
        return io.github.dailystruggle.rtp.common.predict.DistributionSampler.variantSeed(
                variantName, species.effectiveSalt());
    }

    private void writeSuppression(Path packRoot, TreeSpecies species) throws IOException {
        // To suppress vanilla trees, we need to override the PlacedFeatures in the minecraft namespace.
        // We use an impossible placement filter to ensure they never spawn.

        List<String> targetFeatures = List.of(
            "trees_plains", "trees_flower_forest", "trees_meadow", "trees_birch",
            "trees_birch_and_oak_leaf_litter", "trees_taiga", "trees_snowy", "trees_grove",
            "trees_jungle", "trees_sparse_jungle", "trees_savanna", "trees_windswept_savanna",
            "trees_windswept_hills", "trees_windswept_forest", "trees_cherry", "trees_swamp",
            "trees_mangrove", "trees_water", "trees_badlands",
            "trees_old_growth_pine_taiga", "trees_old_growth_spruce_taiga",
            "oak_checked", "birch_checked", "birch_bees_0002", "spruce_checked",
            "fancy_oak_checked", "dark_oak_checked", "cherry_checked", "mangrove_checked"
        );

        for (String feature : targetFeatures) {
            Map<String, Object> empty = new LinkedHashMap<>();
            // We reference a vanilla feature but with impossible placement
            empty.put("feature", "minecraft:oak");
            empty.put("placement", List.of(
                Map.of("type", "minecraft:block_predicate_filter", "predicate", Map.of("type", "minecraft:matching_blocks", "blocks", List.of("minecraft:bedrock")))
            ));

            writeJson(dataFile(packRoot, "minecraft", "worldgen/placed_feature/" + feature + ".json"), empty);
        }
    }

    private void writePackMeta(Path packRoot, String description) throws IOException {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("description", description);
        pack.put("pack_format", 71);
        pack.put("supported_formats", List.of(61, 81));
        pack.put("min_format", 61);
        pack.put("max_format", 999);
        writeJson(packRoot.resolve("pack.mcmeta"), Map.of("pack", pack));
    }

    /**
     * Sanitizes a resource location so it satisfies Minecraft's {@code [a-z0-9/._-]}
     * path / {@code [a-z0-9._-]} namespace constraints. Authored tree names such as
     * {@code titan_mega_B} contain uppercase letters, which the vanilla registry
     * rejects ("Non [a-z0-9/._-] character in path of location"), aborting world
     * load. These element locations are only used by the worldgen datapack, so
     * lowercasing / replacing illegal characters is safe.
     */
    static String sanitizeLocation(String location) {
        if (location == null) return null;
        int colon = location.indexOf(':');
        String namespace = colon >= 0 ? location.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? location.substring(colon + 1) : location;

        String ns = NON_NAMESPACE_CHARS.matcher(namespace.toLowerCase(java.util.Locale.ROOT))
                .replaceAll("_");
        String p = NON_PATH_CHARS.matcher(path.toLowerCase(java.util.Locale.ROOT))
                .replaceAll("_");
        return ns + ":" + p;
    }

    private static Path dataFile(Path packRoot, String namespace, String relative) {
        return packRoot.resolve("data").resolve(namespace).resolve(relative);
    }

    private static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    /** Name of the marker file (inside the pack root) that stores the inputs fingerprint. */
    private static final String FINGERPRINT_FILE = ".treegen-fingerprint";

    /**
     * Computes a stable fingerprint over every input that influences the generated
     * datapack contents: the configured species, pack name/description, generation
     * mode, water-growth overrides, and the platform data version (NBT format).
     * When this fingerprint is unchanged the datapack on disk is still valid and
     * regeneration can be skipped.
     */
    private static String computeFingerprint(TreeGenConfig config, int dataVersion) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("packName", config.packName());
        input.put("packDescription", config.packDescription());
        input.put("mode", config.mode() == null ? null : config.mode().name());
        input.put("dataVersion", dataVersion);
        input.put("generatorFormatVersion", GENERATOR_FORMAT_VERSION);
        List<String> water = new ArrayList<>(config.waterGrowthTrees());
        water.sort(Comparator.naturalOrder());
        input.put("waterGrowthTrees", water);
        input.put("saplingDropChance", config.saplingDropChance());
        // Species serialized in a deterministic (sorted) key order.
        Map<String, TreeSpecies> species = config.species();
        Map<String, TreeSpecies> sorted = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(species.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            sorted.put(key, species.get(key));
        }
        input.put("species", sorted);

        String json = GSON.toJson(input);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is guaranteed to be available; fall back to the raw JSON.
            return Integer.toHexString(json.hashCode());
        }
    }

    /**
     * Returns the previously written species count when the on-disk datapack was
     * generated from the same fingerprint (and the pack still exists), otherwise
     * {@code null} to indicate regeneration is required.
     */
    private Integer readFingerprintIfUnchanged(Path packRoot, String fingerprint) {
        Path marker = packRoot.resolve(FINGERPRINT_FILE);
        if (!Files.isRegularFile(marker) || !Files.isRegularFile(packRoot.resolve("pack.mcmeta"))) {
            return null;
        }
        try {
            String content = Files.readString(marker, StandardCharsets.UTF_8).trim();
            int sep = content.indexOf(' ');
            String storedHash = sep >= 0 ? content.substring(0, sep) : content;
            if (!fingerprint.equals(storedHash)) {
                return null;
            }
            if (sep < 0) {
                return 0;
            }
            try {
                return Integer.parseInt(content.substring(sep + 1).trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
        } catch (IOException ex) {
            // Unreadable marker: regenerate to be safe.
            return null;
        }
    }

    /** Persists the fingerprint and written-species count alongside the datapack. */
    private void writeFingerprint(Path packRoot, String fingerprint, int written) {
        try {
            Files.writeString(packRoot.resolve(FINGERPRINT_FILE), fingerprint + " " + written,
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            platform.logger().warning("Failed to write datapack fingerprint: " + ex.getMessage());
        }
    }

    private static void wipe(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
