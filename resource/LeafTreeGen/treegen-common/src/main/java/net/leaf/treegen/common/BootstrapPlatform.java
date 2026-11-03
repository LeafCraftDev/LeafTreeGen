package net.leaf.treegen.common;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Minimal {@link Platform} implementation used during pre-world bootstrap datapack generation.
 */
public final class BootstrapPlatform implements Platform {

    private final Logger logger;
    private final Path rootFolder;

    public BootstrapPlatform(Logger logger, Path rootFolder) {
        this.logger = logger;
        this.rootFolder = rootFolder;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Path getRootFolder() {
        return rootFolder;
    }

    @Override
    public Path getWorldFolder(String worldName) {
        return Paths.get(worldName);
    }

    @Override
    public List<String> getWorldNames() {
        return List.of();
    }

    @Override
    public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        throw new UnsupportedOperationException("scheduleRepeatingTask is unavailable during bootstrap");
    }

    @Override
    public String getBiomeKey(String worldName, int x, int y, int z) {
        throw new UnsupportedOperationException("getBiomeKey is unavailable during bootstrap");
    }

    @Override
    public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
        throw new UnsupportedOperationException("placeStructure is unavailable during bootstrap");
    }

    @Override
    public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
        throw new UnsupportedOperationException("setBlock is unavailable during bootstrap");
    }

    @Override
    public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
        throw new UnsupportedOperationException("setBlocks is unavailable during bootstrap");
    }

    @Override
    public void sendMessage(UUID playerId, String message, boolean error) {
        if (error) {
            logger.warning(message);
        } else {
            logger.info(message);
        }
    }
}
