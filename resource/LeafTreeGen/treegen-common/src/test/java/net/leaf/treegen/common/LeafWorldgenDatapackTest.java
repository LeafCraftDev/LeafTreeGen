package net.leaf.treegen.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.dailystruggle.anvil.Nbt;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Automated QA test gate for {@code datapacks/leaf-worldgen} and {@code LeafTreeGen} custom
 * {@code leaf:*} biome compatibility ({@code ADR-126}).
 *
 * @governedBy ADR-126
 */
class LeafWorldgenDatapackTest {

  private static final Gson GSON = new Gson();

  private static Path locateDatapackRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve("datapacks/leaf-worldgen");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate datapacks/leaf-worldgen from working dir");
  }

  @Test
  void referentialIntegrityAcrossOverworldBiomesFeaturesPoolsAndStructures() throws IOException {
    Path packRoot = locateDatapackRoot();
    assertTrue(Files.isRegularFile(packRoot.resolve("pack.mcmeta")));

    Path leafData = packRoot.resolve("data/leaf");
    Path mcData = packRoot.resolve("data/minecraft");

    // 1. Verify overworld.json -> worldgen/biome/*.json
    Path overworldJson = mcData.resolve("dimension/overworld.json");
    assertTrue(Files.isRegularFile(overworldJson), "Missing overworld.json");
    JsonObject overworld =
        GSON.fromJson(Files.readString(overworldJson, StandardCharsets.UTF_8), JsonObject.class);
    JsonArray biomeEntries =
        overworld
            .getAsJsonObject("generator")
            .getAsJsonObject("biome_source")
            .getAsJsonArray("biomes");
    assertFalse(biomeEntries.isEmpty());

    Set<String> referencedLeafBiomes = new HashSet<>();
    for (JsonElement el : biomeEntries) {
      String biomeId = el.getAsJsonObject().get("biome").getAsString();
      if (biomeId.startsWith("leaf:")) {
        referencedLeafBiomes.add(biomeId);
        String local = biomeId.substring("leaf:".length());
        Path biomeFile = leafData.resolve("worldgen/biome/" + local + ".json");
        assertTrue(
            Files.isRegularFile(biomeFile),
            "overworld.json references missing biome file: " + biomeId + " -> " + biomeFile);
      }
    }
    assertTrue(referencedLeafBiomes.contains("leaf:dark_forest_edge"));
    assertTrue(referencedLeafBiomes.contains("leaf:dark_forest_body"));
    assertTrue(referencedLeafBiomes.contains("leaf:dark_forest_core"));
    assertTrue(referencedLeafBiomes.contains("leaf:jacaranda_grove"));
    assertTrue(referencedLeafBiomes.contains("leaf:redwood_grove"));
    assertTrue(referencedLeafBiomes.contains("leaf:boreal_shield"));
    assertTrue(referencedLeafBiomes.contains("leaf:bayou"));
    for (int i = 1; i <= 8; i++) {
      assertTrue(referencedLeafBiomes.contains(String.format("leaf:maple_forest_%02d", i)));
    }
    for (int i = 1; i <= 12; i++) {
      assertTrue(referencedLeafBiomes.contains(String.format("leaf:rainbow_forest_%02d", i)));
    }

    // 2. Verify worldgen/biome/*.json -> placed_feature/*.json
    try (Stream<Path> biomeFiles = Files.walk(leafData.resolve("worldgen/biome"))) {
      for (Path biomeFile : biomeFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
        JsonObject biomeObj =
            GSON.fromJson(Files.readString(biomeFile, StandardCharsets.UTF_8), JsonObject.class);
        JsonArray featureSteps = biomeObj.getAsJsonArray("features");
        assertNotNull(featureSteps, "Missing features array in " + biomeFile);
        for (JsonElement stepEl : featureSteps) {
          for (JsonElement featEl : stepEl.getAsJsonArray()) {
            String placedId = featEl.getAsString();
            if (placedId.startsWith("leaf:")) {
              String local = placedId.substring("leaf:".length());
              Path placedFile = leafData.resolve("worldgen/placed_feature/" + local + ".json");
              assertTrue(
                  Files.isRegularFile(placedFile),
                  biomeFile.getFileName()
                      + " references missing placed_feature: "
                      + placedId
                      + " -> "
                      + placedFile);
            }
          }
        }
      }
    }

    // 3. Verify placed_feature/*.json -> configured_feature/*.json
    try (Stream<Path> placedFiles = Files.walk(leafData.resolve("worldgen/placed_feature"))) {
      for (Path placedFile : placedFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
        JsonObject placedObj =
            GSON.fromJson(Files.readString(placedFile, StandardCharsets.UTF_8), JsonObject.class);
        String configuredId = placedObj.get("feature").getAsString();
        if (configuredId.startsWith("leaf:")) {
          String local = configuredId.substring("leaf:".length());
          Path configuredFile =
              leafData.resolve("worldgen/configured_feature/" + local + ".json");
          assertTrue(
              Files.isRegularFile(configuredFile),
              placedFile.getFileName()
                  + " references missing configured_feature: "
                  + configuredId
                  + " -> "
                  + configuredFile);
        }
      }
    }

    // 4. Verify configured_feature/*.json nested placed_feature references
    for (Path cfRoot :
        List.of(
            leafData.resolve("worldgen/configured_feature"),
            mcData.resolve("worldgen/configured_feature"))) {
      try (Stream<Path> cfFiles = Files.walk(cfRoot)) {
        for (Path cfFile : cfFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
          JsonObject cfObj =
              GSON.fromJson(Files.readString(cfFile, StandardCharsets.UTF_8), JsonObject.class);
          JsonObject cfg = cfObj.getAsJsonObject("config");
          if (cfg == null) {
            continue;
          }
          if (cfg.has("default") && cfg.get("default").isJsonPrimitive()) {
            assertPlacedFeatureExists(leafData, cfFile, cfg.get("default").getAsString());
          }
          if (cfg.has("features") && cfg.get("features").isJsonArray()) {
            for (JsonElement entry : cfg.getAsJsonArray("features")) {
              if (entry.isJsonObject() && entry.getAsJsonObject().has("feature")) {
                JsonElement fEl = entry.getAsJsonObject().get("feature");
                if (fEl.isJsonPrimitive()) {
                  assertPlacedFeatureExists(leafData, cfFile, fEl.getAsString());
                }
              }
            }
          }
        }
      }
    }

    // 5. Verify structure_set/*.json -> structure/*.json -> biome tag & template_pool -> structure/*.nbt
    try (Stream<Path> setFiles = Files.walk(leafData.resolve("worldgen/structure_set"))) {
      for (Path setFile : setFiles.filter(p -> p.toString().endsWith(".json")).toList()) {
        JsonObject setObj =
            GSON.fromJson(Files.readString(setFile, StandardCharsets.UTF_8), JsonObject.class);
        for (JsonElement sEntry : setObj.getAsJsonArray("structures")) {
          String structId = sEntry.getAsJsonObject().get("structure").getAsString();
          assertTrue(structId.startsWith("leaf:"));
          Path structFile =
              leafData.resolve(
                  "worldgen/structure/" + structId.substring("leaf:".length()) + ".json");
          assertTrue(
              Files.isRegularFile(structFile),
              setFile.getFileName() + " references missing structure: " + structId);

          JsonObject structObj =
              GSON.fromJson(Files.readString(structFile, StandardCharsets.UTF_8), JsonObject.class);
          String biomesTag = structObj.get("biomes").getAsString();
          if (biomesTag.startsWith("#leaf:")) {
            String tagRel = biomesTag.substring("#leaf:".length());
            Path tagFile = leafData.resolve("tags/worldgen/biome/" + tagRel + ".json");
            assertTrue(
                Files.isRegularFile(tagFile),
                structFile.getFileName() + " references missing biome tag: " + biomesTag);
            JsonObject tagObj =
                GSON.fromJson(Files.readString(tagFile, StandardCharsets.UTF_8), JsonObject.class);
            for (JsonElement bVal : tagObj.getAsJsonArray("values")) {
              String bId = bVal.getAsString();
              if (bId.startsWith("leaf:")) {
                Path bFile =
                    leafData.resolve("worldgen/biome/" + bId.substring("leaf:".length()) + ".json");
                assertTrue(
                    Files.isRegularFile(bFile),
                    tagFile.getFileName() + " references missing biome: " + bId);
              }
            }
          }

          String startPool = structObj.get("start_pool").getAsString();
          assertTrue(startPool.startsWith("leaf:"));
          Path poolFile =
              leafData.resolve(
                  "worldgen/template_pool/" + startPool.substring("leaf:".length()) + ".json");
          assertTrue(
              Files.isRegularFile(poolFile),
              structFile.getFileName() + " references missing template_pool: " + startPool);

          JsonObject poolObj =
              GSON.fromJson(Files.readString(poolFile, StandardCharsets.UTF_8), JsonObject.class);
          for (JsonElement elemEntry : poolObj.getAsJsonArray("elements")) {
            JsonObject element = elemEntry.getAsJsonObject().getAsJsonObject("element");
            String location = element.get("location").getAsString();
            assertTrue(location.startsWith("leaf:"));
            Path nbtFile =
                leafData.resolve("structure/" + location.substring("leaf:".length()) + ".nbt");
            assertTrue(
                Files.isRegularFile(nbtFile),
                poolFile.getFileName() + " references missing structure NBT: " + location);
          }
        }
      }
    }
  }

  private static void assertPlacedFeatureExists(
      Path leafData, Path sourceFile, String placedFeatureId) {
    if (placedFeatureId.startsWith("leaf:")) {
      String local = placedFeatureId.substring("leaf:".length());
      Path placedFile = leafData.resolve("worldgen/placed_feature/" + local + ".json");
      assertTrue(
          Files.isRegularFile(placedFile),
          sourceFile.getFileName()
              + " references missing placed_feature: "
              + placedFeatureId
              + " -> "
              + placedFile);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void allStructureNbtFilesParseCleanlyViaAnvilApi() throws IOException {
    Path structureRoot = locateDatapackRoot().resolve("data/leaf/structure");
    assertTrue(Files.isDirectory(structureRoot));

    List<Path> nbtFiles;
    try (Stream<Path> walk = Files.walk(structureRoot)) {
      nbtFiles = walk.filter(p -> p.toString().endsWith(".nbt")).sorted().toList();
    }
    assertEquals(17, nbtFiles.size(), "Expected all 17 leaf-worldgen structure .nbt files");

    for (Path nbtFile : nbtFiles) {
      byte[] rawBytes = readDecompressedNbtBytes(nbtFile);
      LinkedHashMap<String, Object> root = Nbt.readRootCompound(rawBytes);
      assertNotNull(root, "Null NBT root in " + nbtFile);

      Object dataVersionObj = root.get("DataVersion");
      assertTrue(
          dataVersionObj instanceof Integer dv && dv > 0,
          "Invalid or missing DataVersion in " + nbtFile + ": " + dataVersionObj);

      Object sizeObj = root.get("size");
      assertTrue(
          sizeObj instanceof Nbt.NbtList sizeList && sizeList.items.size() == 3,
          "Invalid size list in " + nbtFile);
      List<Object> size = ((Nbt.NbtList) sizeObj).items;
      int sizeX = ((Number) size.get(0)).intValue();
      int sizeY = ((Number) size.get(1)).intValue();
      int sizeZ = ((Number) size.get(2)).intValue();
      assertTrue(sizeX > 0 && sizeY > 0 && sizeZ > 0, "Non-positive dimensions in " + nbtFile);

      Object paletteObj = root.get("palette");
      assertTrue(
          paletteObj instanceof Nbt.NbtList paletteList && !paletteList.items.isEmpty(),
          "Empty or missing palette in " + nbtFile);
      List<Object> palette = ((Nbt.NbtList) paletteObj).items;
      for (Object entryObj : palette) {
        Map<String, Object> entry = (Map<String, Object>) entryObj;
        Object name = entry.get("Name");
        assertTrue(
            name instanceof String s && !s.isBlank(),
            "Palette entry missing Name in " + nbtFile);
      }

      Object blocksObj = root.get("blocks");
      assertTrue(
          blocksObj instanceof Nbt.NbtList blockList && !blockList.items.isEmpty(),
          "Empty or missing blocks list in " + nbtFile);
      List<Object> blocks = ((Nbt.NbtList) blocksObj).items;
      for (Object blockObj : blocks) {
        Map<String, Object> block = (Map<String, Object>) blockObj;
        Object stateObj = block.get("state");
        assertTrue(
            stateObj instanceof Integer stateIdx && stateIdx >= 0 && stateIdx < palette.size(),
            "Block state index out of palette bounds in " + nbtFile);
        Object posObj = block.get("pos");
        assertTrue(
            posObj instanceof Nbt.NbtList posList && posList.items.size() == 3,
            "Invalid block pos in " + nbtFile);
        List<Object> pos = ((Nbt.NbtList) posObj).items;
        int px = ((Number) pos.get(0)).intValue();
        int py = ((Number) pos.get(1)).intValue();
        int pz = ((Number) pos.get(2)).intValue();
        assertTrue(
            px >= 0 && px < sizeX && py >= 0 && py < sizeY && pz >= 0 && pz < sizeZ,
            "Block pos " + pos + " out of size bounds " + size + " in " + nbtFile);
      }
    }
  }

  private static byte[] readDecompressedNbtBytes(Path file) throws IOException {
    byte[] fileBytes = Files.readAllBytes(file);
    if (fileBytes.length >= 2
        && (fileBytes[0] & 0xFF) == 0x1F
        && (fileBytes[1] & 0xFF) == 0x8B) {
      try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(fileBytes))) {
        return gis.readAllBytes();
      }
    }
    return fileBytes;
  }

  @Test
  void generatorScriptParityRainbowMapleAndOverworldOverride() throws IOException {
    Path packRoot = locateDatapackRoot();
    Path biomeDir = packRoot.resolve("data/leaf/worldgen/biome");

    // 1. Parity with tools/build_rainbow_biomes.py (N=12, SAT=0.55, VAL=0.80)
    int rainbowN = 12;
    double rainbowSat = 0.55D;
    double rainbowVal = 0.80D;
    for (int i = 0; i < rainbowN; i++) {
      int expectedFoliage = hsvToRgbInt(i / (double) rainbowN, rainbowSat, rainbowVal);
      Path file = biomeDir.resolve(String.format("rainbow_forest_%02d.json", i + 1));
      JsonObject biome =
          GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
      int actualFoliage = biome.getAsJsonObject("effects").get("foliage_color").getAsInt();
      assertEquals(
          expectedFoliage,
          actualFoliage,
          "Rainbow biome foliage_color parity mismatch in " + file.getFileName());
    }

    // 2. Parity with tools/build_maple_biomes.py (N=8, HUE 0.00..0.13, SAT=0.85, VAL=0.80)
    int mapleN = 8;
    double hueStart = 0.0D;
    double hueEnd = 0.13D;
    double mapleSat = 0.85D;
    double mapleVal = 0.80D;
    for (int i = 0; i < mapleN; i++) {
      double hue = hueStart + (hueEnd - hueStart) * (i / (double) (mapleN - 1));
      int expectedFoliage = hsvToRgbInt(hue, mapleSat, mapleVal);
      Path file = biomeDir.resolve(String.format("maple_forest_%02d.json", i + 1));
      JsonObject biome =
          GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
      int actualFoliage = biome.getAsJsonObject("effects").get("foliage_color").getAsInt();
      assertEquals(
          expectedFoliage,
          actualFoliage,
          "Maple biome foliage_color parity mismatch in " + file.getFileName());
    }

    // 3. Parity with tools/build_overworld_override.py (10 NEW_BIOMES slivers at head + split rules)
    Path overworldJson = packRoot.resolve("data/minecraft/dimension/overworld.json");
    JsonObject overworld =
        GSON.fromJson(Files.readString(overworldJson, StandardCharsets.UTF_8), JsonObject.class);
    JsonArray biomes =
        overworld
            .getAsJsonObject("generator")
            .getAsJsonObject("biome_source")
            .getAsJsonArray("biomes");
    assertEquals("leaf:glass_beach", biomes.get(0).getAsJsonObject().get("biome").getAsString());
    assertEquals("leaf:glass_beach", biomes.get(1).getAsJsonObject().get("biome").getAsString());
    assertEquals(
        "leaf:volcanic_mountain", biomes.get(2).getAsJsonObject().get("biome").getAsString());
    assertEquals(
        "leaf:volcanic_mountain", biomes.get(3).getAsJsonObject().get("biome").getAsString());
    assertEquals("leaf:ashen_plains", biomes.get(4).getAsJsonObject().get("biome").getAsString());
    assertEquals("leaf:ashen_plains", biomes.get(5).getAsJsonObject().get("biome").getAsString());
    assertEquals(
        "leaf:volcanic_hot_springs", biomes.get(6).getAsJsonObject().get("biome").getAsString());
    assertEquals(
        "leaf:volcanic_hot_springs", biomes.get(7).getAsJsonObject().get("biome").getAsString());
    assertEquals(
        "leaf:lavender_fields", biomes.get(8).getAsJsonObject().get("biome").getAsString());
    assertEquals(
        "leaf:lavender_fields", biomes.get(9).getAsJsonObject().get("biome").getAsString());
  }

  private static int hsvToRgbInt(double h, double s, double v) {
    if (s == 0.0D) {
      int c = (int) (v * 255.0D);
      return (c << 16) | (c << 8) | c;
    }
    int i = (int) (h * 6.0D);
    double f = (h * 6.0D) - i;
    double p = v * (1.0D - s);
    double q = v * (1.0D - s * f);
    double t = v * (1.0D - s * (1.0D - f));
    i = i % 6;
    double r;
    double g;
    double b;
    switch (i) {
      case 0 -> {
        r = v;
        g = t;
        b = p;
      }
      case 1 -> {
        r = q;
        g = v;
        b = p;
      }
      case 2 -> {
        r = p;
        g = v;
        b = t;
      }
      case 3 -> {
        r = p;
        g = q;
        b = v;
      }
      case 4 -> {
        r = t;
        g = p;
        b = v;
      }
      default -> {
        r = v;
        g = p;
        b = q;
      }
    }
    int ri = (int) (r * 255.0D);
    int gi = (int) (g * 255.0D);
    int bi = (int) (b * 255.0D);
    return (ri << 16) | (gi << 8) | bi;
  }

  @Test
  void leafTreeGenSupportsLeafCustomBiomeNamespaces(@TempDir Path tempDir) throws IOException {
    Platform dummyPlatform = mock(Platform.class);
    when(dummyPlatform.getRootFolder()).thenReturn(tempDir);
    when(dummyPlatform.logger()).thenReturn(Logger.getAnonymousLogger());
    TreeRegistry registry = new TreeRegistry(dummyPlatform);
    registry.loadSpecies();

    File configFile = tempDir.resolve("config.yml").toFile();
    try (InputStream in =
        TreeGenConfig.class.getClassLoader().getResourceAsStream("config.yml")) {
      assertNotNull(in);
    }
    RtpYamlConfig yaml = RtpYamlConfig.load(configFile);
    TreeGenConfig config = TreeGenConfig.fromYaml(yaml, registry, dummyPlatform);

    // Verify all required leaf:* custom biomes are supported by the configured species
    assertTrue(config.get("dark_forest_understory").allowsWorldgenBiome("leaf:dark_forest_edge"));
    assertTrue(config.get("dark_forest_understory").allowsWorldgenBiome("leaf:dark_forest_body"));
    assertTrue(config.get("dark_forest_understory").allowsWorldgenBiome("leaf:dark_forest_core"));
    assertTrue(config.get("dark_forest_canopy").allowsWorldgenBiome("leaf:dark_forest_core"));

    for (int i = 1; i <= 8; i++) {
      String mapleBiome = String.format("leaf:maple_forest_%02d", i);
      assertTrue(config.get("birch-forest").allowsWorldgenBiome(mapleBiome));
      assertTrue(config.get("maple-birch-giants").allowsWorldgenBiome(mapleBiome));
    }

    assertTrue(config.get("cherry-grove").allowsWorldgenBiome("leaf:jacaranda_grove"));
    assertTrue(config.get("jacaranda-grove").allowsWorldgenBiome("leaf:jacaranda_grove"));
    assertTrue(config.get("old-growth-pine-taiga").allowsWorldgenBiome("leaf:redwood_grove"));
    assertTrue(config.get("snowy_taiga").allowsWorldgenBiome("leaf:boreal_shield"));
    assertTrue(config.get("swamp").allowsWorldgenBiome("leaf:bayou"));

    // Also verify fallback resolution when a species only declares the vanilla biome name
    for (String[] pair :
        new String[][] {
          {"minecraft:dark_forest", "leaf:dark_forest_core"},
          {"minecraft:birch_forest", "leaf:maple_forest_01"},
          {"minecraft:taiga", "leaf:taiga_core"},
          {"minecraft:forest", "leaf:forest_core"},
          {"minecraft:flower_forest", "leaf:rainbow_forest_01"},
          {"minecraft:cherry_grove", "leaf:jacaranda_grove"},
          {"minecraft:old_growth_pine_taiga", "leaf:redwood_grove"},
          {"minecraft:snowy_taiga", "leaf:boreal_shield"},
          {"minecraft:swamp", "leaf:bayou"},
          {"minecraft:meadow", "leaf:alpine_meadow"}
        }) {
      TreeSpecies vanillaOnly =
          new TreeSpecies(
              "test-species",
              "Test Species",
              "minecraft",
              "test",
              Set.of(pair[0]),
              "OAK_SAPLING",
              true,
              false,
              3,
              1,
              -1,
              "linear",
              "surface_structures",
              List.of(),
              null,
              null,
              null,
              1);
      assertTrue(vanillaOnly.allowsWorldgenBiome(pair[1]));
      assertFalse(vanillaOnly.allowsWorldgenBiome("leaf:unrelated_biome"));
    }
    assertTrue(TreeSpecies.leafCustomBiomeEquivalents(null).isEmpty());
    assertTrue(TreeSpecies.leafCustomBiomeEquivalents("").isEmpty());
  }
}
