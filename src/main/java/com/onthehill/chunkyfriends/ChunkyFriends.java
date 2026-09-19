package com.onthehill.chunkyfriends;

import com.onthehill.chunkyfriends.chunky.ChunkyGateway;
import com.onthehill.chunkyfriends.command.ChunkyFriendsCommand;
import com.onthehill.chunkyfriends.config.ChunkyFriendsConfig;
import com.onthehill.chunkyfriends.scheduler.PregenScheduler;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NeoForge 1.21.1 entry point for Chunky Friends. */
@Mod(ChunkyFriends.MOD_ID)
public final class ChunkyFriends {
    /** NeoForge mod ids may not contain hyphens, unlike the original Fabric id. */
    public static final String MOD_ID = "chunky_friends";
    public static final Logger LOGGER = LoggerFactory.getLogger("Chunky Friends");

    private ChunkyFriendsConfig config;
    private PregenScheduler pregenScheduler;

    public ChunkyFriends() {
        final var bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onServerStarted);
        bus.addListener(this::onServerStopping);
        bus.addListener(this::onPlayerLoggedIn);
        bus.addListener(this::onPlayerLoggedOut);
        bus.addListener(this::onServerTick);
        bus.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(final ServerStartedEvent event) {
        // Recreate per server instance. This also makes integrated-server stop/start cycles safe.
        config = ChunkyFriendsConfig.load(FMLPaths.CONFIGDIR.get().resolve("chunky-friends.json"));
        pregenScheduler = new PregenScheduler(new ChunkyGateway(), config);
        pregenScheduler.init(event.getServer());
        LOGGER.info("Chunky Friends NeoForge port initialized for Minecraft 1.21.1.");
    }

    private void onServerStopping(final ServerStoppingEvent event) {
        if (pregenScheduler != null) {
            pregenScheduler.shutdown(event.getServer());
        }
        pregenScheduler = null;
        config = null;
    }

    private void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (pregenScheduler != null && event.getEntity() instanceof ServerPlayer player) {
            pregenScheduler.onPlayerJoin(player);
        }
    }

    private void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (pregenScheduler != null && event.getEntity() instanceof ServerPlayer player) {
            pregenScheduler.onPlayerDisconnect(player);
        }
    }

    private void onServerTick(final ServerTickEvent.Post event) {
        if (pregenScheduler != null) {
            pregenScheduler.onServerTick(event.getServer());
        }
    }

    private void onRegisterCommands(final RegisterCommandsEvent event) {
        ChunkyFriendsCommand.register(
                event.getDispatcher(),
                () -> config,
                () -> pregenScheduler,
                this::onCurveConfigChanged);
    }

    private void onCurveConfigChanged() {
        if (pregenScheduler != null) {
            pregenScheduler.resetAllProgress();
        }
    }
}
