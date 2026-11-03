package net.leaf.treegen.common.command;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import net.leaf.treegen.common.Platform;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import net.leaf.treegen.common.TreeSpecies;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Platform-agnostic {@code /leaftree} command tree built on the dailystruggle
 * {@link io.github.dailystruggle.commandsapi.common.CommandsAPI CommandsAPI} model.
 *
 * <p>This is the single source of truth for command <em>behaviour</em> across Paper/Folia, Fabric,
 * and NeoForge. Platform adapters register it via {@link Platform#registerCommands(Object, String...)}.
 */
public final class LeafTreeGenCommand extends AbstractLeafCommand {

    public LeafTreeGenCommand(Platform platform,
                              Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook) {
        this(configRef,
             registryRef,
             generator,
             reloadHook,
             msg -> platform.sendMessage(null, msg, false),
             (callerId, msg) -> platform.sendMessage(callerId, msg, false),
             platform::getCallerLocation,
             new SaplingGiver() {
                 @Override
                 public boolean give(UUID callerId, String playerName, String speciesId, int amount) {
                     return give(callerId, playerName, speciesId, amount, null);
                 }

                 @Override
                 public boolean give(UUID callerId, String playerName, String speciesId, int amount, String variant) {
                     TreeGenConfig cfg = configRef.get();
                     if (cfg == null) return false;
                     TreeSpecies species = cfg.get(speciesId);
                     if (species == null) return false;
                     return platform.giveSapling(callerId, playerName, species, amount, variant);
                 }
             },
             platform);
    }

    public LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook,
                              Consumer<String> feedback) {
        this(configRef, registryRef, generator, reloadHook, feedback, CallerLocation.Resolver.NONE);
    }

    public LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook,
                              Consumer<String> feedback,
                              CallerLocation.Resolver callerLocation) {
        this(configRef, registryRef, generator, reloadHook, feedback, callerLocation, SaplingGiver.NONE);
    }

    public LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook,
                              Consumer<String> feedback,
                              CallerLocation.Resolver callerLocation,
                              SaplingGiver saplingGiver) {
        this(configRef, registryRef, generator, reloadHook, feedback,
             (callerId, msg) -> { if (feedback != null) feedback.accept(msg); },
             callerLocation, saplingGiver, null);
    }

    private LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                               Supplier<TreeRegistry> registryRef,
                               ProceduralGenerator generator,
                               Runnable reloadHook,
                               Consumer<String> feedback,
                               BiConsumer<UUID, String> callerFeedback,
                               CallerLocation.Resolver callerLocation,
                               SaplingGiver saplingGiver,
                               Platform platform) {
        super("leaftree", "leaftreegen.admin", "LeafTreeGen root command.", null, feedback, callerFeedback);
        addSubCommand(new GenerateSub(this, configRef, generator, feedback, callerFeedback, callerLocation));
        addSubCommand(new GiveSub(this, configRef, registryRef, platform, feedback, callerFeedback,
                saplingGiver == null ? SaplingGiver.NONE : saplingGiver));
        addSubCommand(new ListSub(this, configRef, registryRef, platform, feedback, callerFeedback));
        addSubCommand(new ReloadSub(this, reloadHook, feedback, callerFeedback));
        addSubCommand(new DebugSub(this, configRef, platform, feedback, callerFeedback));
        addSubCommand(new StatsSub(this, platform, feedback, callerFeedback));
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        // Root with no subcommand: print usage. Sub-command dispatch is handled by TreeCommand.
        if (nextCommand == null) {
            reply(callerId, "Usage: /leaftree <generate|give|list|reload|debug|stats> ...");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Sub-commands
    // ------------------------------------------------------------------

    private static final class GenerateSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;
        private final ProceduralGenerator generator;
        private final CallerLocation.Resolver callerLocation;

        GenerateSub(CommandsAPICommand parent, Supplier<TreeGenConfig> configRef,
                    ProceduralGenerator generator, Consumer<String> feedback,
                    BiConsumer<UUID, String> callerFeedback,
                    CallerLocation.Resolver callerLocation) {
            super("generate", "leaftreegen.admin", "Generate a tree at x,y,z (default ~ ~ ~, i.e. the caller's location, or origin 0,64,0 from console; supports relative ~ and ~n).", parent, feedback, callerFeedback);
            this.configRef = configRef;
            this.generator = generator;
            this.callerLocation = callerLocation == null ? CallerLocation.Resolver.NONE : callerLocation;
            addParameter("species", new SpeciesParameter(configRef));
            addParameter("world", new FreeParameter("leaftreegen.admin", "World name"));
            addParameter("x", new FreeParameter("leaftreegen.admin", "X coordinate"));
            addParameter("y", new FreeParameter("leaftreegen.admin", "Y coordinate"));
            addParameter("z", new FreeParameter("leaftreegen.admin", "Z coordinate"));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { reply(callerId, "Config not loaded."); return true; }
            List<String> speciesArg = parameterValues.get("species");
            if (speciesArg == null || speciesArg.isEmpty()) { reply(callerId, "Usage: /leaftree generate species=<id>"); return true; }
            String id = speciesArg.get(0);
            TreeSpecies species = cfg.get(id);
            if (species == null) { reply(callerId, "Unknown species: " + id); return true; }

            CallerLocation loc = callerLocation.apply(callerId);
            int baseX = loc != null ? loc.x() : 0;
            int baseY = loc != null ? loc.y() : 64;
            int baseZ = loc != null ? loc.z() : 0;
            String defaultWorld = loc != null && loc.world() != null ? loc.world() : "world";

            String world = firstOr(parameterValues.get("world"), defaultWorld);
            int x = relativeOr(parameterValues.get("x"), baseX);
            int y = relativeOr(parameterValues.get("y"), baseY);
            int z = relativeOr(parameterValues.get("z"), baseZ);

            generator.generate(world, x, y, z, species, new Random());
            reply(callerId, "Generated " + species.displayName() + " at " + x + "," + y + "," + z + " in " + world + ".");
            return true;
        }
    }

    private static final class GiveSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;
        private final Supplier<TreeRegistry> registryRef;
        private final Platform platform;
        private final SaplingGiver saplingGiver;

        GiveSub(CommandsAPICommand parent,
                Supplier<TreeGenConfig> configRef,
                Supplier<TreeRegistry> registryRef,
                Platform platform,
                Consumer<String> feedback,
                BiConsumer<UUID, String> callerFeedback,
                SaplingGiver saplingGiver) {
            super("give", "leaftreegen.admin", "Give tree saplings.", parent, feedback, callerFeedback);
            this.configRef = configRef;
            this.registryRef = registryRef;
            this.platform = platform;
            this.saplingGiver = saplingGiver == null ? SaplingGiver.NONE : saplingGiver;
            addParameter("species", new SpeciesParameter(configRef));
            addParameter("player", new OnlinePlayerParameter(platform));
            addParameter("amount", new IntegerParameter("leaftreegen.admin", "Amount", (u, v) -> true, 1, 64));
            addParameter("variant", new VariantParameter(configRef, registryRef, platform));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { reply(callerId, "Config not loaded."); return true; }
            List<String> speciesArg = parameterValues.get("species");
            if (speciesArg == null || speciesArg.isEmpty()) {
                reply(callerId, "Usage: /leaftree give species=<id> [player=<name>] [amount=<n>] [variant=<key>]");
                return true;
            }
            String id = speciesArg.get(0);
            TreeSpecies species = cfg.get(id);
            if (species == null) { reply(callerId, "Unknown species: " + id); return true; }
            int amount = intOr(parameterValues.get("amount"), 1);
            List<String> playerArg = parameterValues.get("player");
            String playerName = playerArg == null || playerArg.isEmpty() ? null : playerArg.get(0);

            String variant = null;
            List<String> variantArg = parameterValues.get("variant");
            if (variantArg != null && !variantArg.isEmpty() && !variantArg.get(0).isBlank()) {
                String requested = variantArg.get(0).trim();
                TreeRegistry registry = registryRef != null ? registryRef.get() : null;
                if (registry != null) {
                    String tplWorld = platform != null ? platform.resolveTemplateWorld(null) : "world";
                    List<String> available = registry.variantsFor(tplWorld, species);
                    variant = available.stream()
                            .filter(v -> v.equals(requested) || v.endsWith("/" + requested) || v.endsWith(":" + requested))
                            .findFirst()
                            .orElse(null);
                    if (variant == null) {
                        reply(callerId, "Unknown variant '" + requested + "' for " + species.displayName()
                                + ". Available: " + String.join(", ", available));
                        return true;
                    }
                } else {
                    variant = requested;
                }
            }

            boolean delivered = saplingGiver.give(callerId, playerName, species.id(), amount, variant);
            String target = playerName != null ? playerName : "(self)";
            if (delivered) {
                reply(callerId, "Gave " + amount + "x " + species.displayName()
                        + (variant != null ? " (" + variant + ")" : "") + " to " + target + ".");
            } else {
                reply(callerId, "Could not give " + species.displayName() + " to " + target
                        + " (player not online, or this platform has no sapling item).");
            }
            return true;
        }
    }

    private static final class ListSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;
        private final Supplier<TreeRegistry> registryRef;
        private final Platform platform;

        ListSub(CommandsAPICommand parent,
                Supplier<TreeGenConfig> configRef,
                Supplier<TreeRegistry> registryRef,
                Platform platform,
                Consumer<String> feedback,
                BiConsumer<UUID, String> callerFeedback) {
            super("list", "leaftreegen.admin", "List tree species.", parent, feedback, callerFeedback);
            this.configRef = configRef;
            this.registryRef = registryRef;
            this.platform = platform;
            addParameter("species", new SpeciesParameter(configRef));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { reply(callerId, "Config not loaded."); return true; }
            List<String> speciesArg = parameterValues.get("species");
            if (speciesArg != null && !speciesArg.isEmpty()) {
                TreeSpecies species = cfg.get(speciesArg.get(0));
                if (species == null) { reply(callerId, "Unknown species: " + speciesArg.get(0)); return true; }
                TreeRegistry registry = registryRef != null ? registryRef.get() : null;
                String tplWorld = platform != null ? platform.resolveTemplateWorld(null) : "world";
                List<String> variants = registry != null ? registry.variantsFor(tplWorld, species) : List.of();
                if (variants.isEmpty()) {
                    reply(callerId, species.id() + ": " + species.displayName());
                } else {
                    reply(callerId, species.id() + ": " + species.displayName() + " (variants: " + String.join(", ", variants) + ")");
                }
            } else {
                reply(callerId, "Species: " + String.join(", ", cfg.ids()));
            }
            return true;
        }
    }

    private static final class ReloadSub extends AbstractLeafCommand {
        private final Runnable reloadHook;

        ReloadSub(CommandsAPICommand parent, Runnable reloadHook,
                  Consumer<String> feedback, BiConsumer<UUID, String> callerFeedback) {
            super("reload", "leaftreegen.admin", "Reload the config.", parent, feedback, callerFeedback);
            this.reloadHook = reloadHook;
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            reloadHook.run();
            reply(callerId, "LeafTreeGen reloaded.");
            return true;
        }
    }

    private static final class DebugSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;
        private final Platform platform;

        DebugSub(CommandsAPICommand parent, Supplier<TreeGenConfig> configRef, Platform platform,
                 Consumer<String> feedback, BiConsumer<UUID, String> callerFeedback) {
            super("debug", "leaftreegen.admin", "Dump debug status.", parent, feedback, callerFeedback);
            this.configRef = configRef;
            this.platform = platform;
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { reply(callerId, "Config not loaded."); return true; }
            reply(callerId, "--- LeafTreeGen Debug ---");
            reply(callerId, "Generation Mode: " + cfg.mode());
            reply(callerId, "Active Species: " + cfg.ids().size());
            for (String id : cfg.ids()) {
                TreeSpecies s = cfg.get(id);
                if (s != null) {
                    reply(callerId, "- " + s.id() + ": biomes=" + s.biomes().size()
                            + ", worldgen=" + s.worldgen() + ", replace=" + s.replaceVanilla());
                }
            }
            if (platform != null) {
                reply(callerId, "Worlds: " + platform.getWorldNames());
            }
            return true;
        }
    }

    private static final class StatsSub extends AbstractLeafCommand {
        private final Platform platform;

        StatsSub(CommandsAPICommand parent, Platform platform,
                 Consumer<String> feedback, BiConsumer<UUID, String> callerFeedback) {
            super("stats", "leaftreegen.admin", "Show tick budget consumption stats.", parent, feedback, callerFeedback);
            this.platform = platform;
            addParameter("reset", new BooleanParameter("leaftreegen.admin", "Reset stats", (s, v) -> true));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            if (platform == null) {
                reply(callerId, "Stats are unavailable on this platform.");
                return true;
            }
            List<String> resetArg = parameterValues.get("reset");
            if (resetArg != null && !resetArg.isEmpty() && Boolean.parseBoolean(resetArg.get(0))) {
                platform.resetStats();
                reply(callerId, "Stats reset.");
                return true;
            }

            long blocks = platform.getTotalBlocksPlaced();
            long timeNanos = platform.getTotalTimeSpentNanos();
            long batches = platform.getTotalBatches();
            long exceeded = platform.getBudgetExceededCount();
            long chunksLoaded = platform.getTotalChunksLoaded();
            long newChunks = platform.getTotalNewChunksGenerated();
            double chunkWindowMs = platform.getChunkLoadWindowNanos() / 1_000_000.0;
            double totalWindowMs = platform.getTotalCompletionWindowNanos() / 1_000_000.0;

            double timeMs = timeNanos / 1_000_000.0;
            double avgTimeBatch = batches > 0 ? timeMs / batches : 0.0;
            double avgBlocksBatch = batches > 0 ? (double) blocks / batches : 0.0;

            reply(callerId, "--- LeafTreeGen Tick Budget Stats ---");
            reply(callerId, "Chunks Loaded: " + chunksLoaded + " (New: " + newChunks + ")");
            reply(callerId, String.format(java.util.Locale.ROOT, "Chunk Load Window: %.2f ms", chunkWindowMs));
            reply(callerId, String.format(java.util.Locale.ROOT, "Total Completion Window: %.2f ms", totalWindowMs));
            reply(callerId, "Total Blocks Placed: " + blocks);
            reply(callerId, String.format(java.util.Locale.ROOT, "Total Time Spent: %.2f ms", timeMs));
            reply(callerId, "Total Batches: " + batches);
            reply(callerId, "Budget Exceeded Events: " + exceeded);
            reply(callerId, String.format(java.util.Locale.ROOT, "Avg Time per Batch: %.2f ms", avgTimeBatch));
            reply(callerId, String.format(java.util.Locale.ROOT, "Avg Blocks per Batch: %.2f", avgBlocksBatch));
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------

    /** Species parameter; values are the live config ids (drives tab-completion). */
    private static final class SpeciesParameter extends CommandParameter {
        private final Supplier<TreeGenConfig> configRef;

        SpeciesParameter(Supplier<TreeGenConfig> configRef) {
            super("leaftreegen.admin", "Species", (u, v) -> true);
            this.configRef = configRef;
        }

        @Override
        public Set<String> values() {
            TreeGenConfig cfg = configRef.get();
            return cfg == null ? new HashSet<>() : new HashSet<>(cfg.ids());
        }
    }

    /** Online player parameter backed by {@link Platform#getOnlinePlayerNames()}. */
    private static final class OnlinePlayerParameter extends CommandParameter {
        private final Platform platform;

        OnlinePlayerParameter(Platform platform) {
            super("leaftreegen.admin", "Target player", (u, v) -> true);
            this.platform = platform;
        }

        @Override
        public Set<String> values() {
            if (platform == null) return Collections.emptySet();
            Set<String> names = platform.getOnlinePlayerNames();
            return names == null ? Collections.emptySet() : new HashSet<>(names);
        }
    }

    /** Variant parameter tab-completing available structure variants across configured species. */
    private static final class VariantParameter extends CommandParameter {
        private final Supplier<TreeGenConfig> configRef;
        private final Supplier<TreeRegistry> registryRef;
        private final Platform platform;

        VariantParameter(Supplier<TreeGenConfig> configRef,
                         Supplier<TreeRegistry> registryRef,
                         Platform platform) {
            super("leaftreegen.admin", "Template variant", (u, v) -> true);
            this.configRef = configRef;
            this.registryRef = registryRef;
            this.platform = platform;
        }

        @Override
        public Set<String> values() {
            TreeGenConfig cfg = configRef != null ? configRef.get() : null;
            TreeRegistry reg = registryRef != null ? registryRef.get() : null;
            if (cfg == null || reg == null) return Collections.emptySet();
            String tplWorld = platform != null ? platform.resolveTemplateWorld(null) : "world";
            Set<String> out = new HashSet<>();
            for (TreeSpecies s : cfg.species().values()) {
                out.addAll(reg.variantsFor(tplWorld, s));
                if (s.treeDefinitions() != null) {
                    out.addAll(s.treeDefinitions().keySet());
                }
                if (s.trees() != null) {
                    for (TreeSpecies.WeightedSpecies ws : s.trees()) {
                        if (ws != null && ws.id() != null) out.add(ws.id());
                    }
                }
            }
            return out;
        }
    }

    /** A free-form parameter that accepts any value and offers no fixed suggestions. */
    private static final class FreeParameter extends CommandParameter {
        FreeParameter(String permission, String description) {
            super(permission, description, (u, v) -> true);
        }

        @Override
        public Set<String> values() {
            return new HashSet<>();
        }
    }

    // ------------------------------------------------------------------
    // Helpers shared by sub-commands
    // ------------------------------------------------------------------

    private static String firstOr(List<String> values, String fallback) {
        return (values == null || values.isEmpty()) ? fallback : values.get(0);
    }

    private static int intOr(List<String> values, int fallback) {
        if (values == null || values.isEmpty()) return fallback;
        try {
            return Integer.parseInt(values.get(0).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Resolves a coordinate argument supporting Minecraft-style relative syntax.
     *
     * <p>The default is {@code ~}: an omitted, empty/blank, or unparseable argument is
     * treated as relative and yields {@code base}. {@code ~} yields {@code base},
     * {@code ~n} yields {@code base + n} (n may be negative), and a bare integer is taken
     * as absolute.
     */
    private static int relativeOr(List<String> values, int base) {
        if (values == null || values.isEmpty()) return base;
        String s = values.get(0).trim();
        if (s.isEmpty()) return base;
        try {
            if (s.startsWith("~")) {
                String rest = s.substring(1).trim();
                return rest.isEmpty() ? base : base + Integer.parseInt(rest);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return base;
        }
    }
}
