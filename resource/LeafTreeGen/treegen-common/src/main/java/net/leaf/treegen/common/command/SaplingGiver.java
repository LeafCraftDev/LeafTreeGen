package net.leaf.treegen.common.command;

import java.util.UUID;

/**
 * Delivers tree saplings to a player for {@code /leaftree give}.
 *
 * <p>The platform-agnostic {@code LeafTreeGenCommand} resolves the species and amount, but the
 * actual hand-off (resolving the target player and putting the item in their inventory) is
 * platform specific, so it is delegated through this callback. Implementations return
 * {@code true} only when the item was actually delivered, so the command can give honest
 * feedback instead of always claiming success.
 */
@FunctionalInterface
public interface SaplingGiver {

    /**
     * Hands {@code amount} of the {@code speciesId} sapling to a player.
     *
     * @param callerId   the id of the command caller (used when no explicit target is given)
     * @param playerName the target player's name, or {@code null}/empty to target the caller
     * @param speciesId  the species id whose sapling item should be given
     * @param amount     how many to give
     * @return {@code true} if the item was actually delivered to a player
     */
    boolean give(UUID callerId, String playerName, String speciesId, int amount);

    /**
     * Hands {@code amount} of the {@code speciesId} sapling (optionally bound to {@code variant})
     * to a player.
     */
    default boolean give(UUID callerId, String playerName, String speciesId, int amount, String variant) {
        return give(callerId, playerName, speciesId, amount);
    }

    /** A giver that never delivers anything (platforms without a sapling item). */
    SaplingGiver NONE = (callerId, playerName, speciesId, amount) -> false;
}
