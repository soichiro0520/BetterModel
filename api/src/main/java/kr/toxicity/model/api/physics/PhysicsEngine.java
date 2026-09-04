/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.physics;

import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.data.renderer.RenderSource;
import kr.toxicity.model.api.tracker.Tracker;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A server-side spring simulation layer composited after keyframe animation evaluation.
 * <p>
 * Each {@link kr.toxicity.model.api.data.renderer.RenderPipeline} owns at most one engine.
 * The engine integrates jiggle bones (see {@link PhysicsParameters}) at Minecraft tick cadence
 * and applies:
 * </p>
 * <pre>
 *   final transform = keyframe evaluation
 *                   × squash (volume-preserving landing deformation)
 *                   + jiggle offset (spring sway)
 * </pre>
 * <p>
 * All simulation runs on the tracker worker thread. Physics affects only rendering;
 * hitboxes are never moved.
 * </p>
 *
 * @since 3.4.2
 */
@ApiStatus.Internal
public final class PhysicsEngine {

    private static final int STEP_INTERVAL = Tracker.MINECRAFT_TICK_MULTIPLIER;
    private static final int SEND_INTERVAL = 2;
    private static final float GRAVITY_ACCELERATION = 0.08F;
    private static final float MAX_OFFSET = 4F;
    private static final float MAX_VELOCITY = 4F;
    private static final float EPSILON = 1.0E-4F;

    private static final float MAX_SQUASH = 0.5F;
    private static final float MIN_SQUASH = -0.3F;
    private static final float SQUASH_STIFFNESS = 0.2F;
    private static final float SQUASH_DAMPING = 0.25F;
    private static final float SQUASH_GAIN = 0.35F;
    private static final float FALL_VELOCITY_THRESHOLD = 0.3F;

    private final RenderSource<?> source;
    private final RenderedBone root;
    private final Map<RenderedBone, BoneState> states = new ConcurrentHashMap<>();

    private long tick;
    private long sendTick;
    private boolean sampled;
    private double lastX, lastY, lastZ;
    private float velocityX, velocityY, velocityZ;
    private float accelerationX, accelerationY, accelerationZ;

    private float squash, squashVelocity;
    private boolean squashActive;

    /**
     * Creates a physics engine.
     *
     * @param source the render source used to sample entity movement
     * @param roots the root bones of the pipeline; the first root receives landing squash
     * @since 3.4.2
     */
    public PhysicsEngine(@NotNull RenderSource<?> source, @NotNull RenderedBone[] roots) {
        this.source = source;
        this.root = roots[0];
    }

    /**
     * Checks if no physics bone is registered.
     *
     * @return true if empty
     * @since 3.4.2
     */
    public boolean isEmpty() {
        return states.isEmpty();
    }

    /**
     * Registers a bone if its group has physics parameters.
     *
     * @param bone the bone to register
     * @since 3.4.2
     */
    public void register(@NotNull RenderedBone bone) {
        var parameters = bone.getGroup().getPhysics();
        if (parameters != null) states.putIfAbsent(bone, new BoneState(parameters));
    }

    /**
     * Unregisters a bone and clears its physics deformation.
     *
     * @param bone the bone to unregister
     * @since 3.4.2
     */
    public void unregister(@NotNull RenderedBone bone) {
        states.remove(bone);
        bone.jiggleOffset(0F, 0F, 0F);
        if (bone == root) {
            squash = 0F;
            squashVelocity = 0F;
            squashActive = false;
            root.squash(0F);
        }
    }

    /**
     * Injects an impulse into every physics bone's velocity.
     * <p>
     * This is the generic entry point for external impacts (projectiles, entity collisions,
     * knockback). The impulse is added to the spring velocity directly, so the bones swing
     * in the impulse direction and recover by their own stiffness/damping.
     * </p>
     *
     * @param impulse the velocity delta in blocks per tick
     * @since 3.4.2
     */
    public void impulse(@NotNull Vector3f impulse) {
        for (var state : states.values()) {
            state.vx += impulse.x;
            state.vy += impulse.y;
            state.vz += impulse.z;
            state.active = true;
        }
    }

