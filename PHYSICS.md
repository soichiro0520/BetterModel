# BetterModel Physics & Equipment

BetterModel 3.4.2 adds a server-side spring physics layer, equipment socket bones,
and packet-rate optimizations for animated models. This document describes the
user-facing behavior, the sidecar YAML format, and how to profile the cost.

## Sidecar physics file

Physics parameters live in a sidecar YAML file next to the model file:

```
plugins/BetterModel/models/my_model.bbmodel
plugins/BetterModel/models/my_model.yaml
```

Only the flat `physics` section is read; everything else is ignored:

```yaml
physics:
  jiggle_hair:
    stiffness: 0.08
    damping: 0.12
    inertia: 1.0
    gravity: 0.3
    chain: true
```

| Key       | Default | Meaning                                                        |
|-----------|---------|----------------------------------------------------------------|
| stiffness | 0.08    | Spring pull toward the rest pose per tick. Higher = stiffer.   |
| damping   | 0.12    | Velocity decay per tick. Higher = settles faster.              |
| inertia   | 1.0     | Reaction to entity acceleration. Higher = follows more lazily. |
| gravity   | 0.3     | Constant downward pull. 0 disables the sag.                    |
| chain     | false   | Add parent jiggle offsets so children follow parents.          |

Bone names are raw bbmodel bone names (unquoted or quoted). Missing keys fall back
to the defaults above. Bones are conventionally prefixed with `jiggle_` so they are
easy to spot in a bbmodel, but any bone listed in the `physics` section participates.

The simulation runs at Minecraft tick cadence (50 ms) on the tracker worker thread:

- **Jiggle** — each physics bone is a damped spring driven by the entity's sampled
  velocity/acceleration. The resulting offset is added to the bone's rendered
  position only; hitboxes and child propagation are untouched.
- **Squash** — when the entity lands (downward velocity suddenly drops), the root
  bone plays a volume-preserving squash spring: `scaleY = 1-s`,
  `scaleXZ = 1/√(1-s)`. It is automatic and not configurable.
- **Impulse** — plugins can inject a velocity kick into every physics bone via
  `Tracker.applyImpulse(Vector3f)` / `RenderPipeline.impulse(Vector3f)`.
  Bones swing in the impulse direction and recover by their own stiffness/damping.
  On Paper, collisions with cube-family mobs (`AbstractCubeMob`, e.g. the Sulfur Cube)
  automatically inject `cube velocity x 0.2 x size` into the collided model's
  physics bones through the same path.

Models without a `physics` section pay no physics cost (the engine is never created).

## Equipment socket bones

A bone whose raw name starts with `anchor_` is an equipment socket. It never
spawns its own display entity; instead, other models can be attached beneath it:

```java
var tracker = EntityTrackerRegistry.instance().getOrCreate(entity, "my_model");
var equipment = BetterModel.platform().manager(ModelManager.class).model("sword_equipment");
if (equipment != null) tracker.equip("anchor_right_hand", equipment);
// later
tracker.unequip("anchor_right_hand");
```

Attached equipment roots become children of the anchor bone, so the anchor's
keyframe animation, jiggle, and squash propagate to the equipment automatically.
Equipment bones with their own `physics` section join the same physics engine.
The config key `equipment-offset` (default 0.0) slightly inflates
(`1 + offset`) attached equipment to reduce z-fighting with the base model.

## Packet-rate behavior

The physics layer is designed to keep the idle packet rate at zero and to bound
the active rate:

- A bone only sends a transformation packet when its final transform differs from
  the last sent one: position within 1e-3 blocks, rotation/scale within the
  standard comparison epsilons. Micro-jitter below the threshold is coalesced.
- Physics simulation runs every Minecraft tick, but transformation packets are
  throttled to one per 2 simulation steps (100 ms). Bones with an active jiggle
  or squash use a minimum interpolation duration of 2 ticks so the client still
  smooths the motion between packets.
- When a spring settles back to rest, one final packet is always sent so the
  client never keeps a stale deformation.

## Profiling

- **CPU** — use [spark](https://spark.lucko.me/). Profile the tracker worker
  thread (the thread running `Tracker` ticks); physics integration cost shows up
  there per model pipeline. A model with no physics bones has no engine overhead.
- **Packet volume** — compare network traffic with spark's network monitoring (or
  a packet-metrics plugin) while a physics model idles versus while it moves.
  An idle model should produce no entity-metadata packets at all; an actively
  jiggling model produces at most one bundle per 100 ms per deformed bone.
- **Config knobs** — if the physics cost is too high, reduce the number of
  `physics` entries in the sidecar YAML, or raise `damping`/`stiffness` so springs
  settle sooner and stop generating packets earlier.
