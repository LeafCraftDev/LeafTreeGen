package net.leaf.treegen.common;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Platform-agnostic helper for the "leaves drop tagged saplings" feature.
 *
 * <p>It knows which block ids a species uses as leaves (from its procedural
 * params) and can resolve, from a broken/decayed leaf block plus the biome it
 * sits in, which custom species the leaf most likely belongs to. This is the
 * biome-based half of the hybrid resolution; the precise half is handled by the
 * platform-side farm registry that records where trees actually grew.
 */
public final class SaplingDropResolver {

    private SaplingDropResolver() {
    }

    /**
     * Normalise a block-state string (e.g. {@code "minecraft:oak_leaves[distance=1]"})
     * to its base, namespaced block id ({@code "minecraft:oak_leaves"}).
     */
    public static String baseBlockId(String state) {
        if (state == null) return null;
        String s = state.split("\\[")[0].trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (!s.contains(":")) s = "minecraft:" + s;
        return s;
    }

    /**
     * The set of leaf block ids a species places: its primary leaf block plus any
     * secondary and underside leaves. Empty when the species has no procedural
     * definition (e.g. purely baked-NBT variants).
     */
    public static Set<String> leafBlocksOf(TreeSpecies species) {
        Set<String> out = new LinkedHashSet<>();
        if (species == null) return out;
        if (species.procedural() != null) {
            collectLeavesFromParams(out, species.procedural());
        }
        if (species.treeDefinitions() != null) {
            for (TreeSpecies.ProceduralParams p : species.treeDefinitions().values()) {
                collectLeavesFromParams(out, p);
            }
        }
        return out;
    }

    private static void collectLeavesFromParams(Set<String> out, TreeSpecies.ProceduralParams p) {
        if (p == null) return;
        addLeaf(out, p.leafBlock());
        if (p.canopy() != null) {
            addLeaf(out, p.canopy().secondaryLeaves());
            if (p.canopy().secondaryLeafList() != null) {
                for (TreeSpecies.CanopyParams.WeightedBlock wb : p.canopy().secondaryLeafList()) {
                    if (wb != null) addLeaf(out, wb.block());
                }
            }
            addLeaf(out, p.canopy().undersideLeaves());
        }
    }

    private static boolean matchesSpeciesId(String targetId, String candidateId) {
        if (targetId == null || candidateId == null) return false;
        if (targetId.equalsIgnoreCase(candidateId)) return true;
        return targetId.replace('-', '_').equalsIgnoreCase(candidateId.replace('-', '_'));
    }

    private static Set<String> leafBlocksOfWithGroups(TreeSpecies s, Collection<TreeSpecies> allSpecies) {
        Set<String> out = new LinkedHashSet<>(leafBlocksOf(s));
        if (s != null && s.trees() != null && allSpecies != null) {
            for (TreeSpecies.WeightedSpecies ws : s.trees()) {
                if (ws == null || ws.id() == null) continue;
                String targetId = ws.id().contains(":") ? ws.id().substring(0, ws.id().indexOf(':')) : ws.id();
                for (TreeSpecies candidate : allSpecies) {
                    if (candidate != null && matchesSpeciesId(targetId, candidate.id()) && candidate != s) {
                        out.addAll(leafBlocksOf(candidate));
                    }
                }
            }
        }
        return out;
    }

    private static void addLeaf(Set<String> out, String block) {
        String id = baseBlockId(block);
        if (id != null) out.add(id);
    }

    /**
     * Rough horizontal radius (in blocks) of a species' canopy, used to size the
     * tracked farm region around a planted/placed tree. Always at least 4.
     */
    public static int estimateCanopyRadius(TreeSpecies species) {
        return estimateCanopyRadius(species, null);
    }

    /**
     * Rough horizontal radius (in blocks) of a species' canopy, traversing referenced
     * child species when {@code species} is a group wrapper and {@code allSpecies} is supplied.
     */
    public static int estimateCanopyRadius(TreeSpecies species, Collection<TreeSpecies> allSpecies) {
        int r = 4;
        if (species == null) return r;
        if (species.procedural() != null) {
            r = Math.max(r, estimateRadiusForParams(species.procedural()));
        }
        if (species.treeDefinitions() != null) {
            for (TreeSpecies.ProceduralParams p : species.treeDefinitions().values()) {
                r = Math.max(r, estimateRadiusForParams(p));
            }
        }
        if (species.trees() != null && allSpecies != null) {
            r = Math.max(r, estimateGroupCanopyRadius(species, allSpecies));
        }
        return r;
    }

    private static int estimateGroupCanopyRadius(TreeSpecies species, Collection<TreeSpecies> allSpecies) {
        int r = 4;
        for (TreeSpecies.WeightedSpecies ws : species.trees()) {
            if (ws == null || ws.id() == null) continue;
            String[] parts = ws.id().split(":", 2);
            for (TreeSpecies candidate : allSpecies) {
                if (candidate == null || candidate == species || !matchesSpeciesId(parts[0], candidate.id())) {
                    continue;
                }
                TreeSpecies.ProceduralParams named = (parts.length > 1 && candidate.treeDefinitions() != null)
                        ? candidate.treeDefinitions().get(parts[1])
                        : null;
                r = Math.max(r, named != null ? estimateRadiusForParams(named) : estimateCanopyRadius(candidate, null));
            }
        }
        return r;
    }

