package com.onthehill.chunkyfriends.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.config.ConfigSupport;
import com.onthehill.chunkyfriends.player.PlayerPregenState;
import com.onthehill.chunkyfriends.scheduler.ActiveJobSnapshot;
import com.onthehill.chunkyfriends.scheduler.PregenScheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Server-side command surface for the NeoForge 1.21.1 port. */
public final class ChunkyFriendsCommand {
    private ChunkyFriendsCommand() {
    }

    public static void register(
            final CommandDispatcher<CommandSourceStack> dispatcher,
            final Supplier<ChunkyFriendsConfig> configSupplier,
            final Supplier<PregenScheduler> schedulerSupplier,
            final Runnable onCurveChanged) {
        dispatcher.register(literal("chunkyfriends")
                .requires(ChunkyFriendsCommand::hasPermission)
                .executes(context -> status(context, configSupplier, schedulerSupplier))
                .then(literal("status")
                        .executes(context -> status(context, configSupplier, schedulerSupplier)))
                .then(literal("players")
                        .executes(context -> players(context, configSupplier, schedulerSupplier)))
                .then(literal("config")
                        .executes(context -> showCurrent(context, configSupplier))
                        .then(literal("ringcount")
                                .then(argument("value", integer(ConfigSupport.MIN_RING_COUNT, ConfigSupport.MAX_RING_COUNT))
                                        .executes(context -> setRingCount(context, configSupplier, onCurveChanged))))
                        .then(literal("maxradius")
                                .then(argument("value", word())
                                        .executes(context -> setMaxRadius(context, configSupplier, onCurveChanged))))
                        .then(literal("curve")
                                .then(literal("linear")
                                        .executes(context -> setCurve(context, configSupplier, false, onCurveChanged)))
                                .then(literal("quadratic")
                                        .executes(context -> setCurve(context, configSupplier, true, onCurveChanged)))))
                .then(literal("reset")
                        .executes(context -> resetProgress(context, schedulerSupplier))));
    }

    private static boolean hasPermission(final CommandSourceStack source) {
        return source.getServer().isSingleplayer() || source.hasPermission(2);
    }

    private static int showCurrent(
            final CommandContext<CommandSourceStack> context,
            final Supplier<ChunkyFriendsConfig> configSupplier) {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireConfig(context, config)) {
            return 0;
        }

        final String curve = ConfigSupport.isQuadratic(config.getCurveExponent()) ? "quadratic" : "linear";
        context.getSource().sendSuccess(() -> Component.translatableWithFallback(
                "command.chunky_friends.config.current",
                "Ring count: %s, max radius: %s blocks (%s chunks), curve: %s",
                config.getRingCount(),
                config.getMaxRadiusChunks() * ConfigSupport.BLOCKS_PER_CHUNK,
                config.getMaxRadiusChunks(),
                curve), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setRingCount(
            final CommandContext<CommandSourceStack> context,
            final Supplier<ChunkyFriendsConfig> configSupplier,
            final Runnable onCurveChanged) {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireConfig(context, config)) {
            return 0;
        }
        final boolean applied = ConfigSupport.applyUpdate(
                config,
                getInteger(context, "value"),
                config.getMaxRadiusChunks(),
                ConfigSupport.isQuadratic(config.getCurveExponent()),
                onCurveChanged);
        return respondToConfigUpdate(context, applied);
    }

    private static int setMaxRadius(
            final CommandContext<CommandSourceStack> context,
            final Supplier<ChunkyFriendsConfig> configSupplier,
            final Runnable onCurveChanged) {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireConfig(context, config)) {
            return 0;
        }

        final OptionalInt parsed = ConfigSupport.parseRadiusChunks(getString(context, "value"));
        if (parsed.isEmpty()) {
            context.getSource().sendFailure(Component.translatableWithFallback("message.chunky_friends.config.invalid_radius_format", "Could not parse that radius. Use blocks (e.g. 8000) or chunks with c (e.g. 500c)."));
            return 0;
        }

