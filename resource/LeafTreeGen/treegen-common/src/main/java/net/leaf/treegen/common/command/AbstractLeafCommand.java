package net.leaf.treegen.common.command;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Common boilerplate for a {@link TreeCommand} node without any platform (Bukkit) coupling.
 * Mirrors the structure of {@code BukkitTreeCommand} but stores its metadata and lookups
 * directly so it can live in the platform-agnostic {@code common} module.
 */
abstract class AbstractLeafCommand implements TreeCommand {
    private final String name;
    private final String permission;
    private final String description;
    private final CommandsAPICommand parent;
    private final Consumer<String> feedback;
    private final BiConsumer<UUID, String> callerFeedback;
    private final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();
    private final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();

    AbstractLeafCommand(String name, String permission, String description,
                        CommandsAPICommand parent, Consumer<String> feedback) {
        this(name, permission, description, parent, feedback,
                (callerId, msg) -> { if (feedback != null) feedback.accept(msg); });
    }

    AbstractLeafCommand(String name, String permission, String description,
                        CommandsAPICommand parent, Consumer<String> feedback,
                        BiConsumer<UUID, String> callerFeedback) {
        this.name = name;
        this.permission = permission;
        this.description = description;
        this.parent = parent;
        this.feedback = feedback;
        this.callerFeedback = callerFeedback != null
                ? callerFeedback
                : (callerId, msg) -> { if (feedback != null) feedback.accept(msg); };
    }

    protected Consumer<String> feedback() {
        return feedback;
    }

    protected void reply(UUID callerId, String message) {
        callerFeedback.accept(callerId, message);
    }

    protected BiConsumer<UUID, String> callerFeedback() {
        return callerFeedback;
    }

    @Override public String name() { return name; }
    @Override public String permission() { return permission; }
    @Override public String description() { return description; }
    @Override public CommandsAPICommand parent() { return parent; }
    @Override public long avgTime() { return 0L; }
    @Override public Map<String, CommandParameter> getParameterLookup() { return parameterLookup; }
    @Override public Map<String, CommandsAPICommand> getCommandLookup() { return commandLookup; }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
        reply(callerId, "Invalid value for " + parameterName + ": " + parameterValue);
    }

    @Override
    public void msgInvalidCommand(UUID callerId, String argument) {
        reply(callerId, "Unknown subcommand: " + argument);
    }
}
