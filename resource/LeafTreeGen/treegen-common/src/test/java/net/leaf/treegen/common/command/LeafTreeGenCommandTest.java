package net.leaf.treegen.common.command;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.server.api.BookMenuSpec;
import io.github.dailystruggle.server.api.TaggedItemSpec;
import net.leaf.treegen.common.Platform;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeModel;
import net.leaf.treegen.common.TreeRegistry;
import net.leaf.treegen.common.TreeSpecies;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Unit tests for {@link LeafTreeGenCommand} verifying pure {@link CommandsAPI} tree structure,
 * zero {@code String[] args} interception, tab completion across all subcommands and parameters,
 * and end-to-end execution of all clickable {@link BookMenuSpec} commands.
 *
 * @governedBy treegen-ADR-001
 * @governedBy treegen-ADR-002
 * @governedBy treegen-ADR-003
 */
class LeafTreeGenCommandTest {

    @TempDir
    Path tempDir;

    private TestCommandPlatform platform;
    private TreeRegistry registry;
    private TreeGenConfig config;
    private ProceduralGenerator generator;
    private TreeSpecies oakSpecies;
    private TreeSpecies pineSpecies;
    private AtomicBoolean reloaded;
    private LeafTreeGenCommand root;

    @BeforeEach
    void setUp() throws Exception {
        CommandsAPI.commandPipeline.clear();
        platform = new TestCommandPlatform(tempDir);
        registry = new TreeRegistry(platform);

        TreeSpecies.ProceduralParams oakParams = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log",
                "minecraft:oak_leaves",
                6,
                6,
                "OAK",
                1.0,
                "CONSTANT",
                Map.of(),
                false,
                0.0,
                0.0,
                "CONSTANT",
                Map.of(),
                "LINEAR",
                Map.of(),
                null,
                0.5,
                1.0,
                new TreeSpecies.CanopyParams(
                        "DENSITY",
                        1.0,
                        List.of(new TreeSpecies.CanopyParams.Layer(5, 2.0)),
                        null,
                        null,
                        0.0,
                        true,
                        1.0,
                        null,
                        null,
                        1.0),
                false,
                0,
                0,
                List.of());
        oakSpecies = new TreeSpecies(
                "custom-oak",
                "Custom Oak",
                "leaf",
                "oak",
                Set.of("minecraft:forest"),
                "OAK_SAPLING",
                true,
                true,
                2,
                1,
                1001,
                "linear",
                "surface_structures",
                List.of(
                        new TreeSpecies.TreeVariant("leaf:oak/oak_v1", 1),
                        new TreeSpecies.TreeVariant("leaf:oak/oak_v2", 1)),
                oakParams,
                null,
                Map.of("oak_tall", oakParams),
                2);

        pineSpecies = new TreeSpecies(
                "glacierpine",
                "Glacier Pine",
                "leaf",
                "pine",
                Set.of("minecraft:snowy_taiga"),
                "SPRUCE_SAPLING",
                true,
                false,
                3,
                1,
                1002,
                "linear",
                "surface_structures",
                List.of(),
                oakParams,
                null,
                null,
                1);

        Map<String, TreeSpecies> speciesMap = new LinkedHashMap<>();
        speciesMap.put(oakSpecies.id(), oakSpecies);
        speciesMap.put(pineSpecies.id(), pineSpecies);

