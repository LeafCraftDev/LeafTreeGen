package net.leaf.treegen.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Round-trips a model through {@link StructureNbtWriter} and a minimal NBT reader
 * to confirm the emitted binary matches the vanilla structure-template layout.
 */
public class StructureNbtWriterTest {

    @Test
    public void testRoundTripStructureNbt() throws IOException {
        Map<TreeModel.BlockPos, String> blocks = new LinkedHashMap<>();
        // Offset away from origin to exercise min-corner normalisation.
        blocks.put(new TreeModel.BlockPos(5, 10, -3), "minecraft:mushroom_stem");
        blocks.put(new TreeModel.BlockPos(5, 11, -3), "minecraft:oak_log[axis=y]");
        blocks.put(new TreeModel.BlockPos(6, 11, -3), "minecraft:oak_leaves[distance=1,persistent=false]");
        blocks.put(new TreeModel.BlockPos(5, 12, -3), "minecraft:mushroom_stem"); // dedup with palette[0]

        Path file = Files.createTempFile("structure", ".nbt");
        StructureNbtWriter.write(file, blocks, 3578);

        Map<String, Object> root = readGzippedNbt(file);

        Assertions.assertEquals(3578, ((Number) root.get("DataVersion")).intValue());

        List<?> size = (List<?>) root.get("size");
        Assertions.assertEquals(List.of(2, 3, 1),
                List.of(((Number) size.get(0)).intValue(),
                        ((Number) size.get(1)).intValue(),
                        ((Number) size.get(2)).intValue()),
                "size must be the bounding box (W,H,L)");

        List<?> palette = (List<?>) root.get("palette");
        Assertions.assertEquals(3, palette.size(), "duplicate mushroom_stem must collapse into one palette entry");

        @SuppressWarnings("unchecked")
        Map<String, Object> stem = (Map<String, Object>) palette.get(0);
        Assertions.assertEquals("minecraft:mushroom_stem", stem.get("Name"));
        Assertions.assertFalse(stem.containsKey("Properties"),
                "mushroom_stem (no block-state) must have no Properties tag");

        @SuppressWarnings("unchecked")
        Map<String, Object> log = (Map<String, Object>) palette.get(1);
        Assertions.assertEquals("minecraft:oak_log", log.get("Name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> logProps = (Map<String, Object>) log.get("Properties");
        Assertions.assertEquals("y", logProps.get("axis"));

        List<?> blockList = (List<?>) root.get("blocks");
        Assertions.assertEquals(4, blockList.size());
        Assertions.assertTrue(root.containsKey("entities"));
    }

    // --- minimal NBT reader (only the tags this writer emits) ---

    private static Map<String, Object> readGzippedNbt(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            int type = in.readUnsignedByte();
            Assertions.assertEquals(10, type, "root must be a compound");
            readString(in); // root name ("")
            return readCompound(in);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) break; // TAG_END
            String name = readString(in);
            map.put(name, readPayload(in, type));
        }
        return map;
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 3: // int
                return in.readInt();
            case 8: // string
                return readString(in);
            case 9: { // list
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType));
                }
                return list;
            }
            case 10: // compound
                return readCompound(in);
            default:
                throw new IOException("unexpected tag type " + type);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] raw = new byte[len];
        in.readFully(raw);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
