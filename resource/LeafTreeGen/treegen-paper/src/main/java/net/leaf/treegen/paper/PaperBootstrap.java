package net.leaf.treegen.paper;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import net.leaf.treegen.common.LeafTreeGenRuntime;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

/**
 * Paper bootstrap entry point delegating pre-world datapack generation to {@link LeafTreeGenRuntime}.
 */
public final class PaperBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        LeafTreeGenRuntime.bootstrapDatapacks(
                context.getDataDirectory(), Logger.getLogger("LeafTreeGen"));
    }
}
