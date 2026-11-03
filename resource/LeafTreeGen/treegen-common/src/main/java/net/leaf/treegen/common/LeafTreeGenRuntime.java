package net.leaf.treegen.common;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.server.api.ServerAccessor;
import net.leaf.treegen.common.command.LeafTreeGenCommand;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Platform-neutral runtime coordinator for LeafTreeGen across Paper/Folia, Fabric, and NeoForge.
 *
 * <p>Owns species discovery, YAML configuration loading, worldgen datapack generation,
 * {@link ProceduralGenerator}, {@link TreeFarmStore}, {@link GrowthManager},
 * {@link TreeGenEventController}, {@link TreeBlockBatcher}, and {@link LeafTreeGenCommand}
 * registration so platform modules contain only their loader entry points.
 */
public final class LeafTreeGenRuntime {

    private final Platform platform;
    private final TreeRegistry registry;
    private final DatapackGenerator datapackGenerator;
    private final ProceduralGenerator proceduralGenerator;
    private final TreeFarmStore farmStore;
    private final GrowthManager growthManager;
    private final TreeBlockBatcher blockBatcher;
    private final TreeGenEventController eventController;
    private volatile TreeGenConfig config;
    private boolean loaded;

    public LeafTreeGenRuntime(ServerAccessor accessor) {
        this.platform = Platform.from(accessor, this::blockBatcher);
        this.registry = new TreeRegistry(platform);
        this.datapackGenerator = new DatapackGenerator(platform);
        this.proceduralGenerator = new ProceduralGenerator(platform, registry);
        this.farmStore = new TreeFarmStore(platform);
        this.growthManager = new GrowthManager(platform, proceduralGenerator, farmStore, this::config);
        this.blockBatcher = new TreeBlockBatcher(platform, this::config);
        this.eventController = new TreeGenEventController(
                platform, this::config, this::registry, proceduralGenerator, growthManager, farmStore);
    }

    /**
     * Loads species definitions and {@code config.yml} from {@link Platform#getRootFolder()}.
     */
    public void load() {
        try {
            Files.createDirectories(platform.getRootFolder());
        } catch (IOException ex) {
            platform.logger().warning("Could not create data directory: " + ex.getMessage());
        }
        registry.loadSpecies();
        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        RtpYamlConfig yaml = new RtpYamlConfig(configFile);
        this.config = TreeGenConfig.fromYaml(yaml, registry, platform);
        this.proceduralGenerator.setConfigSpecies(this.config.species());
        this.loaded = true;
    }

    /**
     * Starts the runtime: ensures configuration is loaded, generates worldgen datapacks if needed,
     * subscribes {@link TreeGenEventController} to {@link Platform#getWorldEventHook()}, registers
     * {@link LeafTreeGenCommand}, and schedules the per-tick command queue drain.
     */
    public void start() {
        if (!loaded) {
            load();
        }
        generateDatapacks();
        eventController.register();
        platform.registerCommands(new LeafTreeGenCommand(
                platform, this::config, this::registry, proceduralGenerator, this::reload));
        platform.scheduleRepeatingTask(CommandsAPI::execute, 1L, 1L);
    }

    /**
     * Reloads species definitions, {@code config.yml}, and worldgen datapacks.
     */
    public void reload() {
        load();
        generateDatapacks();
        platform.logger().info("LeafTreeGen reloaded.");
    }

    /**
     * Generates worldgen datapacks for all worlds reported by {@link Platform#getWorldNames()} when
     * {@link TreeGenConfig#needsDatapack()} is active.
     */
    public void generateDatapacks() {
        TreeGenConfig cfg = this.config;
        if (cfg == null) {
            return;
        }
        if (!cfg.needsDatapack()) {
            for (String world : platform.getWorldNames()) {
                if ((world.endsWith("_nether") || world.endsWith("_the_end"))
                        && !new File(world).isDirectory()) {
                    continue;
                }
                datapackGenerator.removeIfPresent(world, cfg);
            }
            return;
        }
        for (String world : platform.getWorldNames()) {
            if ((world.endsWith("_nether") || world.endsWith("_the_end"))
                    && !new File(world).isDirectory()) {
                continue;
            }
            int written = datapackGenerator.generate(world, cfg, registry);
            platform.logger().info("Generated worldgen datapack '" + cfg.packName()
                    + "' for world '" + world + "' with " + written + " species.");
        }
    }

    /**
     * Pre-world bootstrap datapack generation entry point (used before the server discovers
     * worldgen datapacks).
     */
    public static void bootstrapDatapacks(Path dataDir, Logger logger) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ex) {
            logger.severe("Could not create data directory " + dataDir + ": " + ex.getMessage());
            return;
        }

        BootstrapPlatform platform = new BootstrapPlatform(logger, dataDir);
        TreeRegistry registry = new TreeRegistry(platform);
        DatapackGenerator datapackGenerator = new DatapackGenerator(platform);

        registry.loadSpecies();

        File configFile = dataDir.resolve("config.yml").toFile();
        RtpYamlConfig yaml = new RtpYamlConfig(configFile);
        TreeGenConfig config = TreeGenConfig.fromYaml(yaml, registry, platform);
        String levelName = readLevelName(logger);
        if (!config.needsDatapack()) {
            datapackGenerator.removeIfPresent(levelName, config);
            datapackGenerator.removeIfPresent(levelName + "_nether", config);
            datapackGenerator.removeIfPresent(levelName + "_the_end", config);
            return;
        }

        generateForBootstrap(datapackGenerator, config, registry, levelName, logger, true);
        generateForBootstrap(datapackGenerator, config, registry, levelName + "_nether", logger, false);
        generateForBootstrap(datapackGenerator, config, registry, levelName + "_the_end", logger, false);
    }

    private static void generateForBootstrap(DatapackGenerator datapackGenerator, TreeGenConfig config,
                                             TreeRegistry registry, String world, Logger logger, boolean always) {
        if (!always && !new File(world).isDirectory()) {
            return;
        }
        int written = datapackGenerator.generate(world, config, registry);
        if (written > 0) {
            logger.info("Pre-generated worldgen datapack '" + config.packName()
                    + "' for world '" + world + "' (bootstrap, before datapacks load)");
        }
    }

    /** Reads {@code level-name} from {@code server.properties}, defaulting to {@code "world"}. */
    public static String readLevelName(Logger logger) {
        File props = new File("server.properties");
        if (props.exists()) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(props)) {
                p.load(in);
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            } catch (IOException ex) {
                if (logger != null) {
                    logger.warning("Could not read level-name from server.properties: " + ex.getMessage());
                }
            }
        }
        return "world";
    }

    public TreeGenConfig config() {
        return config;
    }

    public TreeRegistry registry() {
        return registry;
    }

    public ProceduralGenerator proceduralGenerator() {
        return proceduralGenerator;
    }

    public TreeFarmStore farmStore() {
        return farmStore;
    }

    public GrowthManager growthManager() {
        return growthManager;
    }

    public TreeBlockBatcher blockBatcher() {
        return blockBatcher;
    }

    public TreeGenEventController eventController() {
        return eventController;
    }
}