        config = new TreeGenConfig(
                speciesMap,
                "leaf-treegen-generated",
                "Test pack",
                TreeGenConfig.GenerationMode.BOTH,
                5.0,
                1000
        );
        generator = new ProceduralGenerator(platform, registry);
        generator.setConfigSpecies(speciesMap);
        reloaded = new AtomicBoolean(false);
        root = new LeafTreeGenCommand(
                platform,
                () -> config,
                () -> registry,
                generator,
                () -> reloaded.set(true)
        );
    }

    @Test
    @DisplayName("Command tree uses pure CommandsAPI structure without intercepting String[] args")
    void commandTreeIsPureCommandsApiWithoutStringArgsInterception() throws Exception {
        Assertions.assertEquals("leaftree", root.name());
        Assertions.assertEquals("leaftreegen.admin", root.permission());
        Assertions.assertNotNull(root.description());
        Assertions.assertFalse(root.description().isBlank());
        Assertions.assertNull(root.parent());
        Assertions.assertEquals(0L, root.avgTime());

        // Verify neither root, AbstractLeafCommand, nor any subcommand overrides the raw String[] args onCommand methods
        assertDoesNotInterceptRawArgs(LeafTreeGenCommand.class);
        assertDoesNotInterceptRawArgs(AbstractLeafCommand.class);

        Map<String, CommandsAPICommand> subCommands = root.getCommandLookup();
        Assertions.assertEquals(
                Set.of("GENERATE", "GIVE", "LIST", "RELOAD", "DEBUG", "STATS"),
                subCommands.keySet()
        );

        for (Map.Entry<String, CommandsAPICommand> entry : subCommands.entrySet()) {
            CommandsAPICommand sub = entry.getValue();
            Assertions.assertNotNull(sub);
            Assertions.assertEquals(entry.getKey(), sub.name().toUpperCase(Locale.ROOT));
            Assertions.assertEquals("leaftreegen.admin", sub.permission());
            Assertions.assertNotNull(sub.description());
            Assertions.assertFalse(sub.description().isBlank());
            Assertions.assertSame(root, sub.parent());
            Assertions.assertEquals(0L, sub.avgTime());
            assertDoesNotInterceptRawArgs(sub.getClass());

            if (sub instanceof TreeCommand treeSub) {
                for (Map.Entry<String, CommandParameter> paramEntry : treeSub.getParameterLookup().entrySet()) {
                    CommandParameter param = paramEntry.getValue();
                    Assertions.assertNotNull(param);
                    Assertions.assertEquals("leaftreegen.admin", param.permission());
                    Assertions.assertNotNull(param.description());
                    Assertions.assertFalse(param.description().isBlank());
                    Assertions.assertNotNull(param.values());
                }
            }
        }

        // Bare positional arguments must NOT be intercepted or rewritten; TreeCommand routes them to msgInvalidCommand
        UUID caller = UUID.randomUUID();
        String[][] positionalAttempts = {
                {"generate", "custom-oak"},
                {"give", "custom-oak", "Alice", "4"},
                {"list", "custom-oak"},
                {"stats", "true"}
        };
        for (String[] attempt : positionalAttempts) {
            platform.messages.clear();
            CompletableFuture<Boolean> fut = executeCommand(caller, p -> true, attempt);
            Assertions.assertFalse(
                    fut.join(),
                    "Bare positional args must be rejected by TreeCommand: " + Arrays.toString(attempt)
            );
            Assertions.assertTrue(
                    platform.messages.stream().anyMatch(m -> m.contains("Unknown subcommand: ")),
                    "Expected Unknown subcommand message for positional args " + Arrays.toString(attempt)
                            + ", got: " + platform.messages
            );
        }
    }

    @Test
    @DisplayName("Tab completion covers root subcommands, permissions, and all subcommand parameters")
    void tabCompletionCoversSubcommandsAndParameters() {
        UUID caller = UUID.randomUUID();

        // Root tab completion with admin permission
        List<String> rootTabs = root.onTabComplete(caller, p -> true, new String[]{""});
        Assertions.assertTrue(rootTabs.containsAll(List.of("generate", "give", "list", "reload", "debug", "stats", "help")));

        // Root tab completion prefix filtering
        List<String> gTabs = root.onTabComplete(caller, p -> true, new String[]{"g"});
        Assertions.assertTrue(gTabs.containsAll(List.of("generate", "give")));
        Assertions.assertFalse(gTabs.contains("list"));

        // Root tab completion without permission hides admin subcommands
        List<String> noPermTabs = root.onTabComplete(caller, p -> false, new String[]{""});
        Assertions.assertEquals(List.of("help"), noPermTabs);

        // /leaftree generate tab completion
        List<String> genParams = root.onTabComplete(caller, p -> true, new String[]{"generate", ""});
        Assertions.assertTrue(genParams.containsAll(List.of("species=", "world=", "x=", "y=", "z=", "help")));

        List<String> genSpecies = root.onTabComplete(caller, p -> true, new String[]{"generate", "species="});
        Assertions.assertTrue(genSpecies.containsAll(List.of("species=custom-oak", "species=glacierpine")));

        List<String> genSpeciesFiltered = root.onTabComplete(caller, p -> true, new String[]{"generate", "species=glacier"});
        Assertions.assertEquals(List.of("species=glacierpine"), genSpeciesFiltered);

        List<String> genWorlds = root.onTabComplete(caller, p -> true, new String[]{"generate", "world="});
        Assertions.assertTrue(genWorlds.containsAll(List.of("world=world", "world=world_nether")));

        List<String> genCoordX = root.onTabComplete(caller, p -> true, new String[]{"generate", "x="});
        Assertions.assertTrue(genCoordX.containsAll(List.of("x=~", "x=-~", "x=0")));

        List<String> genAfterSpecies = root.onTabComplete(
                caller, p -> true, new String[]{"generate", "species=custom-oak", ""});
        Assertions.assertFalse(genAfterSpecies.contains("species="));
        Assertions.assertTrue(genAfterSpecies.containsAll(List.of("world=", "x=", "y=", "z=")));

        // /leaftree give tab completion
        List<String> giveParams = root.onTabComplete(caller, p -> true, new String[]{"give", ""});
        Assertions.assertTrue(giveParams.containsAll(List.of("species=", "player=", "amount=", "variant=", "help")));

        List<String> giveSpecies = root.onTabComplete(caller, p -> true, new String[]{"give", "species="});
        Assertions.assertTrue(giveSpecies.containsAll(List.of("species=custom-oak", "species=glacierpine")));

        List<String> givePlayers = root.onTabComplete(caller, p -> true, new String[]{"give", "player="});
        Assertions.assertTrue(givePlayers.containsAll(List.of("player=Alice", "player=Bob")));

        List<String> giveAmounts = root.onTabComplete(caller, p -> true, new String[]{"give", "amount="});
        Assertions.assertTrue(giveAmounts.containsAll(List.of("amount=1", "amount=8", "amount=16", "amount=32", "amount=64")));

        List<String> giveVariants = root.onTabComplete(caller, p -> true, new String[]{"give", "variant="});
        Assertions.assertTrue(giveVariants.contains("variant=leaf:oak/oak_v1"));
        Assertions.assertTrue(giveVariants.contains("variant=leaf:oak/oak_v2"));
        Assertions.assertTrue(giveVariants.contains("variant=oak_tall"));

        // /leaftree list tab completion
        List<String> listParams = root.onTabComplete(caller, p -> true, new String[]{"list", ""});
        Assertions.assertTrue(listParams.containsAll(List.of("species=", "help")));

        List<String> listSpecies = root.onTabComplete(caller, p -> true, new String[]{"list", "species="});
        Assertions.assertTrue(listSpecies.containsAll(List.of("species=custom-oak", "species=glacierpine")));

        // /leaftree stats tab completion
        List<String> statsParams = root.onTabComplete(caller, p -> true, new String[]{"stats", ""});
        Assertions.assertTrue(statsParams.containsAll(List.of("reset=", "help")));

        List<String> statsReset = root.onTabComplete(caller, p -> true, new String[]{"stats", "reset="});
        Assertions.assertTrue(statsReset.containsAll(List.of("reset=true", "reset=false")));

        // /leaftree reload and /leaftree debug tab completion
        Assertions.assertEquals(List.of("help"), root.onTabComplete(caller, p -> true, new String[]{"reload", ""}));
        Assertions.assertEquals(List.of("help"), root.onTabComplete(caller, p -> true, new String[]{"debug", ""}));
    }

    @Test
    @DisplayName("Clickable commands emitted across /leaftree subcommands use pure commands-api format and execute cleanly")
    void clickableCommandsUsePureCommandsApiFormatAndExecuteCleanly() {
        UUID player = UUID.randomUUID();
        platform.callerLocations.put(player, new CallerLocation("world", 20, 70, -10));

        // 1. Trigger interactive lines from root, list, list species=<id>, debug, and stats
        Assertions.assertTrue(executeCommand(player, p -> true).join());
        Assertions.assertTrue(executeCommand(player, p -> true, "list").join());
        Assertions.assertTrue(executeCommand(player, p -> true, "list", "species=custom-oak").join());
        Assertions.assertTrue(executeCommand(player, p -> true, "debug").join());
        Assertions.assertTrue(executeCommand(player, p -> true, "stats").join());

        Assertions.assertFalse(platform.interactiveLines.isEmpty(), "Expected interactive clickable lines for player caller");

        List<BookMenuSpec.Fragment> clickableFragments = platform.interactiveLines.stream()
                .flatMap(line -> line.fragments().stream())
                .filter(f -> f.clickAction() == BookMenuSpec.ClickAction.RUN_COMMAND
                        || f.clickAction() == BookMenuSpec.ClickAction.SUGGEST_COMMAND)
                .toList();
        Assertions.assertFalse(clickableFragments.isEmpty(), "Expected RUN_COMMAND fragments in interactive lines");

        Set<String> executedClickCommands = new LinkedHashSet<>();
        Map<String, CommandsAPICommand> subLookup = root.getCommandLookup();

        for (BookMenuSpec.Fragment fragment : clickableFragments) {
            String clickValue = fragment.clickValue();
            Assertions.assertNotNull(clickValue);
            Assertions.assertTrue(
                    clickValue.startsWith("/leaftree "),
                    "Clickable command must start with '/leaftree ': " + clickValue
            );
            Assertions.assertNotNull(fragment.hover());
            Assertions.assertFalse(fragment.hover().isBlank(), "Clickable fragment must include hover tooltip: " + clickValue);

            String[] tokens = clickValue.substring("/leaftree ".length()).trim().split("\\s+");
            Assertions.assertTrue(tokens.length >= 1, "Clickable command must include subcommand token: " + clickValue);

            CommandsAPICommand targetSub = subLookup.get(tokens[0].toUpperCase(Locale.ROOT));
            Assertions.assertNotNull(
                    targetSub,
                    "First token of clickable command must be a registered subcommand: " + clickValue
            );

            // Every subsequent token must be a key=value parameter registered on the target subcommand
            if (targetSub instanceof TreeCommand treeSub) {
                for (int i = 1; i < tokens.length; i++) {
                    String token = tokens[i];
                    int eq = token.indexOf('=');
                    Assertions.assertTrue(
                            eq > 0 && eq < token.length() - 1,
                            "Clickable command argument must be in key=value format: '" + token + "' in " + clickValue
                    );
                    String paramKey = token.substring(0, eq).toLowerCase(Locale.ROOT);
                    Assertions.assertTrue(
                            treeSub.getParameterLookup().containsKey(paramKey),
                            "Clickable command parameter '" + paramKey + "' must be registered on " + targetSub.name()
                    );
                }
            }

            // Execute the clickable command through LeafTreeGenCommand#onCommand + CommandsAPI.execute()
            CompletableFuture<Boolean> clickFuture = executeCommand(player, p -> true, tokens);
            Assertions.assertTrue(
                    clickFuture.join(),
                    "Clickable command execution must succeed: " + clickValue
            );
            executedClickCommands.add(clickValue);
        }

        // Verify all expected clickable command targets were discovered and executed
        Assertions.assertTrue(executedClickCommands.contains("/leaftree list"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree stats"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree debug"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree reload"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree list species=custom-oak"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree list species=glacierpine"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree give species=custom-oak"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree generate species=custom-oak"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree give species=custom-oak variant=leaf:oak/oak_v1"));
        Assertions.assertTrue(executedClickCommands.contains("/leaftree stats reset=true"));

        // Verify side effects of executing the clickable commands
        Assertions.assertTrue(reloaded.get(), "Clicking /leaftree reload must invoke reloadHook");
        Assertions.assertFalse(platform.givenItems.isEmpty(), "Clicking /leaftree give chips must deliver saplings");
        Assertions.assertTrue(
                platform.givenItems.stream().anyMatch(item -> "leaf:oak/oak_v1".equals(item.customTags().get(Platform.TAG_TREE_VARIANT))),
                "Clicking variant chip must deliver sapling tagged with leaf:oak/oak_v1"
        );
        Assertions.assertFalse(platform.placedBlocks.isEmpty(), "Clicking /leaftree generate chip must place tree blocks");

        // Console caller must not receive interactive clickable chat lines
        platform.interactiveLines.clear();
        Assertions.assertTrue(executeCommand(CommandsAPI.serverId, p -> true).join());
        Assertions.assertTrue(executeCommand(CommandsAPI.serverId, p -> true, "list").join());
        Assertions.assertTrue(platform.interactiveLines.isEmpty(), "Console caller must not receive interactive lines");
    }

    @Test
    @DisplayName("Subcommand execution covers coordinates, variants, error handling, permissions, and Brigadier bridge")
    void subcommandExecutionErrorHandlingAndBrigadierBridge() throws Exception {
        UUID caller = UUID.randomUUID();
        platform.callerLocations.put(caller, new CallerLocation("world", 10, 64, 20));

        // 1. Generate with relative (~, ~n, -~n) and explicit world coordinates
        platform.messages.clear();
        Assertions.assertTrue(executeCommand(
                caller, p -> true,
                "generate", "species=custom-oak", "world=world_nether", "x=~5", "y=~-4", "z=-~2"
        ).join());
        Assertions.assertTrue(
                platform.messages.stream().anyMatch(m -> m.contains("Generated Custom Oak at 15,60,-22 in world_nether.")),
                "Expected relative coordinate resolution in world_nether, got: " + platform.messages
        );

        // 2. Generate missing species / unknown species
        platform.messages.clear();
        Assertions.assertTrue(executeCommand(caller, p -> true, "generate").join());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Usage: /leaftree generate species=<id>")));

        platform.messages.clear();
        Assertions.assertTrue(executeCommand(caller, p -> true, "generate", "species=nonexistent").join());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Unknown species: nonexistent")));

        // 3. Give with unknown variant, offline player failure, and valid suffix variant
        platform.messages.clear();
        Assertions.assertTrue(executeCommand(
                caller, p -> true,
                "give", "species=custom-oak", "variant=missing_var"
        ).join());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Unknown variant 'missing_var'")));

        platform.giveItemResult = false;
        platform.messages.clear();
        Assertions.assertTrue(executeCommand(
                caller, p -> true,
                "give", "species=custom-oak", "player=OfflineUser", "amount=8", "variant=oak_v2"
        ).join());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Could not give Custom Oak to OfflineUser")));
        platform.giveItemResult = true;

        // 4. Bad parameters and permission rejection
        platform.messages.clear();
        executeCommand(caller, p -> true, "give", "species=custom-oak", "amount=999");
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Invalid value for amount: 999")));

        platform.messages.clear();
        executeCommand(caller, p -> true, "stats", "reset=notabool");
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Invalid value for reset: notabool")));

        platform.messages.clear();
        executeCommand(caller, p -> true, "generate", "species=custom-oak", "x=notacoord");
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Invalid value for x: notacoord")));

        platform.messages.clear();
        Assertions.assertFalse(executeCommand(caller, p -> false, "reload").join());
        Assertions.assertTrue(platform.messages.stream().anyMatch(m -> m.contains("Missing permission: leaftreegen.admin")));

        // 5. Help command output
        List<String> helpLines = new ArrayList<>();
        root.onCommand(caller, p -> true, helpLines::add, new String[]{"help"});
        CommandsAPI.execute();
        Assertions.assertTrue(helpLines.stream().anyMatch(l -> l.contains("Subcommands:")));

        // 6. Alternative constructors (Consumer<String> feedback + CallerLocation.Resolver + SaplingGiver)
        List<String> directFeedback = new ArrayList<>();
        AtomicBoolean customGiverCalled = new AtomicBoolean(false);
        LeafTreeGenCommand standaloneCmd = new LeafTreeGenCommand(
                () -> config,
                () -> registry,
                generator,
                () -> reloaded.set(true),
                directFeedback::add,
                id -> new CallerLocation("world", 4, 65, 8),
                (callerId, playerName, speciesId, amount) -> {
                    customGiverCalled.set(true);
                    return true;
                }
        );
        standaloneCmd.onCommand(caller, p -> true, directFeedback::add, new String[]{"give", "species=custom-oak", "amount=2"});
        CommandsAPI.execute();
        Assertions.assertTrue(customGiverCalled.get(), "Custom SaplingGiver must be invoked by standalone constructor");
        Assertions.assertTrue(directFeedback.stream().anyMatch(m -> m.contains("Gave 2x Custom Oak")));
    }

    private CompletableFuture<Boolean> executeCommand(UUID callerId, Predicate<String> permCheck, String... args) {
        Consumer<String> msgSink = msg -> platform.sendMessage(callerId, msg, false);
        CompletableFuture<Boolean> future = root.onCommand(callerId, permCheck, msgSink, args);
        CommandsAPI.execute();
        return future;
    }

    private static void assertDoesNotInterceptRawArgs(Class<?> clazz) throws NoSuchMethodException {
        Method shortForm = clazz.getMethod(
                "onCommand", UUID.class, Predicate.class, Consumer.class, String[].class);
        Method indexedForm = clazz.getMethod(
                "onCommand", UUID.class, Predicate.class, Consumer.class, String[].class, int.class, Map.class);
        Assertions.assertEquals(
                TreeCommand.class,
                shortForm.getDeclaringClass(),
                clazz.getSimpleName() + " must not override onCommand(..., String[] args)"
        );
        Assertions.assertEquals(
                TreeCommand.class,
                indexedForm.getDeclaringClass(),
                clazz.getSimpleName() + " must not override onCommand(..., String[] args, int, Map)"
        );
    }

    private static final class TestCommandPlatform implements Platform {
        private final Path rootFolder;
        private final Logger log = Logger.getLogger("LeafTreeGenCommandTest");
        private final List<String> messages = new ArrayList<>();
        private final List<BookMenuSpec.Line> interactiveLines = new ArrayList<>();
        private final List<TaggedItemSpec> givenItems = new ArrayList<>();
        private final Map<TreeModel.BlockPos, String> placedBlocks = new LinkedHashMap<>();
        private final Map<UUID, CallerLocation> callerLocations = new LinkedHashMap<>();
        private boolean giveItemResult = true;

        TestCommandPlatform(Path rootFolder) throws Exception {
            this.rootFolder = Files.createDirectories(rootFolder.resolve("plugin"));
        }

        @Override
        public Logger logger() {
            return log;
        }

        @Override
        public Path getRootFolder() {
            return rootFolder;
        }

        @Override
        public Path getWorldFolder(String worldName) {
            return rootFolder.resolve(worldName == null ? "world" : worldName);
        }

        @Override
        public List<String> getWorldNames() {
            return List.of("world", "world_nether");
        }

        @Override
        public Set<String> getOnlinePlayerNames() {
            return Set.of("Alice", "Bob");
        }

        @Override
        public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        }

        @Override
        public String getBiomeKey(String worldName, int x, int y, int z) {
            return "minecraft:forest";
        }

        @Override
        public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
            return true;
        }

        @Override
        public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
            placedBlocks.put(new TreeModel.BlockPos(x, y, z), blockData);
            return true;
        }

        @Override
        public boolean setBlocks(String worldName, int chunkX, int chunkZ, Map<TreeModel.BlockPos, String> blocks) {
            placedBlocks.putAll(blocks);
            return true;
        }

        @Override
        public boolean isReplaceable(String worldName, int x, int y, int z) {
            return true;
        }

        @Override
        public String getBlockState(String worldName, int x, int y, int z) {
            return placedBlocks.getOrDefault(new TreeModel.BlockPos(x, y, z), "minecraft:air");
        }

        @Override
        public void sendMessage(UUID playerId, String message, boolean error) {
            messages.add(message);
        }

        @Override
        public void sendInteractiveLine(UUID player, BookMenuSpec.Line line) {
            if (line != null) {
                interactiveLines.add(line);
                messages.add(line.plainText());
            }
        }

        @Override
        public boolean giveItem(UUID fallbackPlayerId, String playerName, TaggedItemSpec item) {
            if (!giveItemResult) {
                return false;
            }
            givenItems.add(item);
            return true;
        }

        @Override
        public CallerLocation getCallerLocation(UUID callerId) {
            return callerLocations.get(callerId);
        }
    }
}
