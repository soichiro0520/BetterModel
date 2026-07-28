#!/usr/bin/env python3
"""Generates synthetic .bbmodel files for BetterModel benchmarks.

A benchmark model is a tree of bones, each holding a share of cubes, plus a
looping animation that rotates every bone so the whole animation pipeline
(keyframe interpolation, bone transform propagation, transform packets) is
exercised every tick.

Polygon budget: 1 cube = 12 triangles, so ~10,000 triangles ~= 834 cubes.

Usage:
  python3 generate_model.py --name bench_10k_b20 --cubes 834 --bones 20
  python3 generate_model.py --name bench_10k_b50 --cubes 834 --bones 50

The output file goes to benchmark/models/<name>.bbmodel by default; copy it to
plugins/BetterModel/models and run /bm reload to use it.
"""

import argparse
import base64
import io
import json
import math
import struct
import uuid
import zlib
from pathlib import Path


def make_png(size: int = 16) -> str:
    """Builds a minimal grayscale checkerboard PNG and returns it base64-encoded."""
    def chunk(tag: bytes, data: bytes) -> bytes:
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b""
    for y in range(size):
        raw += b"\x00"  # filter type none
        for x in range(size):
            v = 200 if (x // 4 + y // 4) % 2 == 0 else 90
            raw += bytes((v,))
    out = io.BytesIO()
    out.write(b"\x89PNG\r\n\x1a\n")
    out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0)))
    out.write(chunk(b"IDAT", zlib.compress(raw)))
    out.write(chunk(b"IEND", b""))
    return base64.b64encode(out.getvalue()).decode()


def build_model(name: str, cube_count: int, bone_count: int, anim_length: float, keyframe_step: float):
    elements = []
    outliner = []
    bone_uuids = []

    cubes_per_bone = [cube_count // bone_count] * bone_count
    for i in range(cube_count % bone_count):
        cubes_per_bone[i] += 1

    # Bones are laid out as a chain of chains (depth ~4) to exercise
    # parent-child transform propagation.
    for b in range(bone_count):
        bone_uuid = str(uuid.uuid4())
        bone_uuids.append(bone_uuid)
        angle = b * (2 * math.pi / bone_count)
        origin = [math.cos(angle) * 8, 4 + (b % 8) * 3, math.sin(angle) * 8]
        children = []
        for c in range(cubes_per_bone[b]):
            el_uuid = str(uuid.uuid4())
            # Small cubes scattered around the bone origin.
            fx = origin[0] + ((c * 7) % 13) * 0.5 - 3
            fy = origin[1] + ((c * 5) % 11) * 0.5 - 2
            fz = origin[2] + ((c * 3) % 7) * 0.5 - 1.5
            sx, sy, sz = 0.8, 0.8, 0.8
            uv = [(c * 2) % 12, (c * 3) % 12]
            faces = {
                face: {"uv": [uv[0], uv[1], uv[0] + 2, uv[1] + 2], "texture": 0}
                for face in ("north", "east", "south", "west", "up", "down")
            }
            elements.append({
                "name": f"cube_{b}_{c}",
                "type": "cube",
                "uuid": el_uuid,
                "from": [fx, fy, fz],
                "to": [fx + sx, fy + sy, fz + sz],
                "origin": origin,
                "faces": faces,
            })
            children.append(el_uuid)
        node = {
            "name": f"bone{b}",
            "uuid": bone_uuid,
            "origin": origin,
            "children": children,
        }
        outliner.append(node)

    # Looping animation: every bone rotates continuously.
    animators = {}
    for b, bone_uuid in enumerate(bone_uuids):
        keyframes = []
        t = 0.0
        idx = 0
        while t <= anim_length + 1e-6:
            phase = (t / anim_length) * 360 + b * 15
            keyframes.append({
                "channel": "rotation",
                "data_points": [{
                    "x": math.sin(math.radians(phase)) * 25,
                    "y": (phase * 2) % 360,
                    "z": math.cos(math.radians(phase)) * 25,
                }],
                "uuid": str(uuid.uuid4()),
                "time": round(t, 4),
                "color": -1,
                "interpolation": "linear",
            })
            idx += 1
            t = idx * keyframe_step
        animators[bone_uuid] = {
            "name": f"bone{b}",
            "type": "bone",
            "keyframes": keyframes,
        }

    animation = {
        "uuid": str(uuid.uuid4()),
        "name": "bench_loop",
        "loop": "loop",
        "override": False,
        "length": anim_length,
        "snapping": 24,
        "animators": animators,
    }

    return {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": name,
        "model_identifier": name,
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": outliner,
        "textures": [{
            "name": "bench.png",
            "uuid": str(uuid.uuid4()),
            "id": "0",
            "particle": False,
            "source": "data:image/png;base64," + make_png(),
        }],
        "animations": [animation],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True, help="model name (also the output file name)")
    parser.add_argument("--cubes", type=int, default=834, help="cube count (834 ~= 10k triangles)")
    parser.add_argument("--bones", type=int, default=20, help="bone count")
    parser.add_argument("--anim-length", type=float, default=2.0, help="animation length in seconds")
    parser.add_argument("--keyframe-step", type=float, default=0.25, help="keyframe spacing in seconds")
    parser.add_argument("--out", default=None, help="output path (defaults to benchmark/models/<name>.bbmodel)")
    args = parser.parse_args()

    model = build_model(args.name, args.cubes, args.bones, args.anim_length, args.keyframe_step)
    out = Path(args.out) if args.out else Path(__file__).resolve().parent.parent / "models" / f"{args.name}.bbmodel"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(model))
    tris = args.cubes * 12
    print(f"wrote {out} ({args.cubes} cubes = {tris} triangles, {args.bones} bones)")


if __name__ == "__main__":
    main()