    /**
     * Advances the simulation by one tracker tick and writes the deformation to the bones.
     * <p>
     * Actual integration happens at Minecraft tick cadence; other calls only return false.
     * Packet refreshes are throttled to one per {@code SEND_INTERVAL} simulation steps;
     * the simulation itself still runs every step. Settling to the rest pose always
     * schedules a final refresh so the client never keeps a stale deformation.
     * </p>
     *
     * @return true if any bone was deformed and needs a packet refresh
     * @since 3.4.2
     */
    public boolean step() {
        if (++tick % STEP_INTERVAL != 0) return false;
        var send = ++sendTick % SEND_INTERVAL == 0;
        var location = source.location();
        if (sampled) {
            var vx = (float) (location.x() - lastX);
            var vy = (float) (location.y() - lastY);
            var vz = (float) (location.z() - lastZ);
            accelerationX = vx - velocityX;
            accelerationY = vy - velocityY;
            accelerationZ = vz - velocityZ;
            if (velocityY < -FALL_VELOCITY_THRESHOLD && vy > velocityY + EPSILON) {
                // Landing impact: sudden loss of downward velocity kicks the squash spring.
                squashVelocity += Math.min(MAX_SQUASH, (vy - velocityY) * SQUASH_GAIN);
                squashActive = true;
            }
            velocityX = vx;
            velocityY = vy;
            velocityZ = vz;
        } else sampled = true;
        lastX = location.x();
        lastY = location.y();
        lastZ = location.z();

        var moved = false;
        for (var entry : states.entrySet()) {
            if (integrate(entry.getKey(), entry.getValue(), send)) moved = true;
        }
        if (squashActive) {
            squashVelocity += -SQUASH_STIFFNESS * squash - SQUASH_DAMPING * squashVelocity;
            squash = Math.clamp(squash + squashVelocity, MIN_SQUASH, MAX_SQUASH);
            root.squash(squash);
            if (Math.abs(squash) < EPSILON && Math.abs(squashVelocity) < EPSILON) {
                // Settled: always sync the restored scale, even off-interval.
                squash = 0F;
                squashVelocity = 0F;
                squashActive = false;
                root.squash(0F);
                root.physicsMoved();
                moved = true;
            } else if (send) {
                root.physicsMoved();
                moved = true;
            }
        }
        return moved;
    }

    private boolean integrate(@NotNull RenderedBone bone, @NotNull BoneState state, boolean send) {
        var parameters = state.parameters;
        state.vx += -parameters.stiffness() * state.ox - parameters.damping() * state.vx - parameters.inertia() * accelerationX;
        state.vy += -parameters.stiffness() * state.oy - parameters.damping() * state.vy - parameters.inertia() * accelerationY - GRAVITY_ACCELERATION * parameters.gravity();
        state.vz += -parameters.stiffness() * state.oz - parameters.damping() * state.vz - parameters.inertia() * accelerationZ;
        state.vx = Math.clamp(state.vx, -MAX_VELOCITY, MAX_VELOCITY);
        state.vy = Math.clamp(state.vy, -MAX_VELOCITY, MAX_VELOCITY);
        state.vz = Math.clamp(state.vz, -MAX_VELOCITY, MAX_VELOCITY);
        state.ox = Math.clamp(state.ox + state.vx, -MAX_OFFSET, MAX_OFFSET);
        state.oy = Math.clamp(state.oy + state.vy, -MAX_OFFSET, MAX_OFFSET);
        state.oz = Math.clamp(state.oz + state.vz, -MAX_OFFSET, MAX_OFFSET);

        var significant = Math.abs(state.ox) > EPSILON || Math.abs(state.oy) > EPSILON || Math.abs(state.oz) > EPSILON
            || Math.abs(state.vx) > EPSILON || Math.abs(state.vy) > EPSILON || Math.abs(state.vz) > EPSILON;
        if (!state.active && !significant) return false;
        if (!significant) {
            // Settled: always sync the restored offset, even off-interval.
            state.ox = state.oy = state.oz = 0F;
            state.vx = state.vy = state.vz = 0F;
            state.active = false;
            bone.jiggleOffset(0F, 0F, 0F);
            bone.physicsMoved();
            return true;
        }
        state.active = true;

        var offset = state.offset.set(state.ox, state.oy, state.oz);
        if (parameters.chain()) {
            var parent = bone.getParent();
            while (parent != null) {
                var parentState = states.get(parent);
                if (parentState == null) break;
                offset.add(parentState.ox, parentState.oy, parentState.oz);
                if (!parentState.parameters.chain()) break;
                parent = parent.getParent();
            }
        }
        bone.jiggleOffset(offset.x, offset.y, offset.z);
        if (!send) return false;
        bone.physicsMoved();
        return true;
    }

    private static final class BoneState {
        private final PhysicsParameters parameters;
        private final Vector3f offset = new Vector3f();
        private float ox, oy, oz;
        private float vx, vy, vz;
        private boolean active;

        private BoneState(@NotNull PhysicsParameters parameters) {
            this.parameters = parameters;
        }
    }
}
