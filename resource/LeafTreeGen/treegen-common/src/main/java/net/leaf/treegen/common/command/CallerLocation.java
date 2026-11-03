package net.leaf.treegen.common.command;

import java.util.UUID;
import java.util.function.Function;

/**
 * A caller's live position, used as the default origin for {@code /leaftree generate} so that
 * omitted or relative ({@code ~}) coordinates presume the player's current location.
 *
 * <p>Platforms that can resolve a player position (Fabric, NeoForge) supply a
 * {@link Resolver} that maps a caller id to their location; console / unknown callers resolve
 * to {@code null}, in which case the command falls back to the world spawn-ish origin (0,64,0).
 *
 * @param world world name the caller is in, or {@code null} if unknown
 * @param x     caller block X
 * @param y     caller block Y
 * @param z     caller block Z
 */
public record CallerLocation(String world, int x, int y, int z) {

    /** Resolves a caller id to their {@link CallerLocation}, or {@code null} if unknown. */
    @FunctionalInterface
    public interface Resolver extends Function<UUID, CallerLocation> {
        /** A resolver that never knows a caller's location (e.g. pure console platforms). */
        Resolver NONE = id -> null;
    }
}
