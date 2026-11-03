package net.leaf.treegen.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;

/**
 * Platform-agnostic tree variant and species registry.
 */
public final class TreeRegistry {

    private final Platform platform;
    private final Map<String, TreeSpecies> speciesMap = new HashMap<>();
    private static final Gson GSON = new Gson();

    public TreeRegistry(Platform platform) {
        this.platform = platform;
    }

    public void loadSpecies() {
        extractDefaultSpecies();
        speciesMap.clear();
        File speciesDir = platform.getRootFolder().resolve("species").toFile();
        if (!speciesDir.exists()) {
            speciesDir.mkdirs();
        }

        File[] files = speciesDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) return;

        platform.logger().info("Loading species from " + speciesDir.getAbsolutePath() + " (found " + files.length + " files)");

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                Object jsonElement = GSON.fromJson(reader, Object.class);
                String baseId = file.getName().replace(".json", "");
                if (jsonElement instanceof List<?> list) {
                    Map<String, TreeSpecies.ProceduralParams> definitions = new HashMap<>();
                    for (int i = 0; i < list.size(); i++) {
                        Object entry = list.get(i);
                        if (entry instanceof Map<?, ?> map) {
                            JsonObject obj = GSON.toJsonTree(map).getAsJsonObject();
                            String treeName = obj.has("name") ? obj.get("name").getAsString() : "tree_" + i;
                            // Handle duplicate names in the same file
                            if (definitions.containsKey(treeName)) {
                                treeName = treeName + "_" + i;
                            }
                            definitions.put(treeName, parseProcedural(obj));
                        }
                    }

                    // The species itself can be a container. We use the first entry for default props if needed,
                    // but mainly we want the treeDefinitions populated.
                    if (!list.isEmpty()) {
                        JsonObject first = GSON.toJsonTree(list.get(0)).getAsJsonObject();
                        TreeSpecies parsed = parseSpecies(first, baseId, definitions);
                        // For a container with multiple tree definitions, the first entry is only
                        // used to derive species-level properties (biomes, spacing, etc.). It must
                        // NOT become the species' root procedural model, otherwise buildModel would
                        // always build that single first variant and never randomly select among the
                        // weighted treeDefinitions (e.g. spiral dark oak variants would never spawn).
                        TreeSpecies species = !definitions.isEmpty()
                            ? new TreeSpecies(
                                parsed.id(), parsed.displayName(), parsed.namespace(), parsed.group(),
                                parsed.biomes(), parsed.saplingItem(), parsed.worldgen(), parsed.replaceVanilla(),
                                parsed.spacing(), parsed.separation(), parsed.salt(), parsed.spreadType(), parsed.step(),
                                parsed.variants(), null, parsed.trees(), parsed.treeDefinitions(), parsed.weight(), parsed.growth())
                            : parsed;
                        String speciesId = species.id().toLowerCase(Locale.ROOT);
                        speciesMap.put(speciesId, species);
                        platform.logger().info("Registered species container: " + speciesId + " (" + definitions.size() + " tree variants)");
                    }
                } else if (jsonElement instanceof Map<?, ?> map) {
                    TreeSpecies species = parseSpecies(GSON.toJsonTree(map).getAsJsonObject(), baseId, null);
                    String speciesId = species.id().toLowerCase(Locale.ROOT);
                    speciesMap.put(speciesId, species);
                    platform.logger().info("Registered species: " + speciesId);
                }
            } catch (Exception e) {
                platform.logger().severe("Failed to load species from " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private void extractDefaultSpecies() {
        File speciesDir = platform.getRootFolder().resolve("species").toFile();
        if (!speciesDir.exists()) {
            speciesDir.mkdirs();
        }

        // Extract default config.yml if missing
        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        if (!configFile.exists()) {
            try {
                java.nio.file.Files.createDirectories(platform.getRootFolder());
                try (java.io.InputStream is = TreeRegistry.class.getClassLoader().getResourceAsStream("config.yml")) {
                    if (is != null) {
                        java.nio.file.Files.copy(is, configFile.toPath());
                        platform.logger().info("Extracted default config.yml (Size: " + configFile.length() + ")");
                    } else {
                        try (java.io.InputStream is2 = TreeRegistry.class.getResourceAsStream("/config.yml")) {
                            if (is2 != null) {
                                java.nio.file.Files.copy(is2, configFile.toPath());
                                platform.logger().info("Extracted default config.yml via getResourceAsStream('/') (Size: " + configFile.length() + ")");
                            }
                        }
                    }
                }
            } catch (java.io.IOException e) {
                platform.logger().severe("Failed to extract default config.yml: " + e.getMessage());
            }
        }

        // Enumerate every bundled species/*.json so newly-added species are
        // always extracted (a stale hardcoded list previously dropped them,
        // causing "child species not found" / "buildModel returned null").
        for (String name : bundledSpeciesFiles()) {
            File outFile = new File(speciesDir, name);
            if (!outFile.exists()) {
                java.io.InputStream is = TreeRegistry.class.getResourceAsStream("/species/" + name);
                if (is == null) {
                    is = TreeRegistry.class.getClassLoader().getResourceAsStream("species/" + name);
                }

                if (is != null) {
                    try (java.io.InputStream finalIs = is) {
                        java.nio.file.Files.copy(finalIs, outFile.toPath());
                        platform.logger().info("Extracted default species: " + name);
                    } catch (java.io.IOException e) {
                        platform.logger().severe("Failed to extract default species " + name + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Returns the file names of every bundled {@code species/*.json} resource.
     * <p>
     * The list is discovered dynamically from the running jar (or the exploded
     * resources directory in dev/test) so that adding a new species file never
     * requires touching this class. A static fallback is used only if discovery
     * fails for some reason.
     */
    private List<String> bundledSpeciesFiles() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try {
            java.net.URL src = TreeRegistry.class.getProtectionDomain().getCodeSource().getLocation();
            if (src != null && "file".equalsIgnoreCase(src.getProtocol())) {
                File f = new File(src.toURI());
                if (f.isFile()) {
                    // Packaged jar: enumerate ZIP entries under species/.
                    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f)) {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                        while (en.hasMoreElements()) {
                            java.util.zip.ZipEntry e = en.nextElement();
                            String n = e.getName();
                            if (!e.isDirectory() && n.startsWith("species/")
                                    && n.toLowerCase(Locale.ROOT).endsWith(".json")) {
                                names.add(n.substring("species/".length()));
                            }
                        }
                    }
                } else if (f.isDirectory()) {
                    // Exploded classes dir: resources may live in a sibling root.
                    collectSpeciesFromDir(new File(f, "species"), names);
                    collectSpeciesFromDir(
                        new File(f.getParentFile(), "resources" + File.separator + "main"
                            + File.separator + "species"), names);
                }
            }
        } catch (Exception ignored) {
            // fall through to static fallback
        }

        // Also consult the classpath directly (covers test/dev layouts where the
        // code source does not contain the resources).
        try {
            java.net.URL dir = TreeRegistry.class.getClassLoader().getResource("species");
            if (dir != null && "file".equals(dir.getProtocol())) {
                collectSpeciesFromDir(new File(dir.toURI()), names);
            }
        } catch (Exception ignored) {
            // fall through to static fallback
        }

        if (names.isEmpty()) {
            names.addAll(Arrays.asList(DEFAULT_SPECIES_FILES));
        }
        return new ArrayList<>(names);
    }

    private void collectSpeciesFromDir(File dir, Set<String> names) {
        if (dir == null || !dir.isDirectory()) return;
        File[] arr = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (arr == null) return;
        for (File a : arr) names.add(a.getName());
    }

    /** Fallback list of bundled species, used when the code source uses a non-file URI (e.g. NeoForge union:). */
    private static final String[] DEFAULT_SPECIES_FILES = {
        "ashwood.json", "bamboo-jungle.json", "birch-forest.json", "blightroot.json",
        "cherry-grove.json", "christmas-tree.json", "cloudcap.json",
        "dark-forest-floor-lights.json", "dark-forest-megas.json", "dark-forest-mids.json",
        "dark-forest.json", "embervine.json", "evergreen.json", "forest.json",
        "glacierpine.json", "glowcap.json", "jacaranda-grove.json", "jungle.json",
        "lavatree.json", "maple-birch-giants.json", "meadow-azalea.json",
        "oak-small-mid.json", "old-growth-birch-forest.json", "old-growth-pine-taiga.json",
        "rainbow-grove.json", "savanna.json", "swamp.json", "taiga.json"
    };

    private TreeSpecies parseSpecies(JsonObject json, String defaultId) {
        return parseSpecies(json, defaultId, null);
    }

    private TreeSpecies.BranchParams.SubBranchParams parseSubBranches(JsonObject b) {
        JsonObject s = null;
        if (b.has("sub_branches") && b.get("sub_branches").isJsonObject()) {
            s = b.getAsJsonObject("sub_branches");
        } else if (b.has("sub-branches") && b.get("sub-branches").isJsonObject()) {
            s = b.getAsJsonObject("sub-branches");
        } else if (b.has("subBranches") && b.get("subBranches").isJsonObject()) {
            s = b.getAsJsonObject("subBranches");
        }
        if (s == null) return null;
        int count = s.has("count") ? (s.get("count").isJsonNull() ? 0 : s.get("count").getAsInt()) : 0;
        if (count <= 0) return null;
        return new TreeSpecies.BranchParams.SubBranchParams(
            count,
            s.has("pitch_delta") ? s.get("pitch_delta").getAsDouble() : (s.has("pitch-delta") ? s.get("pitch-delta").getAsDouble() : 0.0),
            s.has("yaw_delta") ? s.get("yaw_delta").getAsDouble() : (s.has("yaw-delta") ? s.get("yaw-delta").getAsDouble() : 45.0),
            s.has("length_scale") ? s.get("length_scale").getAsDouble() : (s.has("length-scale") ? s.get("length-scale").getAsDouble() : 0.5),
            s.has("cluster_radius") ? (s.get("cluster_radius").isJsonNull() ? 1 : s.get("cluster_radius").getAsInt()) : (s.has("cluster-radius") ? s.get("cluster-radius").getAsInt() : 1),
            s.has("cluster_mode") ? (s.get("cluster_mode").isJsonNull() ? "DENSITY" : s.get("cluster_mode").getAsString()) : (s.has("cluster-mode") ? s.get("cluster-mode").getAsString() : "DENSITY"),
            s.has("cluster_density") ? s.get("cluster_density").getAsDouble() : (s.has("cluster-density") ? s.get("cluster-density").getAsDouble() : 1.0)
        );
    }

    private List<TreeSpecies.CanopyParams.WeightedBlock> parseSecondaryLeafList(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return List.of();
        if (el.isJsonPrimitive()) {
            String block = el.getAsString();
            return (block != null && !block.isBlank())
                    ? List.of(new TreeSpecies.CanopyParams.WeightedBlock(block, 1))
                    : List.of();
        }
        if (el.isJsonArray()) {
            List<TreeSpecies.CanopyParams.WeightedBlock> out = new ArrayList<>();
            for (com.google.gson.JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject obj = item.getAsJsonObject();
                    if (obj.has("block") && !obj.get("block").isJsonNull()) {
                        String blk = obj.get("block").getAsString();
                        int w = obj.has("weight") && !obj.get("weight").isJsonNull() ? obj.get("weight").getAsInt() : 1;
                        if (blk != null && !blk.isBlank()) {
                            out.add(new TreeSpecies.CanopyParams.WeightedBlock(blk, Math.max(1, w)));
                        }
                    }
                } else if (item.isJsonPrimitive()) {
                    String blk = item.getAsString();
                    if (blk != null && !blk.isBlank()) {
                        out.add(new TreeSpecies.CanopyParams.WeightedBlock(blk, 1));
                    }
                }
            }
            return out;
        }
        return List.of();
    }

    private TreeSpecies.ProceduralParams parseProcedural(JsonObject p) {
        TreeSpecies.CanopyParams canopy = null;
        if (p.has("canopy")) {
            JsonObject c = p.getAsJsonObject("canopy");
            List<TreeSpecies.CanopyParams.Layer> layers = new ArrayList<>();
            if (c.has("layers")) {
                c.getAsJsonArray("layers").forEach(l -> {
                    JsonObject lo = l.getAsJsonObject();
                    layers.add(new TreeSpecies.CanopyParams.Layer(
                        lo.has("yOffset") ? lo.get("yOffset").getAsInt() : (lo.has("y_offset") ? lo.get("y_offset").getAsInt() : 0),
                        lo.get("radius").getAsDouble()
                    ));
                });
            }

            TreeSpecies.BranchParams branches = null;
            if (c.has("branches")) {
                JsonObject b = c.getAsJsonObject("branches");
                boolean leafStartUp = b.has("leaf_start_up") && !b.get("leaf_start_up").isJsonNull()
                        ? b.get("leaf_start_up").getAsBoolean()
                        : (b.has("leaf-start-up") && !b.get("leaf-start-up").isJsonNull() && b.get("leaf-start-up").getAsBoolean());
                branches = new TreeSpecies.BranchParams(
                    b.has("count") ? (b.get("count").isJsonNull() ? 0 : b.get("count").getAsInt()) : 0,
                    b.has("min-length") ? b.get("min-length").getAsDouble() : (b.has("min_length") ? b.get("min_length").getAsDouble() : (b.has("lengthBase") ? b.get("lengthBase").getAsDouble() : 2.0)),
                    b.has("max-length") ? b.get("max-length").getAsDouble() : (b.has("max_length") ? b.get("max_length").getAsDouble() : (b.has("lengthCrown") ? b.get("lengthCrown").getAsDouble() : 5.0)),
                    b.has("length-fn") ? (b.get("length-fn").isJsonNull() ? "CONSTANT" : b.get("length-fn").getAsString()) : (b.has("length_fn") ? (b.get("length_fn").isJsonNull() ? "CONSTANT" : b.get("length_fn").getAsString()) : "CONSTANT"),
                    parseMap(b.has("length-params") ? b.getAsJsonObject("length-params") : (b.has("length_params") ? b.getAsJsonObject("length_params") : null)),
                    b.has("min-elevation") ? b.get("min-elevation").getAsDouble() : (b.has("min_elevation") ? b.get("min_elevation").getAsDouble() : (b.has("elevation") ? b.get("elevation").getAsDouble() : -20.0)),
                    b.has("max-elevation") ? b.get("max-elevation").getAsDouble() : (b.has("max_elevation") ? b.get("max_elevation").getAsDouble() : (b.has("elevation") ? b.get("elevation").getAsDouble() : 45.0)),
                    b.has("spacing") ? b.get("spacing").getAsDouble() : 1.0,
                    b.has("start-height") ? b.get("start-height").getAsDouble() : (b.has("start_height") ? b.get("start_height").getAsDouble() : 0.6),
                    b.has("cluster_radius") ? (b.get("cluster_radius").isJsonNull() ? 2 : b.get("cluster_radius").getAsInt()) : (b.has("cluster-radius") ? (b.get("cluster-radius").isJsonNull() ? 2 : b.get("cluster-radius").getAsInt()) : 2),
                    b.has("cluster_mode") ? (b.get("cluster_mode").isJsonNull() ? "DENSITY" : b.get("cluster_mode").getAsString()) : (b.has("cluster-mode") ? (b.get("cluster-mode").isJsonNull() ? "DENSITY" : b.get("cluster-mode").getAsString()) : "DENSITY"),
                    b.has("cluster_density") ? b.get("cluster_density").getAsDouble() : (b.has("cluster-density") ? b.get("cluster-density").getAsDouble() : 1.0),
                    b.has("prob_fn") ? (b.get("prob_fn").isJsonNull() ? "TOP_HEAVY" : b.get("prob_fn").getAsString()) : (b.has("prob-fn") ? (b.get("prob-fn").isJsonNull() ? "TOP_HEAVY" : b.get("prob-fn").getAsString()) : "TOP_HEAVY"),
                    parseMap(b.has("prob_params") ? b.getAsJsonObject("prob_params") : (b.has("prob-params") ? b.getAsJsonObject("prob-params") : null)),
                    parseSubBranches(b),
                    leafStartUp
                );
            }

            // Underside (canopy bottom) glow layer. Accept either explicit canopy keys
            // ("underside-leaves"/"underside-chance") or a "canopy_bottom" decorator entry
            // on the definition, which is how the bundled glowcap species expresses it.
            String undersideLeaves = c.has("underside-leaves") ? c.get("underside-leaves").getAsString()
                : (c.has("underside_leaves") ? c.get("underside_leaves").getAsString() : null);
            double undersideChance = c.has("underside-chance") ? c.get("underside-chance").getAsDouble()
                : (c.has("underside_chance") ? c.get("underside_chance").getAsDouble() : 1.0);
            if (undersideLeaves == null && p.has("decorators") && p.get("decorators").isJsonArray()) {
                for (com.google.gson.JsonElement de : p.getAsJsonArray("decorators")) {
                    if (!de.isJsonObject()) continue;
                    JsonObject deco = de.getAsJsonObject();
                    if (deco.has("target") && "canopy_bottom".equals(deco.get("target").getAsString()) && deco.has("block")) {
                        undersideLeaves = deco.get("block").getAsString();
                        undersideChance = deco.has("chance") ? deco.get("chance").getAsDouble() : 1.0;
                        break;
                    }
                }
            }

            com.google.gson.JsonElement secLeavesEl = c.has("secondary-leaves") ? c.get("secondary-leaves")
                    : (c.has("secondary_leaves") ? c.get("secondary_leaves")
                    : (p.has("secondary-leaves") ? p.get("secondary-leaves")
                    : (p.has("secondary_leaves") ? p.get("secondary_leaves") : null)));
            List<TreeSpecies.CanopyParams.WeightedBlock> secondaryLeafList = parseSecondaryLeafList(secLeavesEl);
            String secondaryLeaves = !secondaryLeafList.isEmpty() ? secondaryLeafList.get(0).block() : null;

            double secondaryFraction = c.has("secondary-fraction") ? c.get("secondary-fraction").getAsDouble()
                    : (c.has("secondary_fraction") ? c.get("secondary_fraction").getAsDouble()
                    : (c.has("secondary_leaf_fraction") ? c.get("secondary_leaf_fraction").getAsDouble()
                    : (p.has("secondary-fraction") ? p.get("secondary-fraction").getAsDouble()
                    : (p.has("secondary_fraction") ? p.get("secondary_fraction").getAsDouble()
                    : (p.has("secondary_leaf_fraction") ? p.get("secondary_leaf_fraction").getAsDouble()
                    : (p.has("secondary-leaf-fraction") ? p.get("secondary-leaf-fraction").getAsDouble()
                    : (!secondaryLeafList.isEmpty() ? 0.35 : 0.0)))))));

            double startAngle = c.has("start_angle") && !c.get("start_angle").isJsonNull() ? c.get("start_angle").getAsDouble()
                    : (c.has("start-angle") && !c.get("start-angle").isJsonNull() ? c.get("start-angle").getAsDouble()
                    : (c.has("startAngle") && !c.get("startAngle").isJsonNull() ? c.get("startAngle").getAsDouble() : 90.0));
            double squish = c.has("squish") && !c.get("squish").isJsonNull() ? c.get("squish").getAsDouble() : 1.0;

            canopy = new TreeSpecies.CanopyParams(
                c.has("mode") ? c.get("mode").getAsString() : "DENSITY",
                c.has("density") ? c.get("density").getAsDouble() : (c.has("leafDensity") ? c.get("leafDensity").getAsDouble() : (c.has("leaf_density") ? c.get("leaf_density").getAsDouble() : (c.has("leaf-density") ? c.get("leaf-density").getAsDouble() : 1.0))),
                layers,
                branches,
                secondaryLeaves,
                secondaryFraction,
                c.has("volume-layers") ? c.get("volume-layers").getAsBoolean() : (c.has("volume_layers") ? c.get("volume_layers").getAsBoolean() : true),
                c.has("leaf-vertical-scale") ? c.get("leaf-vertical-scale").getAsDouble() : (c.has("leaf_vertical_scale") ? c.get("leaf_vertical_scale").getAsDouble() : 1.0),
                c.has("crown_volume_fraction") ? (c.get("crown_volume_fraction").isJsonNull() ? null : c.get("crown_volume_fraction").getAsDouble()) : (c.has("crown-volume-fraction") ? (c.get("crown-volume-fraction").isJsonNull() ? null : c.get("crown-volume-fraction").getAsDouble()) : null),
                undersideLeaves,
                undersideChance,
                startAngle,
                squish,
                secondaryLeafList
            );
        }

        // Accent decorators. canopy_bottom is handled separately via the canopy
        // undersideLeaves path (above), so it is excluded here to avoid double placement.
        List<TreeSpecies.Decorator> decorators = new ArrayList<>();
        if (p.has("decorators") && p.get("decorators").isJsonArray()) {
            for (com.google.gson.JsonElement de : p.getAsJsonArray("decorators")) {
                if (!de.isJsonObject()) continue;
                JsonObject deco = de.getAsJsonObject();
                if (!deco.has("block")) continue;
                String target = deco.has("target") ? deco.get("target").getAsString() : "branch_tip";
                if ("canopy_bottom".equals(target)) continue;
                decorators.add(new TreeSpecies.Decorator(
                    target,
                    deco.get("block").getAsString(),
                    deco.has("chance") ? deco.get("chance").getAsDouble() : 0.5,
                    deco.has("axis_aware") ? deco.get("axis_aware").getAsBoolean() : (deco.has("axis-aware") && deco.get("axis-aware").getAsBoolean())
                ));
            }
        }

        return new TreeSpecies.ProceduralParams(
            p.has("trunk-block") ? p.get("trunk-block").getAsString() : (p.has("trunk") ? p.get("trunk").getAsString() : "minecraft:oak_log"),
            p.has("leaf-block") ? p.get("leaf-block").getAsString() : (p.has("leaves") ? p.get("leaves").getAsString() : "minecraft:oak_leaves"),
            p.has("height-min") ? p.get("height-min").getAsInt() : (p.has("height_min") ? p.get("height_min").getAsInt() : (p.has("heightMin") ? p.get("heightMin").getAsInt() : 5)),
            p.has("height-max") ? p.get("height-max").getAsInt() : (p.has("height_max") ? p.get("height_max").getAsInt() : (p.has("heightMax") ? p.get("heightMax").getAsInt() : 10)),
            p.has("profile") ? p.get("profile").getAsString() : "OAK",
            p.has("trunk-width") ? p.get("trunk-width").getAsDouble() : (p.has("trunk_width") ? p.get("trunk_width").getAsDouble() : (p.has("trunkWidth") ? p.get("trunkWidth").getAsDouble() : 1.0)),
            p.has("trunk-shape") ? p.get("trunk-shape").getAsString() : (p.has("trunk_shape") ? p.get("trunk_shape").getAsString() : (p.has("trunkShape") ? p.get("trunkShape").getAsString() : "CONSTANT")),
            parseMap(p.has("trunk-shape-params") ? p.getAsJsonObject("trunk-shape-params") : (p.has("trunk_shape_params") ? p.getAsJsonObject("trunk_shape_params") : null)),
            p.has("round-trunk") ? p.get("round-trunk").getAsBoolean() : (p.has("round_trunk") ? p.get("round_trunk").getAsBoolean() : false),
            p.has("lean-angle") ? p.get("lean-angle").getAsDouble() : (p.has("lean_angle") ? p.get("lean_angle").getAsDouble() : (p.has("leanAngle") ? p.get("leanAngle").getAsDouble() : 0.0)),
            p.has("lean-azimuth") ? p.get("lean-azimuth").getAsDouble() : (p.has("lean_azimuth") ? p.get("lean_azimuth").getAsDouble() : (p.has("leanAzimuth") ? p.get("leanAzimuth").getAsDouble() : 0.0)),
            p.has("azimuth-fn") ? p.get("azimuth-fn").getAsString() : (p.has("lean_azimuth_fn") ? p.get("lean_azimuth_fn").getAsString() : "CONSTANT"),
            parseMap(p.has("azimuth-params") ? p.getAsJsonObject("azimuth-params") : (p.has("lean_azimuth_params") ? p.getAsJsonObject("lean_azimuth_params") : null)),
            p.has("curve-fn") ? p.get("curve-fn").getAsString() : (p.has("trunk_curve_fn") ? p.get("trunk_curve_fn").getAsString() : "LINEAR"),
            parseMap(p.has("curve-params") ? p.getAsJsonObject("curve-params") : (p.has("trunk_curve_params") ? p.getAsJsonObject("trunk_curve_params") : null)),
            p.has("secondary-trunk") ? p.get("secondary-trunk").getAsString() : (p.has("secondary_trunk") ? p.get("secondary_trunk").getAsString() : null),
            p.has("secondary-trunk-start") ? p.get("secondary-trunk-start").getAsDouble() : (p.has("secondary_trunk_start") ? p.get("secondary_trunk_start").getAsDouble() : 0.5),
            p.has("secondary-trunk-end") ? p.get("secondary-trunk-end").getAsDouble() : (p.has("secondary_trunk_end") ? p.get("secondary_trunk_end").getAsDouble() : 1.0),
            canopy,
            p.has("cap-trunk") ? p.get("cap-trunk").getAsBoolean() : true,
            p.has("count") ? p.get("count").getAsInt() : 1,
            p.has("weight") ? p.get("weight").getAsInt() : 1,
            decorators
        );
    }

    private TreeSpecies parseSpecies(JsonObject json, String defaultId, Map<String, TreeSpecies.ProceduralParams> treeDefinitions) {
        String id = json.has("id") ? json.get("id").getAsString() : defaultId;
        String displayName = json.has("display-name") ? json.get("display-name").getAsString() : (json.has("name") ? json.get("name").getAsString() : id);
        String namespace = json.has("namespace") ? json.get("namespace").getAsString() : "minecraft";
        String group = json.has("group") ? json.get("group").getAsString() : id;

        Set<String> biomes = new HashSet<>();
        if (json.has("biomes")) {
            json.getAsJsonArray("biomes").forEach(b -> biomes.add(b.getAsString()));
        }

        String saplingItem = json.has("sapling-item") ? json.get("sapling-item").getAsString() : "OAK_SAPLING";
        boolean worldgen = json.has("worldgen") ? json.get("worldgen").getAsBoolean() : (json.has("world-gen") ? json.get("world-gen").getAsBoolean() : (json.has("world_gen") ? json.get("world_gen").getAsBoolean() : !biomes.isEmpty()));
        boolean replaceVanilla = json.has("replace-vanilla") ? json.get("replace-vanilla").getAsBoolean() : (json.has("replace_vanilla") ? json.get("replace_vanilla").getAsBoolean() : false);
        int spacing = json.has("spacing") ? json.get("spacing").getAsInt() : 12;
        int separation = json.has("separation") ? json.get("separation").getAsInt() : 6;
        int salt = json.has("salt") ? json.get("salt").getAsInt() : -1;
        String spreadType = json.has("spread-type") ? json.get("spread-type").getAsString() : "linear";
        String step = json.has("step") ? json.get("step").getAsString() : "surface_structures";

        List<TreeSpecies.TreeVariant> variants = new ArrayList<>();
        if (json.has("variants") && json.get("variants").isJsonObject()) {
            JsonObject vObj = json.getAsJsonObject("variants");
            for (String vKey : vObj.keySet()) {
                if (vObj.get(vKey).isJsonObject()) {
                    JsonObject vv = vObj.getAsJsonObject(vKey);
                    variants.add(new TreeSpecies.TreeVariant(vv.get("location").getAsString(), vv.has("weight") ? vv.get("weight").getAsInt() : 1));
                } else {
                    variants.add(new TreeSpecies.TreeVariant(vKey, vObj.get(vKey).getAsInt()));
                }
            }
        }

        TreeSpecies.ProceduralParams procedural = null;
        if (json.has("procedural") || json.has("trunk") || json.has("leaves")) {
            JsonObject p = json.has("procedural") ? json.getAsJsonObject("procedural") : json;
            procedural = parseProcedural(p);
        }

        if (!json.has("sapling-item") && !json.has("sapling_item") && !json.has("sapling")) {
            saplingItem = inferSaplingItem(procedural, treeDefinitions);
        }

        int placementWeight = json.has("weight") ? json.get("weight").getAsInt() : (json.has("count") ? json.get("count").getAsInt() : 1);

        TreeSpecies.GrowthParams growth = parseGrowth(json);

        return new TreeSpecies(id, displayName, namespace, group, biomes, saplingItem, worldgen, replaceVanilla, spacing, separation, salt, spreadType, step, variants, procedural, null, treeDefinitions, placementWeight, growth);
    }

    private static String inferSaplingItem(
            TreeSpecies.ProceduralParams procedural,
            Map<String, TreeSpecies.ProceduralParams> treeDefinitions) {
        TreeSpecies.ProceduralParams p = procedural;
        if (p == null && treeDefinitions != null && !treeDefinitions.isEmpty()) {
            p = treeDefinitions.values().iterator().next();
        }
        if (p == null) {
            return "OAK_SAPLING";
        }
        String combined = ((p.trunkBlock() != null ? p.trunkBlock() : "")
                + " " + (p.leafBlock() != null ? p.leafBlock() : "")
                + " " + (p.profile() != null ? p.profile() : "")).toLowerCase(Locale.ROOT);
        if (combined.contains("cherry")) return "CHERRY_SAPLING";
        if (combined.contains("dark_oak")) return "DARK_OAK_SAPLING";
        if (combined.contains("pale_oak")) return "PALE_OAK_SAPLING";
        if (combined.contains("acacia") || combined.contains("savanna")) return "ACACIA_SAPLING";
        if (combined.contains("birch")) return "BIRCH_SAPLING";
        if (combined.contains("spruce") || combined.contains("pine") || combined.contains("conifer")) return "SPRUCE_SAPLING";
        if (combined.contains("jungle")) return "JUNGLE_SAPLING";
        if (combined.contains("mangrove")) return "MANGROVE_PROPAGULE";
        if (combined.contains("flowering_azalea")) return "FLOWERING_AZALEA";
        if (combined.contains("azalea")) return "AZALEA";
        return "OAK_SAPLING";
    }

    /**
     * Parse the optional {@code growth} block. The admin authors only {@code enabled} and
     * {@code total-time} (ticks); stages are derived later by {@link GrowthPlanner}. Missing
     * block -> growth disabled (instant placement, the historical behavior).
     */
    private TreeSpecies.GrowthParams parseGrowth(JsonObject json) {
        if (!json.has("growth") || !json.get("growth").isJsonObject()) {
            return TreeSpecies.GrowthParams.disabled();
        }
        JsonObject g = json.getAsJsonObject("growth");
        boolean enabled = g.has("enabled") && g.get("enabled").getAsBoolean();
        if (!enabled) {
            return TreeSpecies.GrowthParams.disabled();
        }
        long totalTime = g.has("total-time") ? g.get("total-time").getAsLong()
                : (g.has("total_time") ? g.get("total_time").getAsLong()
                : (g.has("totalTime") ? g.get("totalTime").getAsLong() : 24000L));
        return new TreeSpecies.GrowthParams(true, totalTime);
    }

    private Map<String, Double> parseMap(JsonObject obj) {
        if (obj == null) return new HashMap<>();
        Map<String, Double> out = new HashMap<>();
        if (obj != null) {
            for (String key : obj.keySet()) {
                if (obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isNumber()) {
                    out.put(key, obj.get(key).getAsDouble());
                }
            }
        }
        return out;
    }

    public Map<String, TreeSpecies> getSpeciesMap() {
        return new HashMap<>(speciesMap);
    }

    public List<String> variantsFor(String worldName, TreeSpecies species) {
        return variantsFor(worldName, species, new HashSet<>());
    }

    private List<String> variantsFor(String worldName, TreeSpecies species, Set<String> visited) {
        if (species == null) return List.of();
        if (species.variants() != null && !species.variants().isEmpty()) {
            return species.variantLocations();
        }
        List<String> out = new ArrayList<>();
        Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder != null) {
            File datapacks = worldFolder.resolve("datapacks").toFile();
            File[] packs = datapacks.listFiles(File::isDirectory);
            if (packs != null) {
                for (File pack : packs) {
                    File groupDir = new File(pack, "data/" + species.namespace() + "/structure/" + species.group());
                    File[] nbts = groupDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".nbt"));
                    if (nbts == null) continue;

                    for (File nbt : nbts) {
                        String base = nbt.getName().substring(0, nbt.getName().length() - ".nbt".length());
                        String key = species.namespace() + ":" + species.group() + "/" + base;
                        if (!out.contains(key)) out.add(key);
                    }
                }
            }
        }

        // Group species (e.g. plains -> oak-small-mid, snowy_taiga -> evergreen + christmas-tree)
        // bake their .nbt files under the child species's structure/<child.group>/ directory.
        if (out.isEmpty() && species.trees() != null && !species.trees().isEmpty()
                && visited.add(species.id().toLowerCase(Locale.ROOT))) {
            for (TreeSpecies.WeightedSpecies ws : species.trees()) {
                String[] parts = ws.id().split(":", 2);
                String childKey = parts[0].toLowerCase(Locale.ROOT);
                String treeName = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : null;
                TreeSpecies child = speciesMap.get(childKey);
                if (child == null) child = speciesMap.get(childKey.replace("_", "-"));
                if (child == null) child = speciesMap.get(childKey.replace("-", "_"));
                if (child == null) continue;
                for (String v : variantsFor(worldName, child, visited)) {
                    if (treeName != null) {
                        String local = v.substring(v.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                        if (!local.equals(treeName) && !local.startsWith(treeName + "_")) {
                            continue;
                        }
                    }
                    if (!out.contains(v)) out.add(v);
                }
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
