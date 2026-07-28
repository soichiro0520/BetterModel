/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.config;

import org.jetbrains.annotations.NotNull;

/**
 * Performance config for animation culling and LOD (level of detail).
 * <p>
 * Animation culling skips the whole transform/packet pipeline of a tracker
 * while no player passes its view filter (sight trace); animation clocks keep
 * running at a reduced heartbeat so gameplay timing is preserved.
 * LOD reduces the tracker update rate for models whose nearest viewer is far away,
 * relying on client-side display interpolation to keep motion smooth.
 * </p>
 *
 * @param animationCulling whether to skip transform computation and packet building while unseen
 * @param cullingInterval  heartbeat interval in tracker frames (25ms each) while culled
 * @param lod              whether distance-based update throttling is enabled
 * @param lodNearDistance  distance in blocks under which models always update at full rate
 * @param lodFarDistance   distance in blocks at which models update at the slowest LOD rate
 * @param lodMaxInterval   slowest LOD update interval in tracker frames (25ms each), power of two
 * @since 3.3.0
 */
public record PerformanceConfig(
    boolean animationCulling,
    int cullingInterval,
    boolean lod,
    double lodNearDistance,
    double lodFarDistance,
    int lodMaxInterval
) {

    /**
     * The default performance config.
     * @since 3.3.0
     */
    public static final PerformanceConfig DEFAULT = new PerformanceConfig(
        true,
        10,
        true,
        16.0,
        48.0,
        8
    );

    /**
     * Creates a performance config, clamping values to a safe range.
     *
     * @param animationCulling whether animation culling is enabled
     * @param cullingInterval  culled heartbeat interval in tracker frames
     * @param lod              whether LOD is enabled
     * @param lodNearDistance  full-rate distance in blocks
     * @param lodFarDistance   slowest-rate distance in blocks
     * @param lodMaxInterval   slowest update interval in tracker frames
     */
    public PerformanceConfig {
        cullingInterval = Math.clamp(cullingInterval, 1, 40);
        lodNearDistance = Math.max(lodNearDistance, 1.0);
        lodFarDistance = Math.max(lodFarDistance, lodNearDistance + 1.0);
        lodMaxInterval = Integer.highestOneBit(Math.clamp(lodMaxInterval, 1, 16));
    }

    /**
     * Computes the LOD update interval in tracker frames for a viewer distance.
     * <p>
     * The interval doubles for each equally-sized band between near and far distance
     * until it reaches {@link #lodMaxInterval}.
     * </p>
     *
     * @param distanceSquared squared distance in blocks between model and its nearest viewer
     * @return update interval in tracker frames (1 = every frame)
     * @since 3.3.0
     */
    public int lodInterval(double distanceSquared) {
        if (!lod || distanceSquared <= lodNearDistance * lodNearDistance) return 1;
        if (distanceSquared >= lodFarDistance * lodFarDistance) return lodMaxInterval;
        var steps = Integer.numberOfTrailingZeros(lodMaxInterval);
        var t = (Math.sqrt(distanceSquared) - lodNearDistance) / (lodFarDistance - lodNearDistance);
        var step = Math.min((int) (t * steps) + 1, steps);
        return 1 << step;
    }
}
