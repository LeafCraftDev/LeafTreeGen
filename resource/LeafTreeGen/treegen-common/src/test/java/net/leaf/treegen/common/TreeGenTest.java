package net.leaf.treegen.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

public class TreeGenTest {

    private static final Gson GSON = new Gson();
    private MockPlatform platform;
    private TreeRegistry registry;

    @BeforeEach
    public void setup() {
        platform = new MockPlatform();
        registry = new TreeRegistry(platform);
    }

    @Test
    public void testJsonIngestion() {
        // Demonstrate ingestion of a "flat" JSON schema (Python tool output)
        String jsonStr = "{\n" +
                "  \"name\": \"Spiral Oak\",\n" +
                "  \"trunk\": \"minecraft:dark_oak_log\",\n" +
                "  \"leaves\": \"minecraft:dark_oak_leaves\",\n" +
                "  \"heightMin\": 15,\n" +
                "  \"heightMax\": 25,\n" +
                "  \"trunkWidth\": 2.0,\n" +
                "  \"azimuth-fn\": \"SPIRAL\",\n" +
                "  \"azimuth-params\": {\"step\": 0.1},\n" +
                "  \"canopy\": {\n" +
                "    \"leafDensity\": 0.8,\n" +
                "    \"layers\": [\n" +
                "      {\"y_offset\": 0, \"radius\": 4.0},\n" +
                "      {\"y_offset\": 2, \"radius\": 3.0}\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        JsonObject json = GSON.fromJson(jsonStr, JsonObject.class);

        // TreeRegistry.parseSpecies is private, but loadSpecies calls it.
        // For testing purposes, we can test that it handles the flat fields correctly.
        // Since loadSpecies reads from files, we'll create a temporary file.

        Path speciesDir = platform.getRootFolder().resolve("species");
        speciesDir.toFile().mkdirs();

        java.io.File speciesFile = speciesDir.resolve("spiral_oak.json").toFile();
        try (java.io.FileWriter writer = new java.io.FileWriter(speciesFile)) {
            writer.write(jsonStr);
        } catch (Exception e) {
            Assertions.fail(e);
        }

        registry.loadSpecies();

        TreeSpecies species = registry.getSpeciesMap().get("spiral_oak");
        Assertions.assertNotNull(species, "Species should be loaded and keyed by lowercase name. Keys found: " + registry.getSpeciesMap().keySet());
        Assertions.assertEquals("Spiral Oak", species.displayName());

        TreeSpecies.ProceduralParams proc = species.procedural();
        Assertions.assertNotNull(proc, "Procedural params should be parsed from flat fields");
        Assertions.assertEquals("minecraft:dark_oak_log", proc.trunkBlock());
        Assertions.assertEquals("minecraft:dark_oak_leaves", proc.leafBlock());
        Assertions.assertEquals(15, proc.minHeight());
        Assertions.assertEquals(25, proc.maxHeight());
        Assertions.assertEquals(2.0, proc.trunkWidth());
        Assertions.assertEquals("SPIRAL", proc.azimuthFn());
        Assertions.assertEquals(0.1, proc.azimuthParams().get("step"));

        Assertions.assertNotNull(proc.canopy(), "Canopy should be parsed");
        Assertions.assertEquals(0.8, proc.canopy().density());
        Assertions.assertEquals(2, proc.canopy().layers().size());
        Assertions.assertEquals(4.0, proc.canopy().layers().get(0).radius());
    }

    @Test
    public void testProceduralGeneration() {
        // Test that procedural generation actually places blocks
        TreeSpecies species = new TreeSpecies(
                "test_tree", "Test Tree", "leaf", "test",
                new HashSet<>(), "OAK_SAPLING", true, false, 3, 2, -1, "linear", "surface_structures",
                new ArrayList<>(),
                new TreeSpecies.ProceduralParams(
                        "minecraft:oak_log", "minecraft:oak_leaves", 5, 5, "OAK",
                        1.0, "CONSTANT", new HashMap<>(), false,
                        0.0, 0.0, "CONSTANT", new HashMap<>(),
                        "LINEAR", new HashMap<>(),
                        null, 0.5, 1.0,
                        new TreeSpecies.CanopyParams("DENSITY", 1.0,
                                List.of(new TreeSpecies.CanopyParams.Layer(0, 2.0)),
                                new TreeSpecies.BranchParams(0, 2.0, 5.0, "CONSTANT", new HashMap<>(), -20.0, 45.0, 1.0, 0.6, 2, "DENSITY", 1.0, "TOP_HEAVY", new HashMap<>(), null),
                                null, 0.0, true, 1.0, null, null, 1.0),
                        true, 1, 1, List.of()
                ),
                new ArrayList<>(),
                new HashMap<>(),
                1
        );

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        generator.generate("world", 0, 64, 0, species, new Random(123));

        Assertions.assertFalse(platform.placedBlocks.isEmpty(), "Generator should have placed some blocks");

        // Check for trunk
        boolean hasLog = platform.placedBlocks.values().stream().anyMatch(b -> b.contains("log"));
        Assertions.assertTrue(hasLog, "Should have placed at least one log block");

        // Check for leaves
        boolean hasLeaves = platform.placedBlocks.values().stream().anyMatch(b -> b.contains("leaves"));
        Assertions.assertTrue(hasLeaves, "Should have placed at least one leaf block");
    }

