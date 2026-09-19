package com.onthehill.chunkyfriends.scheduler;

import com.onthehill.chunkyfriends.chunky.ChunkyGateway;
import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.player.PlayerPregenState;
import com.onthehill.chunkyfriends.player.PlayerStateStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates Chunky jobs around recently active player positions.
 *
 * <p>The scheduler intentionally only runs its own jobs while the server is empty. If a player joins while a
 * Chunky Friends job is active, that task is paused through Chunky's API and resumed when the server becomes
 * empty again. This preserves upstream Chunky Friends behavior while moving the lifecycle wiring to NeoForge.
 */
public final class PregenScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PregenScheduler.class);

    private final ChunkyGateway chunkyGateway;
    private final ChunkyFriendsConfig config;
    private final SchedulerState schedulerState = new SchedulerState();

    private MinecraftServer server;
    private Map<UUID, PlayerPregenState> playerStates = new HashMap<>();
    private int onlinePlayerCount;
    private int ticksSinceLastProgress;
    private int ticksSinceLastPositionRefresh;
    private long lastProgressLogEpochMillis;

    public PregenScheduler(final ChunkyGateway chunkyGateway, final ChunkyFriendsConfig config) {
        this.chunkyGateway = chunkyGateway;
        this.config = config;
    }

    public void init(final MinecraftServer server) {
        this.server = server;
        playerStates = PlayerStateStore.load(server);
        chunkyGateway.init();

        // Chunky invokes API callbacks from worker threads. Marshal all state mutations to the server thread.
        chunkyGateway.onGenerationProgress(event -> server.execute(() -> onProgressEvent(event)));
        chunkyGateway.onGenerationComplete(event -> server.execute(() -> onTaskDisappeared(event)));

        onlinePlayerCount = server.getPlayerList().getPlayerCount();
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePosition(getOrCreateState(player.getUUID()), player);
        }
        persist();

        if (onlinePlayerCount == 0) {
            selectAndStartNext();
        }
    }

    public void shutdown(final MinecraftServer server) {
        PlayerStateStore.save(server, playerStates);
    }

    public void resetAllProgress() {
        for (final PlayerPregenState state : playerStates.values()) {
            state.setCurrentRingTier(0);
        }
        persist();
        LOGGER.info("Reset all tracked players' pregeneration ring tiers after a scheduling-curve change.");
        if (onlinePlayerCount == 0 && schedulerState.activePlayerUuid == null) {
            selectAndStartNext();
        }
    }

    public void onPlayerJoin(final ServerPlayer player) {
        final boolean wasEmpty = onlinePlayerCount == 0;
        onlinePlayerCount++;

        final PlayerPregenState state = getOrCreateState(player.getUUID());
        updatePosition(state, player);

        if (wasEmpty && schedulerState.activePlayerUuid != null && !schedulerState.presencePaused) {
            final PlayerPregenState activeState = playerStates.get(schedulerState.activePlayerUuid);
            LOGGER.info(
                    "Pausing pregeneration job for {} in {} because {} joined.",
                    describe(activeState),
                    schedulerState.activeWorld,
                    player.getGameProfile().getName());
            // Mark the job as presence-paused before asking Chunky to stop it. Chunky emits its
            // completion callback as the worker exits after a pause; setting the flag first guarantees that
            // a callback queued immediately by Chunky cannot be mistaken for a genuinely completed tier.
            schedulerState.presencePaused = true;
            final boolean paused = chunkyGateway.pauseTask(schedulerState.activeWorld);
            if (!paused) {
                schedulerState.presencePaused = false;
                LOGGER.warn("Chunky did not pause the active Chunky Friends task in {}.", schedulerState.activeWorld);
            }
        }
        persist();
    }

    public void onPlayerDisconnect(final ServerPlayer player) {
        final PlayerPregenState state = getOrCreateState(player.getUUID());
        state.setLastSeenEpochMillis(System.currentTimeMillis());
        updatePosition(state, player);
        onlinePlayerCount = Math.max(0, onlinePlayerCount - 1);

        if (onlinePlayerCount == 0) {
            if (schedulerState.activePlayerUuid != null && schedulerState.presencePaused) {
                final PlayerPregenState activeState = playerStates.get(schedulerState.activePlayerUuid);
                LOGGER.info(
                        "Resuming pregeneration job for {} in {} because the server is now empty.",
                        describe(activeState),
                        schedulerState.activeWorld);
                if (chunkyGateway.continueTask(schedulerState.activeWorld)) {
                    schedulerState.presencePaused = false;
                    ticksSinceLastProgress = 0;
                } else {
                    LOGGER.warn(
                            "Chunky could not resume the paused task in {}. Clearing it and selecting the next job.",
                            schedulerState.activeWorld);
                    clearActiveJob();
                    selectAndStartNext();
                }
            } else if (schedulerState.activePlayerUuid == null) {
                selectAndStartNext();
            }
        }
        persist();
    }

    public Optional<ActiveJobSnapshot> activeJobSnapshot() {
        if (schedulerState.activePlayerUuid == null) {
            return Optional.empty();
        }
        final PlayerPregenState state = playerStates.get(schedulerState.activePlayerUuid);
        final String displayName = state != null ? state.getLastKnownName() : null;
        final int ringTier = state != null ? state.getCurrentRingTier() + 1 : 0;
        return Optional.of(new ActiveJobSnapshot(
                schedulerState.activePlayerUuid,
                displayName,
                schedulerState.activeWorld,
                ringTier,
                config.getRingCount(),
                schedulerState.lastProgressPercent,
                schedulerState.lastProgressChunks,
                schedulerState.lastProgressRate,
                schedulerState.presencePaused,
                schedulerState.lastProgressEventEpochMillis));
    }

    public List<PlayerPregenState> eligiblePlayers(final long nowEpochMillis) {
        final List<PlayerPregenState> eligible = new ArrayList<>();
        for (final PlayerPregenState state : playerStates.values()) {
            if (PlayerSelector.isEligible(state, nowEpochMillis, config)) {
                eligible.add(state);
            }
        }
        eligible.sort(Comparator.comparing(
                PlayerPregenState::getLastKnownName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return eligible;
    }

    /** Called from NeoForge's {@code ServerTickEvent.Post}. */
    public void onServerTick(final MinecraftServer server) {
        if (schedulerState.activePlayerUuid != null && !schedulerState.presencePaused) {
            ticksSinceLastProgress++;
            if (ticksSinceLastProgress == config.getStallTimeoutTicks()) {
                LOGGER.warn(
                        "Pregeneration job for {} in {} has received no progress event in {} ticks; it may be stalled.",
                        describe(playerStates.get(schedulerState.activePlayerUuid)),
                        schedulerState.activeWorld,
                        config.getStallTimeoutTicks());
            }
        }

        if (onlinePlayerCount > 0) {
            ticksSinceLastPositionRefresh++;
            if (ticksSinceLastPositionRefresh >= config.getCheckIntervalTicks()) {
                ticksSinceLastPositionRefresh = 0;
                refreshOnlinePlayerPositions(server);
            }
        }
    }

    private void refreshOnlinePlayerPositions(final MinecraftServer server) {
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePosition(getOrCreateState(player.getUUID()), player);
        }
        persist();
    }

    private void onProgressEvent(final GenerationProgressEvent event) {
        if (!event.world().equals(schedulerState.activeWorld)) {
            return;
        }
        ticksSinceLastProgress = 0;
        schedulerState.lastProgressPercent = event.progress();
        schedulerState.lastProgressChunks = event.chunks();
        schedulerState.lastProgressRate = event.rate();
        schedulerState.lastProgressEventEpochMillis = System.currentTimeMillis();

        final long now = System.currentTimeMillis();
        if (now - lastProgressLogEpochMillis >= config.getProgressLogIntervalSeconds() * 1000L) {
            lastProgressLogEpochMillis = now;
            LOGGER.info(
                    "Pregeneration progress for {} in {}: {}% complete, {} chunks, {} chunks/s.",
                    describe(playerStates.get(schedulerState.activePlayerUuid)),
                    event.world(),
                    event.progress(),
                    event.chunks(),
                    event.rate());
        }
    }

    /**
     * Chunky 1.4.x emits its completion callback when a task leaves the running set, including pause/cancel.
     * A presence-driven pause is explicitly ignored here. A manual cancel is treated like upstream Chunky
     * Friends: advance the heuristic tier and move on; Chunky's generated-chunk state remains authoritative.
     */
    private void onTaskDisappeared(final GenerationCompleteEvent event) {
        if (schedulerState.activePlayerUuid == null || !event.world().equals(schedulerState.activeWorld)) {
            return;
        }
        if (schedulerState.presencePaused) {
            return;
        }

        final PlayerPregenState state = playerStates.get(schedulerState.activePlayerUuid);
        if (state != null) {
            state.setCurrentRingTier(Math.min(config.getRingCount(), state.getCurrentRingTier() + 1));
            LOGGER.info(
                    "Pregeneration job for {} in {} completed — now at ring tier {} of {}.",
                    describe(state),
                    schedulerState.activeWorld,
                    state.getCurrentRingTier(),
                    config.getRingCount());
        }

        clearActiveJob();
        persist();
        if (onlinePlayerCount == 0) {
            selectAndStartNext();
        }
    }

    private void selectAndStartNext() {
        if (onlinePlayerCount != 0 || schedulerState.activePlayerUuid != null) {
            return;
        }

        final Optional<PlayerPregenState> next = PlayerSelector.selectNext(
                playerStates.values(), System.currentTimeMillis(), config);
        if (next.isEmpty()) {
            return;
        }

        final PlayerPregenState state = next.get();
        if (state.getLastKnownDimension() == null) {
            LOGGER.warn("Skipping {} because no last-known dimension is available.", describe(state));
            state.setCurrentRingTier(config.getRingCount());
            persist();
            selectAndStartNext();
            return;
        }

        final int nextTier = state.getCurrentRingTier() + 1;
        final int radius = RingCurve.radiusForTier(
                nextTier,
                config.getRingCount(),
                config.getMaxRadiusChunks(),
                config.getCurveExponent());

        final boolean started = chunkyGateway.startTask(
                state.getLastKnownDimension(),
                state.getLastKnownX(),
                state.getLastKnownZ(),
                radius);
        if (!started) {
            LOGGER.warn(
                    "Chunky refused to start a pregeneration task for {} in {}. Another Chunky task may already be active in that dimension.",
                    describe(state),
                    state.getLastKnownDimension());
            return;
        }

        schedulerState.activePlayerUuid = state.getPlayerUuid();
        schedulerState.activeWorld = state.getLastKnownDimension();
        schedulerState.presencePaused = false;
        ticksSinceLastProgress = 0;
        lastProgressLogEpochMillis = 0L;
        state.setLastServicedEpochMillis(System.currentTimeMillis());
        persist();

        LOGGER.info(
                "Started tier {} pregeneration job for {} in {} — radius {} chunks ({} blocks).",
                nextTier,
                describe(state),
                state.getLastKnownDimension(),
                radius,
                radius * 16L);
    }

    private void updatePosition(final PlayerPregenState state, final ServerPlayer player) {
        state.setLastKnownName(player.getGameProfile().getName());
        final String dimension = player.level().dimension().location().toString();
        final double x = player.getX();
        final double z = player.getZ();

        final boolean moved = !dimension.equals(state.getLastKnownDimension())
                || x != state.getLastKnownX()
                || z != state.getLastKnownZ();

        state.setLastKnownDimension(dimension);
        state.setLastKnownX(x);
        state.setLastKnownZ(z);

        if (moved && state.getCurrentRingTier() != 0) {
            LOGGER.info(
                    "Invalidating pregeneration progress for {} because its stored center moved; ring tier {} -> 0.",
                    describe(state),
                    state.getCurrentRingTier());
            state.setCurrentRingTier(0);
        }
    }

    private PlayerPregenState getOrCreateState(final UUID playerUuid) {
        return playerStates.computeIfAbsent(playerUuid, PlayerPregenState::new);
    }

    private void persist() {
        if (server != null) {
            PlayerStateStore.save(server, playerStates);
        }
    }

    private void clearActiveJob() {
        schedulerState.activePlayerUuid = null;
        schedulerState.activeWorld = null;
        schedulerState.presencePaused = false;
        schedulerState.lastProgressPercent = 0;
        schedulerState.lastProgressChunks = 0;
        schedulerState.lastProgressRate = 0;
        schedulerState.lastProgressEventEpochMillis = 0;
    }

    private static String describe(final PlayerPregenState state) {
        if (state == null) {
            return "an unknown player";
        }
        final String name = state.getLastKnownName();
        return name != null ? name + " (" + state.getPlayerUuid() + ")" : "player " + state.getPlayerUuid();
    }

    private static final class SchedulerState {
        private UUID activePlayerUuid;
        private String activeWorld;
        private boolean presencePaused;
        private double lastProgressPercent;
        private long lastProgressChunks;
        private double lastProgressRate;
        private long lastProgressEventEpochMillis;
    }
}