        final boolean applied = ConfigSupport.applyUpdate(
                config,
                config.getRingCount(),
                parsed.getAsInt(),
                ConfigSupport.isQuadratic(config.getCurveExponent()),
                onCurveChanged);
        return respondToConfigUpdate(context, applied);
    }

    private static int setCurve(
            final CommandContext<CommandSourceStack> context,
            final Supplier<ChunkyFriendsConfig> configSupplier,
            final boolean quadratic,
            final Runnable onCurveChanged) {
        final ChunkyFriendsConfig config = configSupplier.get();
        if (!requireConfig(context, config)) {
            return 0;
        }
        final boolean applied = ConfigSupport.applyUpdate(
                config,
                config.getRingCount(),
                config.getMaxRadiusChunks(),
                quadratic,
                onCurveChanged);
        return respondToConfigUpdate(context, applied);
    }

    private static int status(
            final CommandContext<CommandSourceStack> context,
            final Supplier<ChunkyFriendsConfig> configSupplier,
            final Supplier<PregenScheduler> schedulerSupplier) {
        final ChunkyFriendsConfig config = configSupplier.get();
        final PregenScheduler scheduler = schedulerSupplier.get();
        if (!requireScheduler(context, config, scheduler)) {
            return 0;
        }

        final Optional<ActiveJobSnapshot> snapshot = scheduler.activeJobSnapshot();
        if (snapshot.isEmpty()) {
            final int eligibleCount = scheduler.eligiblePlayers(System.currentTimeMillis()).size();
            context.getSource().sendSuccess(
                    () -> Component.translatableWithFallback("command.chunky_friends.status.idle", "No Chunky Friends pregeneration job is active. %s player(s) are eligible.", eligibleCount),
                    false);
            return Command.SINGLE_SUCCESS;
        }

        final ActiveJobSnapshot job = snapshot.get();
        final String displayName = job.playerDisplayName() != null
                ? job.playerDisplayName()
                : job.playerUuid().toString();

        MutableComponent message = Component.translatableWithFallback(
                "command.chunky_friends.status.active",
                "Active pregeneration: %s — ring %s/%s, %s%% complete (%s chunks, %s chunks/s) in %s",
                displayName,
                job.ringTier(),
                job.ringCount(),
                job.progressPercent(),
                job.chunks(),
                job.chunksPerSecond(),
                job.world());
        if (job.presencePaused()) {
            message = message.append(Component.translatableWithFallback("command.chunky_friends.status.paused_suffix", " (paused because a player is online)"));
        }
        final MutableComponent finalMessage = message;
        context.getSource().sendSuccess(() -> finalMessage, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int players(
            final CommandContext<CommandSourceStack> context,
            final Supplier<ChunkyFriendsConfig> configSupplier,
            final Supplier<PregenScheduler> schedulerSupplier) {
        final ChunkyFriendsConfig config = configSupplier.get();
        final PregenScheduler scheduler = schedulerSupplier.get();
        if (!requireScheduler(context, config, scheduler)) {
            return 0;
        }

        final List<PlayerPregenState> eligible = scheduler.eligiblePlayers(System.currentTimeMillis());
        if (eligible.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.translatableWithFallback("command.chunky_friends.players.none", "No players are currently eligible."), false);
            return Command.SINGLE_SUCCESS;
        }

        final UUID activePlayerUuid = scheduler.activeJobSnapshot()
                .map(ActiveJobSnapshot::playerUuid)
                .orElse(null);
        final MutableComponent output = Component.translatableWithFallback(
                "command.chunky_friends.players.header", "%s eligible player(s):", eligible.size()).copy();

        for (final PlayerPregenState state : eligible) {
            final String displayName = state.getLastKnownName() != null
                    ? state.getLastKnownName()
                    : state.getPlayerUuid().toString();
            MutableComponent entry = Component.translatableWithFallback(
                    "command.chunky_friends.players.entry",
                    "%s — ring %s/%s",
                    displayName,
                    state.getCurrentRingTier(),
                    config.getRingCount());
            if (state.getPlayerUuid().equals(activePlayerUuid)) {
                entry = entry.append(Component.translatableWithFallback("command.chunky_friends.players.active_suffix", " (active)"));
            }
            output.append(Component.literal("\n")).append(entry);
        }

        context.getSource().sendSuccess(() -> output, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int resetProgress(
            final CommandContext<CommandSourceStack> context,
            final Supplier<PregenScheduler> schedulerSupplier) {
        final PregenScheduler scheduler = schedulerSupplier.get();
        if (scheduler == null) {
            context.getSource().sendFailure(Component.translatableWithFallback("message.chunky_friends.not_ready", "Chunky Friends has not finished starting yet. Try again in a moment."));
            return 0;
        }
        scheduler.resetAllProgress();
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("message.chunky_friends.reset", "Reset all tracked pregeneration ring progress."), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int respondToConfigUpdate(
            final CommandContext<CommandSourceStack> context,
            final boolean applied) {
        if (applied) {
            context.getSource().sendSuccess(
                    () -> Component.translatableWithFallback("message.chunky_friends.config.saved", "Chunky Friends configuration updated."), true);
            return Command.SINGLE_SUCCESS;
        }
        context.getSource().sendFailure(Component.translatableWithFallback("message.chunky_friends.config.invalid_values", "Those Chunky Friends configuration values are out of range."));
        return 0;
    }

    private static boolean requireConfig(
            final CommandContext<CommandSourceStack> context,
            final ChunkyFriendsConfig config) {
        if (config != null) {
            return true;
        }
        context.getSource().sendFailure(Component.translatableWithFallback("message.chunky_friends.not_ready", "Chunky Friends has not finished starting yet. Try again in a moment."));
        return false;
    }

    private static boolean requireScheduler(
            final CommandContext<CommandSourceStack> context,
            final ChunkyFriendsConfig config,
            final PregenScheduler scheduler) {
        if (config != null && scheduler != null) {
            return true;
        }
        context.getSource().sendFailure(Component.translatableWithFallback("message.chunky_friends.not_ready", "Chunky Friends has not finished starting yet. Try again in a moment."));
        return false;
    }
}
