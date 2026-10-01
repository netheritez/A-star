"""Writes a small Minecraft 1.21-format world for the mcworld tests.

Uses nbtlib (an NBT implementation independent of ours) and packs region files
per the Anvil format, so the Java reader is checked against a separate writer.

    pip install nbtlib
    python3 tools/make_test_world.py mcworld/src/test/resources/skyblock-test

Outputs region/*.mca (and one external .mcc chunk), level.dat, and
expected-blocks.txt: every non-air block as "x y z name[props]".
"""
import gzip
import io
import os
import sys
import zlib

from nbtlib import (Byte, Compound, File, Int, List, LongArray, String)

DATA_VERSION = 4435  # 1.21.6
SECTIONS = range(-4, 20)  # y -64 .. 319

blocks = {}  # (x, y, z) -> (name, props)


def put(x, y, z, name, **props):
    blocks[(x, y, z)] = ("minecraft:" + name, {k: str(v).lower() for k, v in props.items()})


def fill(x0, y0, z0, x1, y1, z1, name, **props):
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                put(x, y, z, name, **props)


# ---- Island A: around the origin, spanning four region files -----------------------------
fill(-12, 60, -6, 9, 60, 8, "grass_block", snowy=False)
fill(-12, 59, -6, 9, 59, 8, "dirt")
fill(-6, 57, -3, 4, 58, 4, "stone")
put(0, 60, 0, "lava", level=0)
put(1, 60, 0, "lava", level=0)
put(5, 60, 5, "water", level=0)
for z in range(-6, 3):
    put(-4, 61, z, "oak_fence", east=False, north=True, south=True, waterlogged=False, west=False)
fill(-8, 61, -3, -8, 64, -3, "oak_log", axis="y")
for dx in range(-2, 3):
    for dz in range(-2, 3):
        if (dx, dz) != (0, 0):
            put(-8 + dx, 63, -3 + dz, "oak_leaves", distance=1, persistent=True, waterlogged=False)
fill(7, 61, -6, 9, 62, -4, "stone_bricks")
put(6, 61, -5, "stone_brick_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
put(2, 61, 2, "dandelion")
put(-2, 61, 4, "torch")
put(-1, 61, 6, "oak_door", facing="south", half="lower", hinge="left", open=False, powered=False)
put(-1, 62, 6, "oak_door", facing="south", half="upper", hinge="left", open=False, powered=False)
put(3, 60, 7, "oak_trapdoor", facing="north", half="top", open=True, powered=False, waterlogged=False)
for x in (-10, -9, 8):
    put(x, 61, 1, "short_grass")
# More than 16 block types in one section (chunk 0,0; y 48..63) forces 5 bits per index.
colours = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
           "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
for i, c in enumerate(colours):
    if i <= 9:
        put(i, 60, 8, c + "_wool")
    else:
        put(i - 10, 59, 8, c + "_wool")

# ---- Island B: below y = 0, to test negative sections -------------------------------------
fill(300, -30, 300, 309, -30, 305, "stone")
put(304, -29, 302, "stone_slab", type="bottom", waterlogged=False)
put(306, -29, 303, "cobblestone_wall", east="none", north="none", south="none", up=True,
    waterlogged=False, west="none")

# ---- Island C: a uniform 16^3 cube (one-entry palette, no data), stored externally --------
UNIFORM_CHUNK = (-40, -40)
fill(-640, 64, -640, -625, 79, -625, "stone")

# An empty chunk (all air) that must not count as an island.
EMPTY_CHUNK = (5, 5)


def section_nbt(cx, cz, sy):
    palette, indices = [], []
    lookup = {}
    for y in range(16):
        for z in range(16):
            for x in range(16):
                key = blocks.get((cx * 16 + x, sy * 16 + y, cz * 16 + z),
                                 ("minecraft:air", {}))
                frozen = (key[0], tuple(sorted(key[1].items())))
                if frozen not in lookup:
                    lookup[frozen] = len(palette)
                    palette.append(key)
                indices.append(lookup[frozen])
    pal = List[Compound]([
        Compound({"Name": String(n), **({"Properties": Compound({k: String(v) for k, v in p.items()})}
                                        if p else {})})
        for n, p in palette])
    states = Compound({"palette": pal})
    if len(palette) > 1:
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        words = [0] * ((4096 + per_long - 1) // per_long)
        for i, p in enumerate(indices):
            words[i // per_long] |= p << ((i % per_long) * bits)
        states["data"] = LongArray([w - (1 << 64) if w >= (1 << 63) else w for w in words])
    return Compound({"Y": Byte(sy), "block_states": states,
                     "biomes": Compound({"palette": List[String]([String("minecraft:plains")])})})


def chunk_nbt(cx, cz):
    return File(Compound({
        "DataVersion": Int(DATA_VERSION), "xPos": Int(cx), "zPos": Int(cz), "yPos": Int(-4),
        "Status": String("minecraft:full"),
        "sections": List[Compound]([section_nbt(cx, cz, sy) for sy in SECTIONS]),
    }), root_name="")


def main(out):
    region_dir = os.path.join(out, "region")
    os.makedirs(region_dir, exist_ok=True)
    for f in os.listdir(region_dir):
        os.remove(os.path.join(region_dir, f))

    chunks = {(x // 16, z // 16) for (x, _, z) in blocks} | {EMPTY_CHUNK}
    regions = {}
    for cx, cz in sorted(chunks):
        regions.setdefault((cx // 32, cz // 32), []).append((cx, cz))

    for (rx, rz), members in sorted(regions.items()):
        header = bytearray(8192)
        body = bytearray()
        for n, (cx, cz) in enumerate(members):
            buf = io.BytesIO()
            chunk_nbt(cx, cz).write(buf)
            raw = buf.getvalue()
            external = (cx, cz) == UNIFORM_CHUNK
            compression = [2, 1, 3][n % 3]  # zlib, gzip, none
            payload = {1: gzip.compress, 2: zlib.compress, 3: lambda b: b}[compression](raw)
            if external:
                with open(os.path.join(region_dir, f"c.{cx}.{cz}.mcc"), "wb") as f:
                    f.write(zlib.compress(raw))
                record = (1).to_bytes(4, "big") + bytes([2 | 0x80])
            else:
                record = (len(payload) + 1).to_bytes(4, "big") + bytes([compression]) + payload
            record += b"\0" * (-len(record) % 4096)
            sector = 2 + len(body) // 4096
            i = 4 * ((cx & 31) + (cz & 31) * 32)
            header[i:i + 3] = sector.to_bytes(3, "big")
            header[i + 3] = len(record) // 4096
            header[4096 + i:4096 + i + 4] = (1_700_000_000).to_bytes(4, "big")
            body += record
        with open(os.path.join(region_dir, f"r.{rx}.{rz}.mca"), "wb") as f:
            f.write(header + body)

    level = io.BytesIO()
    File(Compound({"Data": Compound({"LevelName": String("skyblock-test"),
                                     "DataVersion": Int(DATA_VERSION)})}), root_name="").write(level)
    with open(os.path.join(out, "level.dat"), "wb") as f:
        f.write(gzip.compress(level.getvalue()))

    with open(os.path.join(out, "expected-blocks.txt"), "w") as f:
        for (x, y, z), (name, props) in sorted(blocks.items()):
            p = ",".join(f"{k}={v}" for k, v in sorted(props.items()))
            f.write(f"{x} {y} {z} {name}{'[' + p + ']' if p else ''}\n")
    print(f"wrote {len(blocks)} blocks in {len(chunks)} chunks across {len(regions)} region files")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "skyblock-test")