    private static int estimateRadiusForParams(TreeSpecies.ProceduralParams p) {
        int r = 4;
        if (p == null) return r;
        int maxH = Math.max(1, p.maxHeight());
        double leanAngleDeg = Math.min(45.0, Math.max(0.0, p.leanAngle()));
        int leanOffset = leanAngleDeg > 0.0
                ? (int) Math.ceil(maxH * Math.tan(Math.toRadians(leanAngleDeg)))
                : 0;
        int trunkHalfWidth = (int) Math.ceil(Math.max(1.0, p.trunkWidth()) / 2.0);
        int canopyReach = p.canopy() != null ? estimateCanopyReach(p, maxH, p.canopy()) : 0;
        return Math.max(r, trunkHalfWidth + leanOffset + canopyReach);
    }

    private static int estimateCanopyReach(
            TreeSpecies.ProceduralParams p, int maxH, TreeSpecies.CanopyParams c) {
        int canopyReach = 0;
        if (c.layers() != null && !c.layers().isEmpty()) {
            for (TreeSpecies.CanopyParams.Layer l : c.layers()) {
                canopyReach = Math.max(canopyReach, (int) Math.ceil(l.radius()) + 1);
            }
        } else if (c.volumeLayers()) {
            double scale = presetProfileScale(p.profile());
            int presetBaseRadius = Math.max(3, (int) Math.round(maxH / 2.0 * scale));
            canopyReach = Math.max(canopyReach, presetBaseRadius + 1);
        }
        if (c.branches() != null) {
            TreeSpecies.BranchParams b = c.branches();
            double branchLen = Math.max(0.0, b.maxLength());
            double subLen = (b.subBranches() != null && b.subBranches().count() > 0)
                    ? branchLen * Math.max(0.0, b.subBranches().lengthScale())
                    : 0.0;
            double clusterR = Math.max(
                    Math.max(0.0, b.clusterRadius()),
                    b.subBranches() != null ? Math.max(0.0, b.subBranches().clusterRadius()) : 0.0);
            canopyReach = Math.max(canopyReach, (int) Math.ceil(branchLen + subLen + clusterR) + 2);
        }
        return canopyReach;
    }

    private static double presetProfileScale(String rawProfile) {
        String profile = rawProfile != null ? rawProfile.toUpperCase(Locale.ROOT) : "OAK";
        return switch (profile) {
            case "BIRCH" -> 0.7;
            case "SPRUCE", "PINE", "TAIGA" -> 0.55;
            case "JUNGLE", "MEGA_JUNGLE" -> 0.45;
            case "ACACIA" -> 0.6;
            case "DARK_OAK_FLAT" -> 1.25;
            default -> 1.0;
        };
    }

    /**
     * Rough total height (in blocks) of a species, used to size the tracked farm
     * region above a planted/placed tree. Always at least 12.
     */
    public static int estimateHeight(TreeSpecies species) {
        return estimateHeight(species, null);
    }

    /**
     * Rough total height (in blocks) of a species, traversing referenced child species
     * when {@code species} is a group wrapper and {@code allSpecies} is supplied.
     */
    public static int estimateHeight(TreeSpecies species, Collection<TreeSpecies> allSpecies) {
        int h = 12;
        if (species == null) return h;
        int radius = estimateCanopyRadius(species, allSpecies);
        if (species.procedural() != null) {
            h = Math.max(h, species.procedural().maxHeight() + radius + 2);
        }
        if (species.treeDefinitions() != null) {
            for (TreeSpecies.ProceduralParams p : species.treeDefinitions().values()) {
                if (p != null) {
                    h = Math.max(h, p.maxHeight() + radius + 2);
                }
            }
        }
        if (species.trees() != null && allSpecies != null) {
            for (TreeSpecies.WeightedSpecies ws : species.trees()) {
                if (ws == null || ws.id() == null) continue;
                String targetId = ws.id().contains(":") ? ws.id().substring(0, ws.id().indexOf(':')) : ws.id();
                for (TreeSpecies candidate : allSpecies) {
                    if (candidate != null && candidate != species && matchesSpeciesId(targetId, candidate.id())) {
                        h = Math.max(h, estimateHeight(candidate, null));
                    }
                }
            }
        }
        return h;
    }

    /**
     * Resolve which species a broken leaf block belongs to, using only the biome.
     * A species matches when it uses {@code brokenBlock} as a leaf and is allowed
     * in {@code biomeKey}. Returns {@code null} when nothing matches.
     */
    public static TreeSpecies resolveByBiome(Collection<TreeSpecies> species, String biomeKey, String brokenBlock) {
        String target = baseBlockId(brokenBlock);
        if (target == null || species == null) return null;
        for (TreeSpecies s : species) {
            if (s != null && s.allowsBiome(biomeKey) && leafBlocksOfWithGroups(s, species).contains(target)) {
                return s;
            }
        }
        return null;
    }
}