    @Test
    public void testDecoratorsArePlaced() {
        // A species carrying trunk_base + canopy_top decorators (chance 1.0) so the
        // accent blocks are deterministically placed. Verifies the runtime now honors
        // the non-canopy_bottom decorator targets that the Python decorators.py supports.
        List<TreeSpecies.Decorator> decorators = List.of(
                new TreeSpecies.Decorator("trunk_base", "minecraft:brown_mushroom", 1.0, false),
                new TreeSpecies.Decorator("canopy_top", "minecraft:lantern", 1.0, false)
        );
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", "minecraft:oak_leaves", 8, 8, "OAK",
                1.0, "CONSTANT", new HashMap<>(), false,
                0.0, 0.0, "CONSTANT", new HashMap<>(),
                "LINEAR", new HashMap<>(),
                null, 0.5, 1.0,
                new TreeSpecies.CanopyParams("DENSITY", 1.0,
                        List.of(new TreeSpecies.CanopyParams.Layer(7, 2.0)),
                        null, null, 0.0, true, 1.0, null, null, 1.0),
                true, 1, 1, decorators
        );
        TreeSpecies species = new TreeSpecies("decorated", "Decorated", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), params, null, null, 1);

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeModel model = generator.buildModel(species, new Random(7));
        Assertions.assertNotNull(model);

        boolean hasBase = model.getBlocks().values().stream().anyMatch(b -> b.contains("brown_mushroom"));
        Assertions.assertTrue(hasBase, "trunk_base decorator block should be placed around the trunk base");

        boolean hasTop = model.getBlocks().values().stream().anyMatch(b -> b.contains("lantern"));
        Assertions.assertTrue(hasTop, "canopy_top decorator block should be placed atop the canopy");
    }

    @Test
    void testCacheEvictionOnMReuses() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        TreeSpecies species = new TreeSpecies("eviction_test", "Eviction", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, null, 1);

        // N=100, M=2
        TreeCache cache = new TreeCache(100, 2);

        long seed = 12345L;
        int[] genCount = {0};
        TreeCache.GeneratorFunction gen = s -> {
            genCount[0]++;
            return new TreeModel("eviction_test");
        };

        // 1st call: Generate
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(1, genCount[0]);

        // 2nd call: Reuse (this is the 1st reuse, usage becomes 1)
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(1, genCount[0]);

        // 3rd call: Reuse (this is the 2nd reuse, usage becomes 2, it should be removed AFTER returning)
        // Wait, my implementation: cached.uses++ -> if (uses >= maxReuses) remove.
        // So on 2nd call (1st reuse): uses becomes 1.
        // On 3rd call (2nd reuse): uses becomes 2, it is removed.
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(1, genCount[0]);

        // 4th call: Should generate again
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(2, genCount[0]);
    }

    @Test
    void testCacheEfficiency() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        TreeSpecies species = new TreeSpecies("cache_test", "Cache", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, null, 1);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        Random r1 = new Random(100);
        generator.generate("world", 0, 64, 0, species, r1);
        int blockCount1 = platform.placedBlocks.size();

        platform.placedBlocks.clear();
        Random r2 = new Random(100); // Same seed
        generator.generate("world", 10, 64, 10, species, r2);
        int blockCount2 = platform.placedBlocks.size();

        Assertions.assertEquals(blockCount1, blockCount2, "Cache should produce same number of blocks for same seed");
    }

    @Test
    public void testTrunkLogicParity() {
        // Mock a 2.0 width trunk, which should be 2x2 blocks per layer
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", "minecraft:oak_leaves", 3, 3, "OAK",
                2.0, "CONSTANT", new HashMap<>(), false,
                0.0, 0.0, "CONSTANT", new HashMap<>(),
                "LINEAR", new HashMap<>(),
                null, 0.5, 1.0,
                null, true, 1, 1, List.of()
        );
        TreeSpecies species = new TreeSpecies("parity", "Parity", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), params, null, null, 1);

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        // Test buildModel
        TreeModel model = generator.buildModel(species, new Random(42));
        Assertions.assertNotNull(model);
        Assertions.assertFalse(model.getBlocks().isEmpty());

        // Test SegmentedTreeModel
        SegmentedTreeModel segmented = new SegmentedTreeModel(model);
        segmented.place(platform, "world", 0, 64, 0);

        // Layer 0 should be at 0,64,0.
        // Python _square_positions for width=2.0 (even) and cx=0, cz=0:
        // ox = int(floor(0)) - 2//2 + 1 = 0 - 1 + 1 = 0
        // oz = int(floor(0)) - 2//2 + 1 = 0 - 1 + 1 = 0
        // yields (0,0), (0,1), (1,0), (1,1)

        // Let's check what Java currently does
        platform.placedBlocks.keySet().forEach(k -> {
            if (k.contains(",64,")) {
                System.out.println("[DEBUG_LOG] Block at y=64: " + k + " = " + platform.placedBlocks.get(k));
            }
        });

        Assertions.assertTrue(platform.placedBlocks.containsKey("0,64,0"));
        Assertions.assertTrue(platform.placedBlocks.containsKey("0,64,1"));
        Assertions.assertTrue(platform.placedBlocks.containsKey("1,64,0"));
        Assertions.assertTrue(platform.placedBlocks.containsKey("1,64,1"));
        Assertions.assertEquals(4, platform.placedBlocks.keySet().stream().filter(k -> k.contains(",64,")).count(), "Layer 64 should have 4 blocks");

        // Verify wood variant: Layer 64 bottom (y=64) should be wood because y-1 is air
        // Actually trunk starts at y=64. So y=64 is the bottom.
        // It should be minecraft:oak_wood
        Assertions.assertEquals("minecraft:oak_wood", platform.placedBlocks.get("0,64,0"));
    }

    @Test
    public void testTaperedTrunkStaysContiguous() {
        // Regression: birch trees with log/sigmoid trunk shapes used to taper to
        // width 0 in the upper trunk (calculateWidth lacked the max(1, round(..))
        // clamp present in trunk.py width_at), leaving non-contiguous trunks that
        // stopped short of the canopy. Verify every layer gets at least one trunk
        // block for a tapering shape.
        int height = 24;
        for (String shape : new String[]{"LOG", "SIGMOID"}) {
            Map<String, Double> shapeParams = new HashMap<>();
            if (shape.equals("LOG")) {
                shapeParams.put("base", 2.0);
            } else {
                shapeParams.put("steepness", 5.0);
            }
            TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                    "minecraft:birch_log", "minecraft:birch_leaves", height, height, "BIRCH",
                    1.0, shape, shapeParams, false,
                    0.0, 0.0, "CONSTANT", new HashMap<>(),
                    "CONSTANT", new HashMap<>(),
                    null, 0.5, 1.0,
                    null, true, 1, 1, List.of()
            );

            ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
            TreeModel model = generator.buildProceduralModel("birch:" + shape, params, new Random(3103));
            Assertions.assertNotNull(model);

            Set<Integer> trunkLayers = new TreeSet<>();
            for (Map.Entry<TreeModel.BlockPos, String> e : model.getBlocks().entrySet()) {
                String v = e.getValue();
                if (v.contains("_log") || v.contains("_wood")) {
                    trunkLayers.add(e.getKey().y());
                }
            }

            for (int y = 0; y < height; y++) {
                Assertions.assertTrue(trunkLayers.contains(y),
                        shape + " trunk should have a log block at every layer; missing y=" + y
                                + " (present layers: " + trunkLayers + ")");
            }
        }
    }

    @Test
    public void testDarkForestGiantsIngestion() throws Exception {
        // Demonstrate processing of dark-forest-giants.json
        Path giantsJsonPath = Paths.get("src", "main", "resources", "species", "dark-forest-megas.json");
        java.io.File giantsFile = giantsJsonPath.toFile();

        if (!giantsFile.exists()) {
            // Fallback for different working directories
            giantsJsonPath = Paths.get("resource", "LeafTreeGen", "treegen-common", "src", "main", "resources", "species", "dark-forest-megas.json");
            giantsFile = giantsJsonPath.toFile();
        }
        if (!giantsFile.exists()) {
            giantsJsonPath = Paths.get("plugins", "leaf-treegen", "common", "src", "main", "resources", "species", "dark-forest-megas.json");
            giantsFile = giantsJsonPath.toFile();
        }

        Assertions.assertTrue(giantsFile.exists(), "dark-forest-megas.json should exist at " + giantsFile.getAbsolutePath());

        // Copy to species dir for registry to find
        Path speciesDir = platform.getRootFolder().resolve("species");
        speciesDir.toFile().mkdirs();
        java.nio.file.Files.copy(giantsJsonPath, speciesDir.resolve("dark-forest-megas.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        registry.loadSpecies();

        Map<String, TreeSpecies> map = registry.getSpeciesMap();
        Assertions.assertTrue(map.containsKey("dark-forest-megas"), "Should contain dark-forest-megas. Keys found: " + map.keySet());
        TreeSpecies megas = map.get("dark-forest-megas");
        Assertions.assertTrue(megas.treeDefinitions().containsKey("titan_mega_A"), "Should contain titan_mega_A variant");
        Assertions.assertTrue(megas.treeDefinitions().containsKey("spiral_mega_A"), "Should contain spiral_mega_A variant");

        TreeSpecies.ProceduralParams titan0 = megas.treeDefinitions().get("titan_mega_A");
        Assertions.assertEquals("minecraft:dark_oak_log", titan0.trunkBlock());
        // titan has height_min: 55, height_max: 75
        Assertions.assertEquals(55, titan0.minHeight());
        Assertions.assertEquals(75, titan0.maxHeight());

        TreeSpecies.ProceduralParams titan1 = megas.treeDefinitions().get("titan_mega_B");
        Assertions.assertEquals("parabolic", titan1.trunkShape().toLowerCase(java.util.Locale.ROOT));
        Assertions.assertEquals(0.35, titan1.trunkShapeParams().get("peak_offset"));

        TreeSpecies.ProceduralParams spiral3 = megas.treeDefinitions().get("spiral_mega_B");
        Assertions.assertEquals("spiral", spiral3.azimuthFn().toLowerCase(java.util.Locale.ROOT));
        Assertions.assertEquals("sigmoid", spiral3.curveFn().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    public void testAllConfigParsing() throws Exception {
        Path speciesResourcesPath = Paths.get("src", "main", "resources", "species");
        if (!speciesResourcesPath.toFile().exists()) {
            speciesResourcesPath = Paths.get("resource", "LeafTreeGen", "treegen-common", "src", "main", "resources", "species");
        }
        if (!speciesResourcesPath.toFile().exists()) {
            speciesResourcesPath = Paths.get("plugins", "leaf-treegen", "common", "src", "main", "resources", "species");
        }

        Assertions.assertTrue(speciesResourcesPath.toFile().exists(), "Species resources directory should exist");

        java.io.File[] files = speciesResourcesPath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        Assertions.assertNotNull(files);
        Assertions.assertTrue(files.length > 0, "Should have at least some config files");

        Path speciesDir = platform.getRootFolder().resolve("species");
        speciesDir.toFile().mkdirs();

        for (java.io.File file : files) {
            java.nio.file.Files.copy(file.toPath(), speciesDir.resolve(file.getName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        registry.loadSpecies();
        Map<String, TreeSpecies> map = registry.getSpeciesMap();

        Assertions.assertFalse(map.isEmpty(), "Species map should not be empty after loading all configs");

        // Detailed check for a few known ones
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("glowcap")), "Should contain glowcap");
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("ashwood")), "Should contain ashwood");
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("forest")), "Should contain forest (from forest.json). Keys: " + map.keySet());
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("dark-forest-megas")), "Should contain dark-forest-megas");
    }

    @Test
    public void testDefaultSpeciesExtractionRegistersNewSpecies() {
        // Regression: extractDefaultSpecies() used a stale hardcoded list that
        // omitted newly-added species, so loadSpecies() on a fresh install never
        // registered them (e.g. "child species 'oak-small-mid' not found" ->
        // "buildModel returned null for species plains"). Calling loadSpecies()
        // on an empty root folder must now extract and register every bundled
        // species/*.json.
        MockPlatform freshPlatform = new MockPlatform();
        TreeRegistry freshRegistry = new TreeRegistry(freshPlatform);

        // Ensure we exercise the extraction path, not a pre-populated folder.
        Assertions.assertFalse(freshPlatform.getRootFolder().resolve("species").toFile().exists(),
                "Test precondition: species folder should not exist yet");

        freshRegistry.loadSpecies();
        Map<String, TreeSpecies> map = freshRegistry.getSpeciesMap();

        for (String expected : new String[]{
                "oak-small-mid", "meadow-azalea", "bamboo-jungle", "cherry-grove",
                "jacaranda-grove", "maple-birch-giants", "old-growth-birch-forest",
                "old-growth-pine-taiga", "blightroot", "embervine"}) {
            Assertions.assertTrue(map.containsKey(expected),
                    "Species '" + expected + "' should be extracted and registered. Keys: " + map.keySet());
        }
    }

    @Test
    public void testWeightedSelection() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);

        // Define two procedural variants with heavy weight on the second one
        Map<String, TreeSpecies.ProceduralParams> defs = new HashMap<>();
        defs.put("small", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 5, 5, "OAK", 1.0, "CONSTANT", new HashMap<>(),
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, true, 1, 1, List.of()
        ));
        defs.put("massive", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 50, 50, "OAK", 4.0, "CONSTANT", new HashMap<>(),
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, true, 1, 99, List.of()
        ));

        TreeSpecies species = new TreeSpecies("weighted", "Weighted", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, defs, 1);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        int massiveCount = 0;
        int trials = 1000;
        for (int i = 0; i < trials; i++) {
            TreeModel model = generator.buildModel(species, new Random(i));
            if (model.getSpeciesId().contains("massive")) {
                massiveCount++;
            }
        }

        System.out.println("[DEBUG_LOG] Massive count: " + massiveCount + "/" + trials);
        // With 99:1 weight, massive should be selected ~99% of the time.
        // Even with bad luck, it should be > 90% for 1000 trials.
        Assertions.assertTrue(massiveCount > 900, "Massive trees should be heavily weighted. Got: " + massiveCount);
    }

    @Test
    public void testSpiralDarkOakGeneration() {
        // Load species
        registry.loadSpecies();
        TreeSpecies megas = registry.getSpeciesMap().get("dark-forest-megas");
        Assertions.assertNotNull(megas, "dark-forest-megas species should be loaded");

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        // Find spiral variants
        List<String> spiralNames = megas.treeDefinitions().keySet().stream()
                .filter(name -> name.contains("spiral"))
                .toList();

        Assertions.assertFalse(spiralNames.isEmpty(), "Should have at least one spiral variant in dark-forest-megas");

        for (String name : spiralNames) {
            TreeSpecies.ProceduralParams params = megas.treeDefinitions().get(name);
            System.out.println("[DEBUG_LOG] Testing spiral variant: " + name);

            // Generate a few samples
            for (int i = 0; i < 5; i++) {
                TreeModel model = generator.buildProceduralModel("dark-forest-megas:" + name, params, new Random(i));
                Assertions.assertNotNull(model, "Model should not be null for " + name);

                Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
                long logs = blocks.values().stream().filter(b -> b.contains("log")).count();
                long leaves = blocks.values().stream().filter(b -> b.contains("leaves")).count();

                System.out.println("[DEBUG_LOG]   Sample " + i + ": blocks=" + blocks.size() + " logs=" + logs + " leaves=" + leaves);

                Assertions.assertTrue(logs > 0, "Tree " + name + " should have logs");
                Assertions.assertTrue(leaves > 0, "Tree " + name + " should have leaves");

                // Check for spiral lean if it's a spiral tree
                if (params.azimuthFn().equalsIgnoreCase("spiral")) {
                    // Check if the trunk actually moves in a way that suggests a spiral
                    // We can check the offsets of the logs at different heights
                    int maxY = blocks.keySet().stream().mapToInt(TreeModel.BlockPos::y).max().orElse(0);
                    int halfY = maxY / 2;

                    List<TreeModel.BlockPos> lowLogs = blocks.keySet().stream().filter(p -> p.y() == 2 && blocks.get(p).contains("log")).toList();
                    List<TreeModel.BlockPos> midLogs = blocks.keySet().stream().filter(p -> p.y() == halfY && blocks.get(p).contains("log")).toList();
                    List<TreeModel.BlockPos> highLogs = blocks.keySet().stream().filter(p -> p.y() == maxY - 10 && blocks.get(p).contains("log")).toList();

                    if (!lowLogs.isEmpty() && !highLogs.isEmpty()) {
                         // Just verify they aren't all at (0,0) relative to each other if there is a lean angle
                         if (params.leanAngle() > 0) {
                             double lowX = lowLogs.stream().mapToInt(TreeModel.BlockPos::x).average().orElse(0);
                             double lowZ = lowLogs.stream().mapToInt(TreeModel.BlockPos::z).average().orElse(0);
                             double highX = highLogs.stream().mapToInt(TreeModel.BlockPos::x).average().orElse(0);
                             double highZ = highLogs.stream().mapToInt(TreeModel.BlockPos::z).average().orElse(0);

                             double dist = Math.sqrt(Math.pow(highX - lowX, 2) + Math.pow(highZ - lowZ, 2));
                             System.out.println("[DEBUG_LOG]   Lean distance: " + dist);
                             Assertions.assertTrue(dist > 1.0, "Tree " + name + " should have significant lean distance");
                         }
                    }

                    // Spiral dark oaks must be at least 2 blocks wide all the way up.
                    // Inspect the lower trunk region (below the branch start) so we only
                    // measure trunk logs, not canopy/branch logs.
                    int checkTop = Math.max(2, (int) (maxY * 0.3));
                    for (int y = 1; y <= checkTop; y++) {
                        final int yy = y;
                        // Trunk cells may be plain logs or wood variants (exposed ends),
                        // so count both when measuring the trunk footprint.
                        List<TreeModel.BlockPos> rowLogs = blocks.keySet().stream()
                                .filter(p -> p.y() == yy
                                        && (blocks.get(p).contains("log") || blocks.get(p).contains("wood"))).toList();
                        if (rowLogs.isEmpty()) continue;
                        int xSpan = rowLogs.stream().mapToInt(TreeModel.BlockPos::x).max().getAsInt()
                                - rowLogs.stream().mapToInt(TreeModel.BlockPos::x).min().getAsInt() + 1;
                        int zSpan = rowLogs.stream().mapToInt(TreeModel.BlockPos::z).max().getAsInt()
                                - rowLogs.stream().mapToInt(TreeModel.BlockPos::z).min().getAsInt() + 1;
                        Assertions.assertTrue(xSpan >= 2 && zSpan >= 2,
                                "Spiral tree " + name + " trunk should be at least 2 blocks wide at y=" + yy
                                        + " (xSpan=" + xSpan + ", zSpan=" + zSpan + ", sample " + i + ")");
                    }
                }
            }
        }
    }

    @Test
    public void testTitanCanopyAttachedAndWoodConnected() {
        registry.loadSpecies();
        TreeSpecies megas = registry.getSpeciesMap().get("dark-forest-megas");
        Assertions.assertNotNull(megas, "dark-forest-megas species should be loaded");

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        List<String> titanNames = megas.treeDefinitions().keySet().stream()
                .filter(name -> name.contains("titan"))
                .toList();
        Assertions.assertFalse(titanNames.isEmpty(), "Should have at least one titan variant in dark-forest-megas");

        for (String name : titanNames) {
            TreeSpecies.ProceduralParams params = megas.treeDefinitions().get(name);
            for (int i = 0; i < 5; i++) {
                TreeModel model = generator.buildProceduralModel("dark-forest-megas:" + name, params, new Random(i));
                Assertions.assertNotNull(model, "Model should not be null for " + name);
                Map<TreeModel.BlockPos, String> blocks = model.getBlocks();

                // --- Canopy attachment: leaves must not float far above the trunk tip. ---
                int trunkTopY = blocks.entrySet().stream()
                        .filter(e -> e.getValue().contains("log") || e.getValue().contains("wood"))
                        .mapToInt(e -> e.getKey().y()).max().orElse(0);
                List<TreeModel.BlockPos> leaves = blocks.entrySet().stream()
                        .filter(e -> e.getValue().contains("leaves"))
                        .map(Map.Entry::getKey).toList();
                Assertions.assertFalse(leaves.isEmpty(), "Tree " + name + " should have leaves");
                int minLeafY = leaves.stream().mapToInt(TreeModel.BlockPos::y).min().orElse(0);
                Assertions.assertTrue(minLeafY <= trunkTopY + 2,
                        "Tree " + name + " canopy is detached: lowest leaf y=" + minLeafY
                                + " is above trunk tip y=" + trunkTopY + " (sample " + i + ")");

                // --- Wood connectivity: all wood must be 6-connected to the trunk base. ---
                Set<TreeModel.BlockPos> wood = blocks.entrySet().stream()
                        .filter(e -> e.getValue().contains("log") || e.getValue().contains("wood"))
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet());
                TreeModel.BlockPos start = wood.stream()
                        .min(java.util.Comparator.comparingInt(TreeModel.BlockPos::y)).orElseThrow();
                Set<TreeModel.BlockPos> seen = new HashSet<>();
                java.util.ArrayDeque<TreeModel.BlockPos> queue = new java.util.ArrayDeque<>();
                queue.add(start);
                seen.add(start);
                int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
                while (!queue.isEmpty()) {
                    TreeModel.BlockPos p = queue.poll();
                    for (int[] d : dirs) {
                        TreeModel.BlockPos n = new TreeModel.BlockPos(p.x()+d[0], p.y()+d[1], p.z()+d[2]);
                        if (wood.contains(n) && seen.add(n)) queue.add(n);
                    }
                }
                Assertions.assertEquals(wood.size(), seen.size(),
                        "Tree " + name + " has disconnected wood: " + (wood.size() - seen.size())
                                + " of " + wood.size() + " wood blocks not 6-connected to trunk (sample " + i + ")");
            }
        }
    }

    @Test
    public void testAllLeavesWithinVanillaDecayRadius() {
        // Vanilla destroys any leaf whose distance to the nearest log exceeds 6
        // (propagating through up to six orthogonal leaf-to-leaf steps). Wide
        // procedural canopies used to leave leaves stranded beyond that radius, so
        // they decayed prematurely. connectLeavesWithBranches must extend wood to
        // every such leaf; verify the invariant holds for the big titan canopies.
        registry.loadSpecies();
        TreeSpecies megas = registry.getSpeciesMap().get("dark-forest-megas");
        Assertions.assertNotNull(megas, "dark-forest-megas species should be loaded");

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        for (String name : megas.treeDefinitions().keySet()) {
            TreeSpecies.ProceduralParams params = megas.treeDefinitions().get(name);
            for (int i = 0; i < 5; i++) {
                TreeModel model = generator.buildProceduralModel("dark-forest-megas:" + name, params, new Random(i));
                Assertions.assertNotNull(model, "Model should not be null for " + name);
                Map<TreeModel.BlockPos, String> blocks = model.getBlocks();

                Set<TreeModel.BlockPos> wood = new HashSet<>();
                Set<TreeModel.BlockPos> leaves = new HashSet<>();
                for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
                    String v = e.getValue();
                    if (v.contains("log") || v.contains("wood") || v.contains("stem")) {
                        wood.add(e.getKey());
                    } else if (v.contains("leaves")) {
                        leaves.add(e.getKey());
                    }
                }
                if (leaves.isEmpty()) continue;

                // Replicate vanilla's distance BFS: leaves adjacent to a log start at
                // distance 1 and propagate outward, capped at 6.
                Map<TreeModel.BlockPos, Integer> dist = new HashMap<>();
                java.util.ArrayDeque<TreeModel.BlockPos> queue = new java.util.ArrayDeque<>();
                for (TreeModel.BlockPos leaf : leaves) {
                    for (int[] d : dirs) {
                        if (wood.contains(new TreeModel.BlockPos(leaf.x()+d[0], leaf.y()+d[1], leaf.z()+d[2]))) {
                            dist.put(leaf, 1);
                            queue.add(leaf);
                            break;
                        }
                    }
                }
                while (!queue.isEmpty()) {
                    TreeModel.BlockPos cur = queue.poll();
                    int cd = dist.get(cur);
                    if (cd >= 6) continue;
                    for (int[] d : dirs) {
                        TreeModel.BlockPos n = new TreeModel.BlockPos(cur.x()+d[0], cur.y()+d[1], cur.z()+d[2]);
                        if (leaves.contains(n) && !dist.containsKey(n)) {
                            dist.put(n, cd + 1);
                            queue.add(n);
                        }
                    }
                }

                long orphans = leaves.stream().filter(l -> !dist.containsKey(l)).count();
                Assertions.assertEquals(0, orphans,
                        "Tree " + name + " has " + orphans + " leaves beyond vanilla's 6-block decay radius "
                                + "from wood (sample " + i + ")");
            }
        }
    }

    @Test
    public void testDecayBranchesStayHiddenInsideCanopy() {
        // Regression for "logs scattered about the exterior" of large cherry / oak
        // canopies. The leaf-decay connector must run wood close enough to the leaf
        // shell that no leaf decays (orphan count 0), but every log it places must be
        // buried inside the canopy - none may surface on the outer leaves. We exercise
        // the widest procedural variants (cherry-grove, forest oaks, small/mid oaks).
        registry.loadSpecies();
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        String[] focus = {"cherry-grove", "forest", "oak-small-mid"};
        for (String sp : focus) {
            TreeSpecies species = registry.getSpeciesMap().get(sp);
            Assertions.assertNotNull(species, sp + " species should be loaded");
            Map<String, TreeSpecies.ProceduralParams> defs = species.treeDefinitions();
            if (defs == null || defs.isEmpty()) {
                if (species.procedural() != null) {
                    defs = Map.of("(root)", species.procedural());
                } else { continue; }
            }
            for (Map.Entry<String, TreeSpecies.ProceduralParams> de : defs.entrySet()) {
                for (int i = 0; i < 8; i++) {
                    TreeModel model = generator.buildProceduralModel(sp + ":" + de.getKey(), de.getValue(), new Random(i));
                    Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
                    // Snapshot of the wood the connector placed this build.
                    Set<TreeModel.BlockPos> conn = new HashSet<>(generator.lastConnectionWood);

                    Set<TreeModel.BlockPos> wood = new HashSet<>();
                    Set<TreeModel.BlockPos> leavesSet = new HashSet<>();
                    for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
                        String v = e.getValue();
                        if (v.contains("log") || v.contains("wood") || v.contains("stem")) wood.add(e.getKey());
                        else if (v.contains("leaves")) leavesSet.add(e.getKey());
                    }

                    // (a) No connector-placed log may have an air-exposed face.
                    int exposedConn = 0;
                    for (TreeModel.BlockPos p : conn) {
                        for (int[] d : dirs) {
                            if (!blocks.containsKey(new TreeModel.BlockPos(p.x()+d[0], p.y()+d[1], p.z()+d[2]))) {
                                exposedConn++;
                                break;
                            }
                        }
                    }
                    Assertions.assertEquals(0, exposedConn,
                            sp + ":" + de.getKey() + " has " + exposedConn
                                    + " decay-prevention logs exposed on the canopy surface (sample " + i + ")");

                    // (b) No leaf may decay: every leaf must be within vanilla's radius.
                    Map<TreeModel.BlockPos, Integer> dist = new HashMap<>();
                    java.util.ArrayDeque<TreeModel.BlockPos> q = new java.util.ArrayDeque<>();
                    for (TreeModel.BlockPos l : leavesSet) {
                        for (int[] d : dirs) {
                            if (wood.contains(new TreeModel.BlockPos(l.x()+d[0], l.y()+d[1], l.z()+d[2]))) { dist.put(l,1); q.add(l); break; }
                        }
                    }
                    while (!q.isEmpty()) {
                        TreeModel.BlockPos cur = q.poll(); int cd = dist.get(cur);
                        if (cd >= 6) continue;
                        for (int[] d : dirs) {
                            TreeModel.BlockPos n = new TreeModel.BlockPos(cur.x()+d[0], cur.y()+d[1], cur.z()+d[2]);
                            if (leavesSet.contains(n) && !dist.containsKey(n)) { dist.put(n, cd+1); q.add(n); }
                        }
                    }
                    long orphan = leavesSet.stream().filter(l -> !dist.containsKey(l)).count();
                    Assertions.assertEquals(0, orphan,
                            sp + ":" + de.getKey() + " has " + orphan
                                    + " leaves beyond the 6-block decay radius (sample " + i + ")");
                }
            }
        }
    }

    @Test
    // Resolves variant selection directly (no geometry build), so this is fast. The
    // timeout is a cheap safety net in case selection logic ever regresses into a loop.
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testContainerSelectsAllVariants() {
        // Regression test: a container species with multiple tree definitions must
        // randomly select among ALL of them. Previously the first definition leaked
        // into species.procedural(), causing buildModel to always build that single
        // variant (e.g. spiral dark oak variants in dark-forest-megas never spawned).
        registry.loadSpecies();
        TreeSpecies megas = registry.getSpeciesMap().get("dark-forest-megas");
        Assertions.assertNotNull(megas, "dark-forest-megas species should be loaded");
        Assertions.assertNull(megas.procedural(),
                "A multi-definition container must not have a root procedural model");

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        Set<String> selectedDefs = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            // Only the variant *selection* matters here, not the geometry. Resolving the
            // selected id directly (instead of building 2000 large procedural models) keeps
            // this regression test fast while exercising the exact same selection logic.
            String id = generator.selectModelId(megas, new Random(i));
            Assertions.assertNotNull(id, "Selection should resolve a model id (sample " + i + ")");
            String def = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            selectedDefs.add(def);
        }

        // Every defined variant should be reachable, including spiral variants.
        Assertions.assertEquals(megas.treeDefinitions().keySet(), selectedDefs,
                "All container variants should be selectable. Got: " + selectedDefs);
        long spiralSelected = selectedDefs.stream().filter(n -> n.contains("spiral")).count();
        Assertions.assertTrue(spiralSelected > 0, "Spiral dark oak variants must be selectable");
    }

    @Test
    public void testDefinitionOverride() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);

        // Base species with 1:1 weights
        Map<String, TreeSpecies.ProceduralParams> defs = new HashMap<>();
        defs.put("small", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 5, 5, "OAK", 1.0, "CONSTANT", new HashMap<>(),
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, true, 1, 1, List.of()
        ));
        defs.put("massive", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 50, 50, "OAK", 4.0, "CONSTANT", new HashMap<>(),
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, true, 1, 1, List.of()
        ));

        TreeSpecies base = new TreeSpecies("dark_oak", "Dark Oak", "ns", "gp", Set.of("forest"), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, defs, 1);

        // Mock YAML section for override
        // In a real test we'd use the actual RtpYamlSection, but we can test the result of the logic.
        // Let's verify that after the logic in TreeGenConfig, the weights are updated.

        // We simulate the update
        Map<String, TreeSpecies.ProceduralParams> treeDefs = new HashMap<>(base.treeDefinitions());
        TreeSpecies.ProceduralParams p = treeDefs.get("massive");
        treeDefs.put("massive", new TreeSpecies.ProceduralParams(
            p.trunkBlock(), p.leafBlock(), p.minHeight(), p.maxHeight(), p.profile(),
            p.trunkWidth(), p.trunkShape(), p.trunkShapeParams(), p.roundTrunk(),
            p.leanAngle(), p.leanAzimuth(), p.azimuthFn(), p.azimuthParams(),
            p.curveFn(), p.curveParams(), p.secondaryTrunk(), p.secondaryTrunkStart(),
            p.secondaryTrunkEnd(), p.canopy(), p.capTrunk(), p.count(), 100, p.decorators() // Updated weight
        ));

        TreeSpecies overridden = new TreeSpecies(
            base.id(), base.displayName(), base.namespace(), base.group(),
            base.biomes(), base.saplingItem(), base.worldgen(), base.replaceVanilla(), base.spacing(), base.separation(), base.salt(), base.spreadType(), base.step(),
            base.variants(), base.procedural(), base.trees(), treeDefs, base.weight()
        );

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        int massiveCount = 0;
        int trials = 1000;
        for (int i = 0; i < trials; i++) {
            TreeModel model = generator.buildModel(overridden, new Random(i));
            if (model.getSpeciesId().contains("massive")) {
                massiveCount++;
            }
        }

        // With 100:1 weight, massive should be dominant
        Assertions.assertTrue(massiveCount > 950, "Massive trees should be overridden to heavy weight. Got: " + massiveCount);
    }

    @Test
    public void testSelfReferencingConfigGroupDoesNotCycle() {
        // Regression: a config.yml placement group named like an existing JSON
        // species (e.g. placement.forest with "trees: { forest: 100 }") shadows
        // that JSON species in configSpecies. The group's only child "forest"
        // therefore used to resolve back to the group itself, producing
        // "cyclic species reference detected at 'forest'" -> buildModel == null.
        registry.loadSpecies();
        Assertions.assertNotNull(registry.getSpeciesMap().get("forest"),
                "Test precondition: JSON 'forest' species should be registered");

        // Build a config-level group that shadows the JSON 'forest' species and
        // self-references it (mirrors the generated config species).
        TreeSpecies configForest = new TreeSpecies(
                "forest", "Forest", "minecraft", "forest", Set.of("forest"), "OAK_SAPLING",
                true, true, 4, 2, -1, "linear", "surface_structures",
                List.of(), null,
                List.of(new TreeSpecies.WeightedSpecies("forest", 100)),
                new HashMap<>(), 1);

        Map<String, TreeSpecies> configSpecies = new HashMap<>();
        configSpecies.put("forest", configForest);

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        generator.setConfigSpecies(configSpecies);

        for (int i = 0; i < 50; i++) {
            TreeModel model = generator.buildModel(configForest, new Random(i));
            Assertions.assertNotNull(model,
                    "Self-referencing config group 'forest' must resolve to the JSON species, not cycle (seed " + i + ")");
        }
    }

    @Test
    public void testWeightedGroupData() {
        TreeSpecies s = new TreeSpecies("test", "test", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null,
            List.of(new TreeSpecies.WeightedSpecies("forest:oak", 80), new TreeSpecies.WeightedSpecies("birch", 20)), null, 1);

        Assertions.assertEquals(2, s.trees().size());
        Assertions.assertEquals("forest:oak", s.trees().get(0).id());
        Assertions.assertEquals(80, s.trees().get(0).weight());
    }

    @org.junit.jupiter.api.Test
    public void testTreeRotation() {
        TreeModel model = new TreeModel("test");
        model.setBlock(1, 0, 0, "minecraft:oak_log[axis=x]");
        model.setBlock(0, 0, 1, "minecraft:oak_stairs[facing=south]");

        // Rotate 90 degrees clockwise (1 quadrant)
        // (x, z) -> (-z, x)
        // (1, 0) -> (0, 1)
        // (0, 1) -> (-1, 0)
        model.rotate(1);

        org.junit.jupiter.api.Assertions.assertEquals("minecraft:oak_log[axis=z]", model.getBlock(0, 0, 1));
        org.junit.jupiter.api.Assertions.assertEquals("minecraft:oak_stairs[facing=west]", model.getBlock(-1, 0, 0));

        // Rotate another 90 degrees (total 180)
        // (x, z) -> (-z, x)
        // (0, 1) -> (-1, 0)
        // (-1, 0) -> (0, -1)
        model.rotate(1);
        org.junit.jupiter.api.Assertions.assertEquals("minecraft:oak_log[axis=x]", model.getBlock(-1, 0, 0));
        org.junit.jupiter.api.Assertions.assertEquals("minecraft:oak_stairs[facing=north]", model.getBlock(0, 0, -1));
    }

    @Test
    public void testCapTrunkNoAirGapAboveTip() {
        // Regression test for the "off by one" air gap: the cell directly above the
        // trunk apex must be a leaf, not air, so the canopy connects to the trunk.
        registry.loadSpecies();
        TreeSpecies megas = registry.getSpeciesMap().get("dark-forest-megas");
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        for (String name : megas.treeDefinitions().keySet()) {
            TreeSpecies.ProceduralParams params = megas.treeDefinitions().get(name);
            for (int s = 0; s < 8; s++) {
                TreeModel model = generator.buildProceduralModel("x:" + name, params, new Random(s));
                Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
                TreeModel.BlockPos tip = blocks.entrySet().stream()
                        .filter(e -> e.getValue().contains("log") || e.getValue().contains("wood"))
                        .map(Map.Entry::getKey)
                        .max(Comparator.comparingInt(TreeModel.BlockPos::y)).orElseThrow();
                String above = blocks.get(new TreeModel.BlockPos(tip.x(), tip.y() + 1, tip.z()));
                Assertions.assertNotNull(above,
                        "Air gap above trunk tip for " + name + " seed " + s + " at " + tip);
                Assertions.assertTrue(above.contains("leaves"),
                        "Block above trunk tip should be a leaf for " + name + " seed " + s + ", was " + above);
            }
        }
    }

    @Test
    public void testMushroomStemNeverGetsAxisState() {
        // Regression: mushroom_stem has no "axis" block-state property, so emitting
        // "minecraft:mushroom_stem[axis=y]" produced an unparseable block state and
        // "Could not parse data" warnings during placement. The glowcap species uses
        // minecraft:mushroom_stem as its trunk block.
        registry.loadSpecies();
        TreeSpecies glowcap = registry.getSpeciesMap().get("glowcap");
        Assertions.assertNotNull(glowcap, "glowcap species should be loaded");
        Assertions.assertFalse(glowcap.treeDefinitions().isEmpty(),
                "glowcap should have tree definitions");

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        for (Map.Entry<String, TreeSpecies.ProceduralParams> def : glowcap.treeDefinitions().entrySet()) {
            for (int seed = 0; seed < 16; seed++) {
                TreeModel model = generator.buildProceduralModel("glowcap:" + def.getKey(), def.getValue(), new Random(seed));
                for (String block : model.getBlocks().values()) {
                    Assertions.assertFalse(block.startsWith("minecraft:mushroom_stem["),
                            "mushroom_stem must not carry a block-state (e.g. axis), was: " + block
                                    + " (" + def.getKey() + " seed " + seed + ")");
                }
            }
        }
    }

    @Test
    public void testSanitizeLocationProducesValidResourceLocations() {
        // Regression: tree names like "titan_mega_B_5" contain uppercase letters,
        // which the vanilla registry rejects ("Non [a-z0-9/._-] character in path"),
        // aborting world load. sanitizeLocation must lowercase / replace them.
        Assertions.assertEquals("minecraft:dark-forest-megas/titan_mega_b_5",
                DatapackGenerator.sanitizeLocation("minecraft:dark-forest-megas/titan_mega_B_5"));
        Assertions.assertEquals("minecraft:grp/spiral_mega_a",
                DatapackGenerator.sanitizeLocation("minecraft:grp/spiral_mega_A"));
        // No namespace -> defaults to minecraft
        Assertions.assertEquals("minecraft:foo_bar",
                DatapackGenerator.sanitizeLocation("Foo Bar"));
    }

    @Test
    public void testGeneratedTemplatePoolLocationsAreValid() throws Exception {
        // Regression: the worldgen datapack must only emit valid resource locations,
        // otherwise the registry freeze fails with
        // "Unbound values in registry ... template_pool ... titan_mega_B_5".
        registry.loadSpecies();
        TreeSpecies megas = registry.getSpeciesMap().get("dark-forest-megas");
        Assertions.assertNotNull(megas, "dark-forest-megas species should be loaded");

        // Give it a biome so the datapack actually writes its template_pool.
        TreeSpecies placed = new TreeSpecies(
                megas.id(), megas.displayName(), megas.namespace(), megas.group(),
                Set.of("dark_forest"), megas.saplingItem(), true, false,
                megas.spacing(), megas.separation(), megas.salt(), megas.spreadType(), megas.step(),
                megas.variants(), megas.procedural(), megas.trees(), megas.treeDefinitions(), megas.weight());

        Map<String, TreeSpecies> species = new HashMap<>();
        species.put(placed.id(), placed);
        TreeGenConfig config = new TreeGenConfig(species, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.DATAPACK);

        DatapackGenerator generator = new DatapackGenerator(platform);
        generator.generate("world", config, registry);

        Path poolDir = platform.getWorldFolder("world")
                .resolve("datapacks").resolve("leaf-treegen-generated")
                .resolve("data").resolve("minecraft").resolve("worldgen").resolve("template_pool");
        Assertions.assertTrue(java.nio.file.Files.isDirectory(poolDir),
                "template_pool directory should be generated at " + poolDir);

        java.util.regex.Pattern valid = java.util.regex.Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(poolDir)) {
            List<Path> poolFiles = walk.filter(p -> p.toString().endsWith(".json")).toList();
            Assertions.assertFalse(poolFiles.isEmpty(), "Expected at least one template_pool file");
            for (Path p : poolFiles) {
                String content = java.nio.file.Files.readString(p);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"location\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
                while (m.find()) {
                    String loc = m.group(1);
                    Assertions.assertTrue(valid.matcher(loc).matches(),
                            "Invalid resource location '" + loc + "' in " + p);
                }
            }
        }
    }

    @Test
    public void testFeatureTreePlacementForDenseBiome() throws Exception {
        // Density fix: old-growth pine taiga is far denser than the one-tree-per-structure
        // NBT path can reach. When a species declares feature-trees, the datapack places
        // vanilla minecraft:tree configured features through the species' jigsaw structure
        // and scatters trees-per-placement of them per placement via a minecraft:count
        // placement modifier -> vanilla-like density, pure datapack, cross-platform.
        TreeSpecies.FeatureConfig fc = new TreeSpecies.FeatureConfig(10, List.of(
                new TreeSpecies.WeightedFeature("minecraft:mega_pine", 3),
                new TreeSpecies.WeightedFeature("minecraft:pine", 5),
                new TreeSpecies.WeightedFeature("minecraft:spruce", 5)));

        TreeSpecies species = new TreeSpecies(
                "old-growth-pine-taiga", "Old Growth Pine Taiga", "minecraft", "old-growth-pine-taiga",
                Set.of("old_growth_pine_taiga"), "SPRUCE_SAPLING", true, true,
                1, 0, -1, "linear", "surface_structures",
                List.of(), null, null, null, 1,
                TreeSpecies.GrowthParams.disabled(), fc);

        Map<String, TreeSpecies> map = new HashMap<>();
        map.put(species.id(), species);
        TreeGenConfig config = new TreeGenConfig(map, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.DATAPACK);

        DatapackGenerator generator = new DatapackGenerator(platform);
        generator.generate("world", config, registry);

        Path worldgen = platform.getWorldFolder("world")
                .resolve("datapacks").resolve("leaf-treegen-generated")
                .resolve("data").resolve("minecraft").resolve("worldgen");

        // The start pool selects between vanilla features, not baked NBT templates.
        Path pool = worldgen.resolve("template_pool")
                .resolve("old-growth-pine-taiga").resolve("old-growth-pine-taiga.json");
        Assertions.assertTrue(java.nio.file.Files.exists(pool), "feature pool should be generated at " + pool);
        String poolContent = java.nio.file.Files.readString(pool);
        Assertions.assertTrue(poolContent.contains("minecraft:feature_pool_element"),
                "pool should use feature_pool_element entries. Was: " + poolContent);
        Assertions.assertFalse(poolContent.contains("single_pool_element"),
                "feature placement must not bake NBT single_pool_element. Was: " + poolContent);

        // One placed_feature per configured feature, wrapping it with the count modifier.
        Path pine = worldgen.resolve("placed_feature")
                .resolve("old-growth-pine-taiga").resolve("old-growth-pine-taiga").resolve("pine.json");
        Assertions.assertTrue(java.nio.file.Files.exists(pine),
                "placed_feature for pine should exist at " + pine);
        String pineContent = java.nio.file.Files.readString(pine);
        Assertions.assertTrue(pineContent.contains("minecraft:pine"),
                "placed_feature should wrap the vanilla minecraft:pine configured feature. Was: " + pineContent);
        Assertions.assertTrue(pineContent.contains("minecraft:count"),
                "placed_feature should apply a count placement modifier. Was: " + pineContent);
        Assertions.assertTrue(pineContent.contains("\"count\": 10"),
                "count should equal trees-per-placement (10). Was: " + pineContent);
        // Structure-placed features must NOT carry a biome filter: the vanilla BiomeFilter
        // resolves the placed feature from the biome's feature lists to biome-check it, which
        // fails for jigsaw structure feature_pool_element placements and crashes feature
        // placement with "Tried to biome check an unregistered feature".
        Assertions.assertFalse(pineContent.contains("minecraft:biome"),
                "structure-placed feature must not include a minecraft:biome filter. Was: " + pineContent);
        // Trees must not be scattered on top of water: the placed feature must carry a
        // block_predicate_filter requiring solid ground below, matching vanilla behaviour.
        Assertions.assertTrue(pineContent.contains("minecraft:block_predicate_filter")
                        && pineContent.contains("minecraft:solid"),
                "placed_feature should filter out non-solid (water) ground. Was: " + pineContent);
        // Trees must also never be placed on top of water columns: the placed feature
        // must carry a surface_water_depth_filter rejecting any water above the surface.
        Assertions.assertTrue(pineContent.contains("minecraft:surface_water_depth_filter")
                        && pineContent.contains("max_water_depth"),
                "placed_feature should reject placements over water. Was: " + pineContent);
        // Trees must never be placed on top of other trees: WORLD_SURFACE_WG snaps the
        // placement to the top of an existing trunk/canopy, and a bare solid check passes
        // for logs. The supporting block must therefore also be rejected when it is a log
        // or leaf block.
        Assertions.assertTrue(pineContent.contains("minecraft:logs")
                        && pineContent.contains("minecraft:leaves"),
                "placed_feature should reject placements supported by logs/leaves so trees "
                        + "never spawn on top of other trees. Was: " + pineContent);

        // The structure set still drives placement (no NBT baked for this species).
        Path structureSet = worldgen.resolve("structure_set").resolve("old-growth-pine-taiga.json");
        Assertions.assertTrue(java.nio.file.Files.exists(structureSet),
                "structure_set should still be generated to inject the feature placement");
    }

    @Test
    public void testDatapackBakesProceduralNbtVariants() throws Exception {
        // The pure-datapack path must bake N structure templates per tree (N = count)
        // so vanilla worldgen can place them without runtime block placement. glowcap
        // uses mushroom_stem and has count=4 per definition.
        registry.loadSpecies();
        TreeSpecies glowcap = registry.getSpeciesMap().get("glowcap");
        Assertions.assertNotNull(glowcap, "glowcap species should be loaded");

        TreeSpecies placed = new TreeSpecies(
                glowcap.id(), glowcap.displayName(), glowcap.namespace(), glowcap.group(),
                Set.of("lush_caves"), glowcap.saplingItem(), true, false,
                glowcap.spacing(), glowcap.separation(), glowcap.salt(), glowcap.spreadType(), glowcap.step(),
                glowcap.variants(), glowcap.procedural(), glowcap.trees(), glowcap.treeDefinitions(), glowcap.weight());

        Map<String, TreeSpecies> species = new HashMap<>();
        species.put(placed.id(), placed);
        TreeGenConfig config = new TreeGenConfig(species, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.DATAPACK);

        DatapackGenerator generator = new DatapackGenerator(platform);
        generator.generate("world", config, registry);

        Path structureDir = platform.getWorldFolder("world")
                .resolve("datapacks").resolve("leaf-treegen-generated")
                .resolve("data").resolve(glowcap.namespace())
                .resolve("structure").resolve(glowcap.group());
        Assertions.assertTrue(java.nio.file.Files.isDirectory(structureDir),
                "structure directory should be generated at " + structureDir);

        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(structureDir)) {
            List<Path> nbts = walk.filter(p -> p.toString().endsWith(".nbt")).toList();
            Assertions.assertFalse(nbts.isEmpty(), "Expected baked .nbt structure variants");
            for (Path p : nbts) {
                byte[] head = java.nio.file.Files.readAllBytes(p);
                Assertions.assertTrue(head.length > 2
                                && (head[0] & 0xFF) == 0x1f && (head[1] & 0xFF) == 0x8b,
                        "Baked structure should be gzip-compressed: " + p);
            }
        }
    }

    @Test
    public void testDatapackSkipsRegenerationWhenConfigUnchanged() throws Exception {
        // Startup optimization: regenerating the datapack (and re-baking NBT) on every
        // boot is expensive. When the config is unchanged the already-valid datapack on
        // disk must be reused instead of being wiped and rebuilt.
        registry.loadSpecies();
        TreeSpecies glowcap = registry.getSpeciesMap().get("glowcap");
        Assertions.assertNotNull(glowcap, "glowcap species should be loaded");

        TreeSpecies placed = new TreeSpecies(
                glowcap.id(), glowcap.displayName(), glowcap.namespace(), glowcap.group(),
                Set.of("lush_caves"), glowcap.saplingItem(), true, false,
                glowcap.spacing(), glowcap.separation(), glowcap.salt(), glowcap.spreadType(), glowcap.step(),
                glowcap.variants(), glowcap.procedural(), glowcap.trees(), glowcap.treeDefinitions(), glowcap.weight());

        Map<String, TreeSpecies> species = new HashMap<>();
        species.put(placed.id(), placed);
        TreeGenConfig config = new TreeGenConfig(species, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.DATAPACK);

        DatapackGenerator generator = new DatapackGenerator(platform);
        int first = generator.generate("world", config, registry);
        Assertions.assertTrue(first > 0, "first generation should write at least one species");

        Path packRoot = platform.getWorldFolder("world")
                .resolve("datapacks").resolve("leaf-treegen-generated");
        Path marker = packRoot.resolve(".treegen-fingerprint");
        Assertions.assertTrue(java.nio.file.Files.isRegularFile(marker),
                "fingerprint marker should be written");

        // Drop a sentinel into the pack; a true regeneration would wipe it away.
        Path sentinel = packRoot.resolve("sentinel.txt");
        java.nio.file.Files.writeString(sentinel, "keep-me");

        int second = generator.generate("world", config, registry);
        Assertions.assertEquals(first, second, "unchanged config should return the same species count");
        Assertions.assertTrue(java.nio.file.Files.exists(sentinel),
                "unchanged config must not wipe/regenerate the datapack");

        // Changing the config (here the pack description) must force a regeneration.
        TreeGenConfig changed = new TreeGenConfig(species, "leaf-treegen-generated",
                "Different description", TreeGenConfig.GenerationMode.DATAPACK);
        int third = generator.generate("world", changed, registry);
        Assertions.assertTrue(third > 0, "changed config should regenerate");
        Assertions.assertFalse(java.nio.file.Files.exists(sentinel),
                "changed config must wipe and regenerate the datapack");
    }

    @Test
    public void testGroupPoolBakesProceduralChildVariants() throws Exception {
        // A group species (using the "trees" field) must be self-contained: it must
        // bake the procedural NBT variants of every child species it references, even
        // when that child is not independently active (worldgen disabled). The group
        // pool must also reference the count-based variant names (e.g. name_1..name_N).
        registry.loadSpecies();
        TreeSpecies glowcap = registry.getSpeciesMap().get("glowcap");
        Assertions.assertNotNull(glowcap, "glowcap species should be loaded");

        // Child: same data, but worldgen disabled so it gets NO pool of its own.
        TreeSpecies child = new TreeSpecies(
                glowcap.id(), glowcap.displayName(), glowcap.namespace(), glowcap.group(),
                Set.of(), glowcap.saplingItem(), false, false,
                glowcap.spacing(), glowcap.separation(), glowcap.salt(), glowcap.spreadType(), glowcap.step(),
                glowcap.variants(), glowcap.procedural(), glowcap.trees(), glowcap.treeDefinitions(), glowcap.weight());

        // Group: references the child as a whole species via the "trees" field.
        TreeSpecies group = new TreeSpecies(
                "test-group", "Test Group", glowcap.namespace(), "test_group",
                Set.of("lush_caves"), null, true, false,
                glowcap.spacing(), glowcap.separation(), 12345, glowcap.spreadType(), glowcap.step(),
                List.of(), null, List.of(new TreeSpecies.WeightedSpecies(glowcap.id(), 2)),
                null, 1);

        Map<String, TreeSpecies> species = new HashMap<>();
        species.put(child.id(), child);
        species.put(group.id(), group);
        TreeGenConfig config = new TreeGenConfig(species, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.DATAPACK);

        DatapackGenerator generator = new DatapackGenerator(platform);
        generator.generate("world", config, registry);

        Path packData = platform.getWorldFolder("world")
                .resolve("datapacks").resolve("leaf-treegen-generated").resolve("data");

        // The child's procedural variants must have been baked by the group path.
        Path structureDir = packData.resolve(glowcap.namespace())
                .resolve("structure").resolve(glowcap.group());
        Assertions.assertTrue(java.nio.file.Files.isDirectory(structureDir),
                "child structure directory should be baked by the group at " + structureDir);
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(structureDir)) {
            List<Path> nbts = walk.filter(p -> p.toString().endsWith(".nbt")).toList();
            Assertions.assertFalse(nbts.isEmpty(),
                    "Group must bake the procedural child's .nbt variants");
        }

        // The group's template_pool must reference those baked locations, and every
        // referenced location must have a matching baked .nbt file (no dangling refs).
        Path groupPool = packData.resolve(glowcap.namespace())
                .resolve("worldgen").resolve("template_pool").resolve("test_group").resolve("test-group.json");
        Assertions.assertTrue(java.nio.file.Files.isRegularFile(groupPool),
                "group template_pool should be generated at " + groupPool);
        String content = java.nio.file.Files.readString(groupPool);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"location\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
        int refs = 0;
        while (m.find()) {
            refs++;
            String loc = m.group(1);
            int colon = loc.indexOf(':');
            Path nbt = packData.resolve(loc.substring(0, colon))
                    .resolve("structure").resolve(loc.substring(colon + 1) + ".nbt");
            Assertions.assertTrue(java.nio.file.Files.isRegularFile(nbt),
                    "Group pool references location '" + loc + "' but no baked structure exists at " + nbt);
        }
        Assertions.assertTrue(refs > 0, "Group pool should reference at least one baked variant");
    }

    @Test
    public void testProceduralModeWritesSuppressionOnlyDatapack() throws Exception {
        // Runtime (PROCEDURAL) path: the plugin places trees itself, so the datapack
        // must contain ONLY the vanilla suppression overrides (placed_feature) and
        // NO structure_set / template_pool (otherwise vanilla worldgen would place
        // jigsaw trees on top of the runtime ones).
        registry.loadSpecies();
        TreeSpecies glowcap = registry.getSpeciesMap().get("glowcap");
        Assertions.assertNotNull(glowcap, "glowcap species should be loaded");

        TreeSpecies placed = new TreeSpecies(
                glowcap.id(), glowcap.displayName(), glowcap.namespace(), glowcap.group(),
                Set.of("lush_caves"), glowcap.saplingItem(), true, /*replaceVanilla*/ true,
                glowcap.spacing(), glowcap.separation(), glowcap.salt(), glowcap.spreadType(), glowcap.step(),
                glowcap.variants(), glowcap.procedural(), glowcap.trees(), glowcap.treeDefinitions(), glowcap.weight());

        Map<String, TreeSpecies> species = new HashMap<>();
        species.put(placed.id(), placed);
        TreeGenConfig config = new TreeGenConfig(species, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.PROCEDURAL);

        Assertions.assertTrue(config.needsDatapack(), "PROCEDURAL + replaceVanilla should still need a datapack");
        Assertions.assertTrue(config.suppressionOnly(), "PROCEDURAL + replaceVanilla should be suppression-only");

        DatapackGenerator generator = new DatapackGenerator(platform);
        int written = generator.generate("world", config, registry);
        Assertions.assertTrue(written > 0, "Suppression-only datapack should write at least one species");

        Path packData = platform.getWorldFolder("world")
                .resolve("datapacks").resolve("leaf-treegen-generated").resolve("data");

        // Suppression overrides present.
        Path suppressed = packData.resolve("minecraft").resolve("worldgen")
                .resolve("placed_feature").resolve("oak_checked.json");
        Assertions.assertTrue(java.nio.file.Files.isRegularFile(suppressed),
                "Suppression placed_feature override should be written at " + suppressed);

        // No structures / template pools baked for the runtime path.
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(packData)) {
            List<Path> all = walk.toList();
            Assertions.assertTrue(all.stream().noneMatch(p -> p.toString().replace('\\', '/').contains("/worldgen/template_pool/")),
                    "PROCEDURAL mode must not bake template_pool files");
            Assertions.assertTrue(all.stream().noneMatch(p -> p.toString().replace('\\', '/').contains("/worldgen/structure_set/")),
                    "PROCEDURAL mode must not write structure_set files");
            Assertions.assertTrue(all.stream().noneMatch(p -> p.toString().endsWith(".nbt")),
                    "PROCEDURAL mode must not bake .nbt structures");
        }
    }

    @Test
    public void testRuntimeReusesBoundedVariants() {
        // Runtime placement must restrict itself to a bounded pool of pre-computed
        // variants (count) and reuse them, instead of building a fresh tree for
        // every placement. We verify only `count` distinct geometries are produced
        // across many placements of a single-procedural species with count=3.
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", "minecraft:oak_leaves", 6, 6, "OAK",
                1.0, "CONSTANT", new HashMap<>(), false,
                0.0, 0.0, "CONSTANT", new HashMap<>(),
                "LINEAR", new HashMap<>(),
                null, 0.5, 1.0,
                new TreeSpecies.CanopyParams("DENSITY", 1.0,
                        List.of(new TreeSpecies.CanopyParams.Layer(5, 2.0)),
                        null, null, 0.0, true, 1.0, null, null, 1.0),
                true, /*count*/ 3, 1, List.of());
        TreeSpecies species = new TreeSpecies("bounded", "Bounded", "ns", "gp", Set.of(), "OAK",
                true, false, 3, 2, -1, "linear", "step", List.of(), params, null, null, 1);

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        Set<String> distinctGeometries = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            platform.placedBlocks.clear();
            generator.generate("world", 0, 64, 0, species, new Random(i));
            distinctGeometries.add(platform.placedBlocks.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .sorted().reduce("", (a, b) -> a + ";" + b));
        }
        Assertions.assertTrue(distinctGeometries.size() <= 3,
                "Runtime must reuse at most count(=3) pre-computed variants, got " + distinctGeometries.size());
        Assertions.assertTrue(distinctGeometries.size() > 1,
                "With count=3 more than one variant should be produced across 200 placements");
    }

    @Test
    public void testLeavesPlacedAfterTheirConnectingWoodOrLeaf() {
        // Regression for large cherry leaves despawning in runtime (non-DATAPACK)
        // placement. Platforms that apply block physics compute a leaf's decay
        // distance when it is written and a log/closer leaf placed afterwards does
        // not correct it; leaves written before their connecting blocks were stamped
        // distance 7 and decayed. SegmentedTreeModel.orderForPlacement must emit each
        // leaf only after an orthogonal neighbour that is either wood or a leaf
        // closer to the trunk (placed earlier), so the computed distance is final.
        registry.loadSpecies();
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        TreeSpecies species = registry.getSpeciesMap().get("cherry-grove");
        Assertions.assertNotNull(species, "cherry-grove species should be loaded");

        for (Map.Entry<String, TreeSpecies.ProceduralParams> de : species.treeDefinitions().entrySet()) {
            for (int i = 0; i < 6; i++) {
                TreeModel model = generator.buildProceduralModel("cherry-grove:" + de.getKey(), de.getValue(), new Random(i));
                Map<TreeModel.BlockPos, String> blocks = model.getBlocks();

                List<TreeModel.BlockPos> positions = new ArrayList<>(blocks.keySet());
                Map<TreeModel.BlockPos, String> stateByPos = new HashMap<>(blocks);
                List<TreeModel.BlockPos> ordered = SegmentedTreeModel.orderForPlacement(positions, stateByPos);

                Set<TreeModel.BlockPos> placed = new HashSet<>();
                for (TreeModel.BlockPos p : ordered) {
                    String s = stateByPos.get(p);
                    boolean leaf = s.contains("leaves");
                    if (leaf) {
                        boolean supported = false;
                        for (int[] d : dirs) {
                            TreeModel.BlockPos n = new TreeModel.BlockPos(p.x()+d[0], p.y()+d[1], p.z()+d[2]);
                            String ns = stateByPos.get(n);
                            if (ns == null) continue;
                            boolean nWood = ns.contains("log") || ns.contains("wood") || ns.contains("stem");
                            // A neighbour that is wood, or a leaf already placed (hence
                            // closer to the trunk), anchors this leaf's decay distance.
                            if (nWood || (ns.contains("leaves") && placed.contains(n))) {
                                supported = true;
                                break;
                            }
                        }
                        Assertions.assertTrue(supported,
                                "cherry-grove:" + de.getKey() + " (sample " + i + ") places leaf at " + p
                                        + " before any connecting wood/closer leaf");
                    }
                    placed.add(p);
                }
            }
        }
    }

    @Test
    public void testLeafStatesBakeDecayDistanceAndPersistentFalse() {
        // Leaves must be written with a final, correct decay distance so they do not
        // despawn after placement (runtime OR datapack NBT). Every leaf state in the
        // model must carry persistent=false and a distance within vanilla's radius.
        registry.loadSpecies();
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeSpecies species = registry.getSpeciesMap().get("cherry-grove");
        Assertions.assertNotNull(species, "cherry-grove species should be loaded");

        for (Map.Entry<String, TreeSpecies.ProceduralParams> de : species.treeDefinitions().entrySet()) {
            for (int i = 0; i < 6; i++) {
                TreeModel model = generator.buildProceduralModel("cherry-grove:" + de.getKey(), de.getValue(), new Random(i));
                for (Map.Entry<TreeModel.BlockPos, String> e : model.getBlocks().entrySet()) {
                    String s = e.getValue();
                    if (!s.contains("leaves")) continue;
                    Assertions.assertTrue(s.contains("persistent=false"),
                            "cherry-grove:" + de.getKey() + " leaf state missing persistent=false: " + s);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("distance=(\\d+)").matcher(s);
                    Assertions.assertTrue(m.find(), "leaf state missing distance: " + s);
                    int d = Integer.parseInt(m.group(1));
                    Assertions.assertTrue(d >= 1 && d <= 6,
                            "cherry-grove:" + de.getKey() + " (sample " + i + ") leaf baked with decay distance " + d
                                    + " (must be 1..6 to survive): " + s);
                }
            }
        }
    }

    @Test
    public void testLeafExteriorOrnamentsAreRendered() {
        // Verify that leaf_exterior decorators actually scatter ornaments across the canopy.
        registry.loadSpecies();
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeSpecies christmas = registry.getSpeciesMap().get("christmas-tree");
        Assertions.assertNotNull(christmas, "christmas-tree species should be loaded");

        long totalOrnaments = 0;
        long totalLeaves = 0;
        for (Map.Entry<String, TreeSpecies.ProceduralParams> de : christmas.treeDefinitions().entrySet()) {
            for (int seed = 0; seed < 5; seed++) {
                TreeModel model = generator.buildProceduralModel("christmas-tree:" + de.getKey(), de.getValue(), new Random(seed));
                Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
                long ornaments = blocks.values().stream()
                        .filter(b -> b.contains("stained_glass") || b.contains("froglight")
                                || b.contains("end_rod") || b.contains("glowstone")
                                || b.contains("shroomlight") || b.contains("sea_lantern"))
                        .count();
                long leaves = blocks.values().stream().filter(b -> b.contains("leaves")).count();
                totalOrnaments += ornaments;
                totalLeaves += leaves;
                System.out.println("[DEBUG_LOG] christmas-tree " + de.getKey() + " seed=" + seed
                        + " leaves=" + leaves + " ornaments=" + ornaments);
            }
        }
        System.out.println("[DEBUG_LOG] TOTAL leaves=" + totalLeaves + " ornaments=" + totalOrnaments);
        Assertions.assertTrue(totalOrnaments >= 10,
                "christmas-tree leaf_exterior ornaments should be >= 10 across 5 seeds, got " + totalOrnaments);
    }

    @Test
    public void testBranchTipOrnamentsAreRendered() {
        // Regression: branch_tip decorators (the christmas tree's ornaments) used to
        // place nothing because the branch system rasterises wood to the endpoint and
        // re-skins it with leaves AFTER decorators ran, erasing every ornament. The
        // generator now decorates last and hangs ornaments on the leafy shell around
        // each branch tip, so a fully decorated tree must carry visible ornaments.
        registry.loadSpecies();
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        TreeSpecies christmas = registry.getSpeciesMap().get("christmas-tree");
        Assertions.assertNotNull(christmas, "christmas-tree species should be loaded. keys=" + registry.getSpeciesMap().keySet());
        Assertions.assertNotNull(christmas.treeDefinitions(), "christmas-tree should expose tree definitions");

        boolean anyOrnaments = false;
        for (Map.Entry<String, TreeSpecies.ProceduralParams> de : christmas.treeDefinitions().entrySet()) {
            for (int seed = 0; seed < 5; seed++) {
                TreeModel model = generator.buildProceduralModel("christmas-tree:" + de.getKey(), de.getValue(), new Random(seed));
                long ornaments = model.getBlocks().values().stream()
                        .filter(b -> b.contains("stained_glass") || b.contains("froglight")
                                || b.contains("end_rod") || b.contains("glowstone")
                                || b.contains("shroomlight") || b.contains("sea_lantern"))
                        .count();
                if (ornaments > 0) anyOrnaments = true;
            }
        }
        Assertions.assertTrue(anyOrnaments,
                "christmas-tree must render branch_tip ornaments (stained glass / froglight / end-rod / glowstone)");
    }

    @Test
    public void testGroupSpeciesVariantsAndMultiDefinitionDrops() throws Exception {
        registry.loadSpecies();
        TreeSpecies oakSmallMid = registry.getSpeciesMap().get("oak-small-mid");
        Assertions.assertNotNull(oakSmallMid);

        // Multi-definition species must report leaf blocks, canopy radius, and height from treeDefinitions.
        Assertions.assertFalse(SaplingDropResolver.leafBlocksOf(oakSmallMid).isEmpty(),
                "Multi-definition species must report leaf blocks from treeDefinitions");
        Assertions.assertTrue(SaplingDropResolver.estimateCanopyRadius(oakSmallMid) > 4,
                "Multi-definition species must compute canopy radius from treeDefinitions");
        Assertions.assertTrue(SaplingDropResolver.estimateHeight(oakSmallMid) > 12,
                "Multi-definition species must compute height from treeDefinitions");

        // Group species (e.g. plains -> oak-small-mid) must resolve baked .nbt variants from child species.
        TreeSpecies plains = new TreeSpecies(
                "plains", "Plains", "minecraft", "plains",
                Set.of("minecraft:plains"), "OAK_SAPLING", true, true,
                16, 4, -1, "linear", "surface_structures",
                List.of(), null,
                List.of(new TreeSpecies.WeightedSpecies("oak-small-mid", 100)),
                null, 1);

        Map<String, TreeSpecies> map = new HashMap<>();
        map.put("plains", plains);
        map.put("oak-small-mid", oakSmallMid);
        TreeGenConfig config = new TreeGenConfig(map, "leaf-treegen-generated",
                "Test", TreeGenConfig.GenerationMode.DATAPACK);

        DatapackGenerator datapackGen = new DatapackGenerator(platform);
        datapackGen.generate("world", config, registry);

        List<String> plainsVariants = registry.variantsFor("world", plains);
        Assertions.assertFalse(plainsVariants.isEmpty(),
                "variantsFor on a group species (plains) must resolve baked child .nbt variants");

        ProceduralGenerator procGen = new ProceduralGenerator(platform, registry);
        procGen.setConfigSpecies(map);
        Assertions.assertTrue(procGen.canGenerate(plains),
                "ProceduralGenerator.canGenerate must return true for group species");
        Assertions.assertTrue(plains.allowsWorldgenBiome("plains"));
        Assertions.assertFalse(oakSmallMid.allowsWorldgenBiome("plains"),
                "Child helper species with empty biomes must not match arbitrary worldgen biomes");
    }

    @Test
    public void testTrunkSurfaceDecoratorsUseValidMultifaceStates() {
        registry.loadSpecies();
        ProceduralGenerator procGen = new ProceduralGenerator(platform, registry);
        for (Map.Entry<String, TreeSpecies> entry : registry.getSpeciesMap().entrySet()) {
            TreeSpecies species = entry.getValue();
            if (species.treeDefinitions() == null) continue;
            for (Map.Entry<String, TreeSpecies.ProceduralParams> def : species.treeDefinitions().entrySet()) {
                for (int seed = 0; seed < 8; seed++) {
                    TreeModel model = procGen.buildProceduralModel(
                            species.id() + ":" + def.getKey(), def.getValue(), new Random(seed));
                    for (String state : model.getBlocks().values()) {
                        Assertions.assertFalse(state.startsWith("minecraft:glow_lichen[facing="),
                                "glow_lichen must use multiface [<face>=true], not [facing=...]: " + state);
                        Assertions.assertFalse(state.startsWith("minecraft:vine[facing="),
                                "vine must use multiface [<face>=true], not [facing=...]: " + state);
                        Assertions.assertFalse(state.startsWith("minecraft:brown_mushroom["),
                                "brown_mushroom has no block-state properties: " + state);
                    }
                }
            }
        }

        TreeModel rotModel = new TreeModel("test");
        rotModel.setBlock(1, 0, 0, "minecraft:glow_lichen[west=true]");
        rotModel.rotate(1);
        Assertions.assertEquals("minecraft:glow_lichen[north=true]", rotModel.getBlock(0, 0, 1));
    }

    @Test
    public void testSliceForChunkConservationAndStatelessHaloOrderIndependence() {
        registry.loadSpecies();
        ProceduralGenerator procGen = new ProceduralGenerator(platform, registry);
        TreeSpecies cherry = registry.getSpeciesMap().get("cherry-grove");
        Assertions.assertNotNull(cherry);

        TreeModel model = procGen.buildModel(cherry, new Random(7));
        Assertions.assertNotNull(model);
        SegmentedTreeModel segmented = new SegmentedTreeModel(model);

        // Anchor at (15, 64, 15) on the corner of chunk (0, 0) so the canopy spans 4 chunks.
        int ox = 15, oy = 64, oz = 15;
        Set<Long> touched = segmented.touchedChunks(ox, oz);
        Assertions.assertTrue(touched.size() > 1,
                "Tree anchored at chunk corner (15, 64, 15) must overhang into neighbour chunks");
        Assertions.assertEquals(model.touchedChunks(ox, oz), touched);

        Map<TreeModel.BlockPos, String> unionFromSlices = new HashMap<>();
        for (long key : touched) {
            int cx = TreeModel.chunkX(key);
            int cz = TreeModel.chunkZ(key);
            Assertions.assertTrue(segmented.intersectsChunk(ox, oz, cx, cz));
            Map<TreeModel.BlockPos, String> slice = segmented.sliceForChunk(ox, oy, oz, cx, cz);
            Assertions.assertFalse(slice.isEmpty());
            Assertions.assertEquals(model.sliceForChunk(ox, oy, oz, cx, cz), slice);

            boolean seenNonWood = false;
            for (Map.Entry<TreeModel.BlockPos, String> e : slice.entrySet()) {
                TreeModel.BlockPos pos = e.getKey();
                Assertions.assertEquals(cx, pos.x() >> 4, "Slice block X must belong to target chunk");
                Assertions.assertEquals(cz, pos.z() >> 4, "Slice block Z must belong to target chunk");
                String state = e.getValue();
                boolean isWood = state.contains("log") || state.contains("wood") || state.contains("stem");
                if (!isWood) {
                    seenNonWood = true;
                } else {
                    Assertions.assertFalse(seenNonWood, "Wood blocks must precede leaves/decorators within a chunk slice");
                }
                unionFromSlices.put(pos, state);
            }
        }
        Assertions.assertEquals(model.getBlocks().size(), unionFromSlices.size(),
                "Union of per-chunk slices must equal 100% of the full tree blocks");
        Assertions.assertTrue(segmented.sliceForChunk(ox, oy, oz, 99, 99).isEmpty(),
                "Non-intersecting chunk must return an empty slice");

        // Stateless 3x3 halo pull must produce identical world blocks regardless of chunk generation order.
        TreeSpecies haloSpecies = new TreeSpecies(
                "halo-oak", "Halo Oak", "minecraft", "oak",
                Set.of("minecraft:plains"), "OAK_SAPLING", true, true,
                1, 0, 12345, "linear", "surface_structures",
                List.of(),
                new TreeSpecies.ProceduralParams(
                        "minecraft:oak_log", "minecraft:oak_leaves", 8, 10, "OAK", 1.5,
                        "LINEAR", Map.of(), false, 0.0, 0.0, "CONSTANT", Map.of(),
                        "CONSTANT", Map.of(), null, 0.0, 0.5,
                        new TreeSpecies.CanopyParams(
                                "ROUNDED", 0.85,
                                List.of(new TreeSpecies.CanopyParams.Layer(6, 5.5)),
                                null, null, 0.0, false, 1.0, null, null, 0.0),
                        false, 1, 2, List.of()),
                List.of(), null, 2);

        List<int[]> forwardChunks = List.of(new int[]{0, 0}, new int[]{1, 0}, new int[]{0, 1}, new int[]{1, 1});
        List<int[]> reverseChunks = List.of(new int[]{1, 1}, new int[]{0, 1}, new int[]{1, 0}, new int[]{0, 0});

        Map<TreeModel.BlockPos, String> forwardWorld = new HashMap<>();
        for (int[] c : forwardChunks) {
            Map<TreeModel.BlockPos, String> slice = procGen.sampleChunkHalo(
                    c[0], c[1], 987654321L, List.of(haloSpecies), 1,
                    (x, z) -> 63, (x, y, z) -> "minecraft:plains");
            for (TreeModel.BlockPos pos : slice.keySet()) {
                Assertions.assertEquals(c[0], pos.x() >> 4);
                Assertions.assertEquals(c[1], pos.z() >> 4);
            }
            forwardWorld.putAll(slice);
        }

        Map<TreeModel.BlockPos, String> reverseWorld = new HashMap<>();
        for (int[] c : reverseChunks) {
            Map<TreeModel.BlockPos, String> slice = procGen.sampleChunkHalo(
                    c[0], c[1], 987654321L, List.of(haloSpecies), 1,
                    (x, z) -> 63, (x, y, z) -> "minecraft:plains");
            reverseWorld.putAll(slice);
        }

        Assertions.assertFalse(forwardWorld.isEmpty(), "Stateless 3x3 halo must generate blocks across the 2x2 chunk grid");
        Assertions.assertEquals(forwardWorld, reverseWorld,
                "Stateless 3x3 halo generation must be invariant under chunk generation order");
    }

    @Test
    public void testShippedSpeciesSchemaFidelityAndRootsAndConfigGenerateToggle() {
        registry.loadSpecies();
        Map<String, TreeSpecies> speciesMap = registry.getSpeciesMap();
        ProceduralGenerator procGen = new ProceduralGenerator(platform, registry);

        // 1. rainbow-grove uses top-level secondary_leaves array + secondary_leaf_fraction: 0.857
        TreeSpecies rainbow = speciesMap.get("rainbow-grove");
        Assertions.assertNotNull(rainbow);
        TreeModel rainbowModel = procGen.buildModel(rainbow, new Random(42));
        Assertions.assertNotNull(rainbowModel);
        Set<String> rainbowBlockTypes = new HashSet<>();
        for (String b : rainbowModel.getBlocks().values()) {
            rainbowBlockTypes.add(SaplingDropResolver.baseBlockId(b));
        }
        Assertions.assertTrue(rainbowBlockTypes.contains("minecraft:spruce_leaves")
                        || rainbowBlockTypes.contains("minecraft:birch_leaves")
                        || rainbowBlockTypes.contains("minecraft:jungle_leaves"),
                "rainbow-grove must mix weighted secondary_leaves array into canopy, got: " + rainbowBlockTypes);

        // 2. glacierpine uses top-level secondary_leaves string ("minecraft:snow_block") + secondary_leaf_fraction
        TreeSpecies glacierpine = speciesMap.get("glacierpine");
        Assertions.assertNotNull(glacierpine);
        TreeModel glacierModel = procGen.buildModel(glacierpine, new Random(7));
        Assertions.assertNotNull(glacierModel);
        Assertions.assertTrue(glacierModel.getBlocks().values().stream()
                        .anyMatch(s -> s.startsWith("minecraft:snow_block")),
                "glacierpine must include top-level secondary_leaves snow_block in canopy");

        // 3. savanna uses start_angle: 180 (flat disc layers, halfH = 0)
        TreeSpecies savanna = speciesMap.get("savanna");
        Assertions.assertNotNull(savanna);
        TreeSpecies.ProceduralParams savannaSmall = savanna.treeDefinitions().get("acacia");
        Assertions.assertNotNull(savannaSmall);
        Assertions.assertEquals(180.0, savannaSmall.canopy().startAngle(), 1e-6,
                "start_angle must be parsed from canopy JSON");

        // 4. dark-forest-megas respects squish: 0.38 and start_angle: 148
        TreeSpecies megas = speciesMap.get("dark-forest-megas");
        Assertions.assertNotNull(megas);
        TreeSpecies.ProceduralParams titanMegaA = megas.treeDefinitions().get("titan_mega_A");
        Assertions.assertNotNull(titanMegaA);
        Assertions.assertEquals(148.0, titanMegaA.canopy().startAngle(), 1e-6,
                "start_angle must be parsed from canopy JSON");
        Assertions.assertEquals(0.38, titanMegaA.canopy().squish(), 1e-6,
                "squish must be parsed from canopy JSON");

        // 5. SegmentedTreeModel orders hyphae as wood before leaves
        Map<TreeModel.BlockPos, String> crimsonBlocks = new LinkedHashMap<>();
        crimsonBlocks.put(new TreeModel.BlockPos(0, 2, 0), "minecraft:NETHER_WART_BLOCK".toLowerCase(Locale.ROOT));
        crimsonBlocks.put(new TreeModel.BlockPos(0, 1, 0), "minecraft:oak_leaves[distance=1,persistent=false]");
        crimsonBlocks.put(new TreeModel.BlockPos(0, 0, 0), "minecraft:crimson_hyphae[axis=y]");
        List<TreeModel.BlockPos> ordered = SegmentedTreeModel.orderForPlacement(
                new ArrayList<>(crimsonBlocks.keySet()), crimsonBlocks);
        Assertions.assertEquals(new TreeModel.BlockPos(0, 0, 0), ordered.get(0),
                "hyphae must be recognized as wood and placed before leaves");
    }

    private static class MockPlatform implements Platform {
        private final Path root = Paths.get("build/test-data-" + System.currentTimeMillis());
        public final Map<String, String> placedBlocks = new HashMap<>();

        @Override public Logger logger() {
            Logger logger = Logger.getLogger("Test-" + System.currentTimeMillis());
            logger.setUseParentHandlers(true);
            return logger;
        }
        @Override public Path getRootFolder() { return root; }
        @Override public Path getWorldFolder(String worldName) { return root.resolve(worldName); }
        @Override public List<String> getWorldNames() { return List.of("world"); }
        @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {}
        @Override public String getBiomeKey(String worldName, int x, int y, int z) { return "minecraft:plains"; }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return true; }
        @Override public void giveSapling(UUID playerId, String speciesId, int amount) {}
        @Override public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
            placedBlocks.put(x + "," + y + "," + z, blockData);
            return true;
        }
        @Override public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
            blocks.forEach((pos, data) -> placedBlocks.put(pos.x() + "," + pos.y() + "," + pos.z(), data));
            return true;
        }
        @Override public void sendMessage(UUID playerId, String message, boolean error) {}
    }
}
