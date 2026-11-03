package net.leaf.treegen.neoforge;

import io.github.dailystruggle.rtp.neoforge.v26_1_R1.server.NeoForgeModServerAccessor;
import net.leaf.treegen.common.LeafTreeGenRuntime;
import net.neoforged.fml.common.Mod;

import java.nio.file.Path;

/**
 * NeoForge mod entry point for LeafTreeGen, delegating platform services to
 * {@link NeoForgeModServerAccessor} and domain lifecycle to {@link LeafTreeGenRuntime}.
 */
@Mod("leaf_treegen")
public final class NeoForgeMod {

    private final LeafTreeGenRuntime runtime;

    public NeoForgeMod() {
        NeoForgeModServerAccessor accessor =
                new NeoForgeModServerAccessor("LeafTreeGen", Path.of("config", "leaf-treegen"));
        accessor.registerEvents();
        this.runtime = new LeafTreeGenRuntime(accessor);
        this.runtime.start();
        accessor.logger().info("LeafTreeGen (NeoForge) initialized.");
    }
}
