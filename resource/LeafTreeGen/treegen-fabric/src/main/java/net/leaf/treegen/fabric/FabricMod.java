package net.leaf.treegen.fabric;

import io.github.dailystruggle.rtp.fabric.unobf.server.FabricModServerAccessor;
import net.fabricmc.api.ModInitializer;
import net.leaf.treegen.common.LeafTreeGenRuntime;

import java.nio.file.Path;

/**
 * Fabric mod entry point for LeafTreeGen, delegating platform services to
 * {@link FabricModServerAccessor} and domain lifecycle to {@link LeafTreeGenRuntime}.
 */
public final class FabricMod implements ModInitializer {

    private LeafTreeGenRuntime runtime;

    @Override
    public void onInitialize() {
        FabricModServerAccessor accessor =
                new FabricModServerAccessor("LeafTreeGen", Path.of("config", "leaf-treegen"));
        accessor.registerEvents();
        this.runtime = new LeafTreeGenRuntime(accessor);
        this.runtime.start();
        accessor.logger().info("LeafTreeGen (Fabric) initialized.");
    }
}
