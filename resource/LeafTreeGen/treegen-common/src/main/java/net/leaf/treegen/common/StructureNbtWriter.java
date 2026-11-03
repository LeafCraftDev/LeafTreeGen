package net.leaf.treegen.common;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a {@link TreeModel}'s blocks to a vanilla structure-template {@code .nbt}
 * file (gzip-compressed NBT), the format consumed by {@code /place template},
 * structure blocks, and jigsaw {@code template_pool} pieces.
 *
 * <p>This mirrors the reference {@code tools/tree-gen/nbt.py:write_structure_nbt}
 * implementation so the pure-datapack placement path produces the same on-disk
 * layout the prior (Python-baked) pipeline relied on. Layout:
 *
 * <pre>
 *     DataVersion : int
 *     size        : [int, int, int]   (W, H, L)
 *     palette     : [ {Name: str, Properties?: {str: str}} ]
 *     blocks      : [ {state: int, pos: [int, int, int]} ]
 *     entities    : []
 * </pre>
 *
 * <p>Block positions are normalised to the model's minimum corner, exactly as the
 * reference writer does. Only the blocks present are emitted; unfilled positions
 * remain structure void (untouched on placement).
 */
public final class StructureNbtWriter {

    private static final byte TAG_END = 0;
    private static final byte TAG_INT = 3;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;

    private StructureNbtWriter() {
    }

    /**
     * Writes the given blocks to {@code file} as a gzip-compressed vanilla
     * structure template.
     *
     * @param file        destination {@code .nbt} file (parent dirs are created)
     * @param blocks      block-state map keyed by model-local position
     * @param dataVersion Minecraft DataVersion to stamp into the template
     */
    public static void write(Path file, Map<TreeModel.BlockPos, String> blocks, int dataVersion) throws IOException {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks map is empty");
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (TreeModel.BlockPos p : blocks.keySet()) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int length = maxZ - minZ + 1;

        // Build the palette (de-duplicated by Name + sorted Properties) and the
        // per-block entries (relative position + palette index).
        List<String[]> palette = new ArrayList<>();   // each: [name, propsKey] (propsKey unused after dedup map)
        List<Map<String, String>> paletteProps = new ArrayList<>();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<int[]> blockEntries = new ArrayList<>(); // x, y, z, stateIndex

        for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
            String state = e.getValue();
            String name = parseName(state);
            Map<String, String> props = parseProperties(state);
            String key = name + "|" + canonicalProps(props);
            Integer idx = paletteIndex.get(key);
            if (idx == null) {
                idx = palette.size();
                paletteIndex.put(key, idx);
                palette.add(new String[]{name});
                paletteProps.add(props);
            }
            TreeModel.BlockPos p = e.getKey();
            blockEntries.add(new int[]{p.x() - minX, p.y() - minY, p.z() - minZ, idx});
        }

        ByteArrayOutputStream rootBytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(rootBytes);

        // Root: unnamed compound.
        out.writeByte(TAG_COMPOUND);
        writeName(out, "");

        writeNamedInt(out, "DataVersion", dataVersion);

        // size : list of int [W, H, L]
        out.writeByte(TAG_LIST);
        writeName(out, "size");
        out.writeByte(TAG_INT);
        out.writeInt(3);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(length);

        // palette : list of compound
        out.writeByte(TAG_LIST);
        writeName(out, "palette");
        out.writeByte(TAG_COMPOUND);
        out.writeInt(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            // Name
            out.writeByte(TAG_STRING);
            writeName(out, "Name");
            writeString(out, palette.get(i)[0]);
            // Properties (optional)
            Map<String, String> props = paletteProps.get(i);
            if (!props.isEmpty()) {
                out.writeByte(TAG_COMPOUND);
                writeName(out, "Properties");
                for (Map.Entry<String, String> pe : props.entrySet()) {
                    out.writeByte(TAG_STRING);
                    writeName(out, pe.getKey());
                    writeString(out, pe.getValue());
                }
                out.writeByte(TAG_END);
            }
            out.writeByte(TAG_END);
        }

        // blocks : list of compound
        out.writeByte(TAG_LIST);
        writeName(out, "blocks");
        out.writeByte(TAG_COMPOUND);
        out.writeInt(blockEntries.size());
        for (int[] be : blockEntries) {
            // pos : list of int [x, y, z]
            out.writeByte(TAG_LIST);
            writeName(out, "pos");
            out.writeByte(TAG_INT);
            out.writeInt(3);
            out.writeInt(be[0]);
            out.writeInt(be[1]);
            out.writeInt(be[2]);
            // state : int
            writeNamedInt(out, "state", be[3]);
            out.writeByte(TAG_END);
        }

        // entities : empty compound list
        out.writeByte(TAG_LIST);
        writeName(out, "entities");
        out.writeByte(TAG_COMPOUND);
        out.writeInt(0);

        // End of root compound.
        out.writeByte(TAG_END);
        out.flush();

        Files.createDirectories(file.getParent());
        try (OutputStream fileOut = Files.newOutputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(fileOut)) {
            gzip.write(rootBytes.toByteArray());
        }
    }

    private static void writeNamedInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(TAG_INT);
        writeName(out, name);
        out.writeInt(value);
    }

    private static void writeName(DataOutputStream out, String name) throws IOException {
        writeString(out, name);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(raw.length);
        out.write(raw);
    }

    static String parseName(String state) {
        int bracket = state.indexOf('[');
        return bracket >= 0 ? state.substring(0, bracket) : state;
    }

    static Map<String, String> parseProperties(String state) {
        Map<String, String> props = new LinkedHashMap<>();
        int bracket = state.indexOf('[');
        if (bracket < 0) return props;
        String rest = state.substring(bracket + 1);
        if (rest.endsWith("]")) rest = rest.substring(0, rest.length() - 1);
        for (String part : rest.split(",")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return props;
    }

    private static String canonicalProps(Map<String, String> props) {
        if (props.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }
}
