package net.leaf.treegen.common;

import io.github.dailystruggle.server.api.TaggedItemSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-neutral builder for LeafTreeGen's species-tagged sapling items.
 *
 * <p>Produces a {@link TaggedItemSpec} consumed by {@link Platform#giveItem} and
 * {@link Platform#dropItem} so platform modules need no custom sapling item classes.
 */
public final class SaplingItem {

    /** Custom-data key under which the species id is stored on the stack. */
    public static final String TREE_SPECIES_KEY = "leaf_treegen_tree_species";
    /** Custom-data key under which an optional bound variant is stored on the stack. */
    public static final String TREE_VARIANT_KEY = "leaf_treegen_tree_variant";

    private SaplingItem() {}

    /** Builds a {@link TaggedItemSpec} for {@code amount} of the species' sapling. */
    public static TaggedItemSpec create(TreeSpecies species, int amount) {
        return create(species, amount, null);
    }

    /**
     * Builds a {@link TaggedItemSpec} for {@code amount} of the species' sapling with an optional
     * bound structure variant.
     */
    public static TaggedItemSpec create(TreeSpecies species, int amount, String variant) {
        String itemKey = (species.saplingItem() != null && !species.saplingItem().isBlank())
                ? species.saplingItem()
                : "OAK_SAPLING";
        boolean hasVariant = variant != null && !variant.isBlank();

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(Platform.TAG_TREE_SPECIES, species.id());
        tags.put(TREE_SPECIES_KEY, species.id());
        if (hasVariant) {
            tags.put(Platform.TAG_TREE_VARIANT, variant);
            tags.put(TREE_VARIANT_KEY, variant);
        }

        String displayName = species.displayName() + " Sapling";
        List<String> lore = List.of(
                "A magical sapling.",
                hasVariant
                        ? "Plant it to grow a " + species.displayName() + " (" + variant + ")."
                        : "Plant it to grow a " + species.displayName() + ".");

        return new TaggedItemSpec(itemKey, Math.max(1, amount), displayName, lore, tags);
    }
}
