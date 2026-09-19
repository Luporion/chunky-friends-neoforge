package com.onthehill.chunkyfriends.config;

import java.util.OptionalInt;

/**
 * Shared validation and parsing for the server-side configuration command.
 *
 * <p>The upstream Fabric version keeps this logic in its networking layer because its client GUI and
 * commands share the same protocol. The NeoForge 1.21.1 port is deliberately server-only for the first
 * stable port, so this small loader-neutral helper keeps the exact same validation semantics without
 * carrying a Fabric networking dependency.
 */
public final class ConfigSupport {
    public static final int MIN_RING_COUNT = 1;
    public static final int MAX_RING_COUNT = 64;
    public static final int MIN_RADIUS_CHUNKS = 1;
    public static final int MAX_RADIUS_CHUNKS = 100_000;
    public static final int BLOCKS_PER_CHUNK = 16;

    private static final double LINEAR_CURVE_EXPONENT = 1.0;
    private static final double QUADRATIC_CURVE_EXPONENT = 2.0;

    private ConfigSupport() {
    }

    /**
     * Parses a radius using Chunky's command convention: a bare number means blocks, {@code c} means chunks,
     * and {@code b} explicitly means blocks. The returned value is always chunks.
     */
    public static OptionalInt parseRadiusChunks(final String input) {
        if (input == null) {
            return OptionalInt.empty();
        }
        final String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return OptionalInt.empty();
        }

        final char last = Character.toLowerCase(trimmed.charAt(trimmed.length() - 1));
        final boolean isChunks = last == 'c';
        final boolean hasUnitSuffix = isChunks || last == 'b';
        final String numericPart = hasUnitSuffix ? trimmed.substring(0, trimmed.length() - 1) : trimmed;

        try {
            final int value = Integer.parseInt(numericPart);
            return OptionalInt.of(isChunks ? value : (int) Math.round(value / (double) BLOCKS_PER_CHUNK));
        } catch (final NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    /** Applies and persists a scheduling-curve update after validation. */
    public static boolean applyUpdate(
            final ChunkyFriendsConfig config,
            final int ringCount,
            final int maxRadiusChunks,
            final boolean quadratic,
            final Runnable onCurveChanged) {
        if (ringCount < MIN_RING_COUNT || ringCount > MAX_RING_COUNT
                || maxRadiusChunks < MIN_RADIUS_CHUNKS || maxRadiusChunks > MAX_RADIUS_CHUNKS) {
            return false;
        }

        final boolean changed = config.getRingCount() != ringCount
                || config.getMaxRadiusChunks() != maxRadiusChunks
                || isQuadratic(config.getCurveExponent()) != quadratic;

        config.setRingCount(ringCount);
        config.setMaxRadiusChunks(maxRadiusChunks);
        config.setCurveExponent(quadratic ? QUADRATIC_CURVE_EXPONENT : LINEAR_CURVE_EXPONENT);
        config.save();

        if (changed) {
            onCurveChanged.run();
        }
        return true;
    }

    public static boolean isQuadratic(final double curveExponent) {
        return curveExponent >= (LINEAR_CURVE_EXPONENT + QUADRATIC_CURVE_EXPONENT) / 2.0;
    }
}
