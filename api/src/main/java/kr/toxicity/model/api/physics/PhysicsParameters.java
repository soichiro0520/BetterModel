/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.physics;

import org.jetbrains.annotations.NotNull;

/**
 * Physics parameters of a jiggle bone, simulated by the server-side physics layer.
 * <p>
 * Bones whose raw name starts with {@code jiggle_} are physics-enabled with {@link #DEFAULT}
 * unless overridden by a model sidecar YAML ({@code models/<model>.yaml}).
 * </p>
 * <p>Example usage:</p>
 * <pre>{@code
 * PhysicsParameters parameters = new PhysicsParameters(0.08F, 0.12F, 1.0F, 0.3F, false);
 * }</pre>
 *
 * @param stiffness spring constant per tick squared. larger is harder, smaller is wobblier
 * @param damping damping coefficient per tick, clamped to [0, 1]. smaller swings longer
 * @param inertia how much the parent entity's acceleration is transferred to the bone
 * @param gravity gravity influence, controls how much the bone sags downward
 * @param chain true if the parent bone's displacement propagates to this bone (tails, hair, scarves)
 * @since 3.4.2
 */
public record PhysicsParameters(float stiffness, float damping, float inertia, float gravity, boolean chain) {

    /**
     * The default parameters used for {@code jiggle_} prefixed bones without a YAML override.
     *
     * @since 3.4.2
     */
    public static final PhysicsParameters DEFAULT = new PhysicsParameters(0.08F, 0.12F, 1.0F, 0.3F, false);

    /**
     * The minimum spring constant to keep the spring stable.
     *
     * @since 3.4.2
     */
    public static final float MIN_STIFFNESS = 1.0E-4F;

    /**
     * The maximum damping coefficient.
     *
     * @since 3.4.2
     */
    public static final float MAX_DAMPING = 1.0F;

    /**
     * Creates physics parameters with validation.
     *
     * @since 3.4.2
     */
    public PhysicsParameters {
        if (stiffness < MIN_STIFFNESS) stiffness = MIN_STIFFNESS;
        damping = Math.clamp(damping, 0F, MAX_DAMPING);
        inertia = Math.max(0F, inertia);
        gravity = Math.max(0F, gravity);
    }

    /**
     * The prefix of jiggle bone names.
     *
     * @since 3.4.2
     */
    public static final String JIGGLE_PREFIX = "jiggle_";
}
