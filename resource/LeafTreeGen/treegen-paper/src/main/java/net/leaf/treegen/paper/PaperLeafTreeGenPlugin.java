package net.leaf.treegen.paper;

import io.github.dailystruggle.rtp.bukkitplatform.server.BukkitPluginServerAccessor;
import net.leaf.treegen.common.LeafTreeGenRuntime;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Paper / Folia plugin entry point for LeafTreeGen, delegating platform services to
 * {@link BukkitPluginServerAccessor} and domain lifecycle to {@link LeafTreeGenRuntime}.
 */
public final class PaperLeafTreeGenPlugin extends JavaPlugin {

    private BukkitPluginServerAccessor accessor;
    private LeafTreeGenRuntime runtime;

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        this.accessor = new BukkitPluginServerAccessor(this, "leaf-treegen");
        this.runtime = new LeafTreeGenRuntime(this.accessor);
        this.runtime.load();
    }

    @Override
    public void onEnable() {
        if (this.accessor == null || this.runtime == null) {
            this.accessor = new BukkitPluginServerAccessor(this, "leaf-treegen");
            this.runtime = new LeafTreeGenRuntime(this.accessor);
        }
        this.accessor.registerEvents();
        this.runtime.start();
        getLogger().info("LeafTreeGen (Paper) enabled.");
    }
}
