package com.onthehill.chunkyfriends.chunky;

import java.util.function.Consumer;

import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.api.ChunkyAPI;
import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;

/**
 * Thin typed wrapper around Chunky 1.4.23's developer API.
 *
 * <p>Chunky 1.4.x uses string shape/pattern identifiers. The enum overload used by Chunky 1.5.x does not
 * exist on Minecraft 1.21.1, so this class intentionally targets the 1.4.23 signature.
 */
public final class ChunkyGateway {
    private static final double BLOCKS_PER_CHUNK = 16.0;
    private static final String SHAPE_CIRCLE = "circle";
    private static final String PATTERN_CONCENTRIC = "concentric";

    private ChunkyAPI api;

    public void init() {
        api = ChunkyProvider.get().getApi();
    }

    public boolean isRunning(final String world) {
        return api.isRunning(world);
    }

    public boolean startTask(
            final String world,
            final double centerX,
            final double centerZ,
            final double radiusChunks) {
        final double radiusBlocks = radiusChunks * BLOCKS_PER_CHUNK;
        return api.startTask(
                world,
                SHAPE_CIRCLE,
                centerX,
                centerZ,
                radiusBlocks,
                radiusBlocks,
                PATTERN_CONCENTRIC);
    }

    public boolean pauseTask(final String world) {
        return api.pauseTask(world);
    }

    public boolean continueTask(final String world) {
        return api.continueTask(world);
    }

    public void onGenerationProgress(final Consumer<GenerationProgressEvent> listener) {
        api.onGenerationProgress(listener);
    }

    public void onGenerationComplete(final Consumer<GenerationCompleteEvent> listener) {
        api.onGenerationComplete(listener);
    }
}
