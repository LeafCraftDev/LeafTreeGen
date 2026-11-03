"""Sponge Schematic v3 NBT writer and Iris V2 IOB writer (stdlib only)."""

import gzip
import struct
import io

DATA_VERSION = 3578  # Minecraft 1.20.1

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11


def _name(b: bytearray, s: str) -> None:
    """Append an NBT tag name: big-endian unsigned-short length followed by the UTF-8 bytes."""
    raw = s.encode("utf-8")
    b += struct.pack(">H", len(raw))
    b += raw


def _payload(b: bytearray, tag: int, value) -> None:
    """Append the binary payload for one NBT tag of the given type (recurses for lists/compounds)."""
    if tag == TAG_BYTE:
        b += struct.pack(">b", value)
    elif tag == TAG_SHORT:
        b += struct.pack(">h", value)
    elif tag == TAG_INT:
        b += struct.pack(">i", value)
    elif tag == TAG_LONG:
        b += struct.pack(">q", value)
    elif tag == TAG_STRING:
        raw = value.encode("utf-8")
        b += struct.pack(">H", len(raw))
        b += raw
    elif tag == TAG_BYTE_ARRAY:
        b += struct.pack(">i", len(value))
        b += bytes(value)
    elif tag == TAG_INT_ARRAY:
        b += struct.pack(">i", len(value))
        for v in value:
            b += struct.pack(">i", v)
    elif tag == TAG_COMPOUND:
        for t, n, v in value:
            b.append(t)
            _name(b, n)
            _payload(b, t, v)
        b.append(TAG_END)
    elif tag == TAG_LIST:
        elem_tag, items = value
        b.append(elem_tag)
        b += struct.pack(">i", len(items))
        for item in items:
            _payload(b, elem_tag, item)
    else:
        raise ValueError("unsupported tag %d" % tag)


def varint(n: int) -> bytes:
    """Encode a non-negative integer as a little-endian LEB128 varint (Sponge block-data format)."""
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            break
    return bytes(out)


def write_schematic(path: str, blocks: dict, name: str = "tree", author: str = "tree-gen") -> None:
    """Write blocks dict {(x,y,z): blockstate} to a Sponge Schematic v3 .schem file."""
    AIR = "minecraft:air"

    if not blocks:
        raise ValueError("blocks dict is empty")

    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    minx, miny, minz = min(xs), min(ys), min(zs)
    maxx, maxy, maxz = max(xs), max(ys), max(zs)
    W = maxx - minx + 1
    H = maxy - miny + 1
    L = maxz - minz + 1

    norm = {(x - minx, y - miny, z - minz): state for (x, y, z), state in blocks.items()}

    palette = {AIR: 0}
    for y in range(H):
        for z in range(L):
            for x in range(W):
                state = norm.get((x, y, z), AIR)
                if state not in palette:
                    palette[state] = len(palette)

    data = bytearray()
    for y in range(H):
        for z in range(L):
            for x in range(W):
                state = norm.get((x, y, z), AIR)
                data += varint(palette[state])

    palette_compound = [(TAG_INT, state, idx) for state, idx in palette.items()]

    blocks_compound = [
        (TAG_INT, "PaletteMax", len(palette)),
        (TAG_COMPOUND, "Palette", palette_compound),
        (TAG_BYTE_ARRAY, "Data", data),
        (TAG_LIST, "BlockEntities", (TAG_COMPOUND, [])),
    ]

    schematic_compound = [
        (TAG_INT, "Version", 3),
        (TAG_INT, "DataVersion", DATA_VERSION),
        (TAG_COMPOUND, "Metadata", [
            (TAG_STRING, "Name", name),
            (TAG_STRING, "Author", author),
            (TAG_LONG, "Date", 0),
        ]),
        (TAG_SHORT, "Width", W),
        (TAG_SHORT, "Height", H),
        (TAG_SHORT, "Length", L),
        (TAG_INT_ARRAY, "Offset", [0, 0, 0]),
        (TAG_COMPOUND, "Blocks", blocks_compound),
    ]

    root = bytearray()
    root.append(TAG_COMPOUND)
    _name(root, "")
    _payload(root, TAG_COMPOUND, [
        (TAG_COMPOUND, "Schematic", schematic_compound),
    ])
    root.append(TAG_END)

    with gzip.open(path, "wb") as f:
        f.write(bytes(root))


def parse_blockstate(state: str):
    """Split a blockstate string into (name, properties dict).

    ``"minecraft:oak_log[axis=y,foo=bar]"`` -> ``("minecraft:oak_log", {"axis": "y", "foo": "bar"})``.
    A bare ``"minecraft:stone"`` returns ``("minecraft:stone", {})``.
    """
    if "[" in state:
        name, rest = state.split("[", 1)
        rest = rest.rstrip("]")
        props = {}
        for part in rest.split(","):
            if not part:
                continue
            k, v = part.split("=", 1)
            props[k.strip()] = v.strip()
        return name, props
    return state, {}


def write_structure_nbt(path: str, blocks: dict, data_version: int = DATA_VERSION) -> None:
    """Write blocks dict {(x,y,z): blockstate} to a vanilla structure-template .nbt file.

    Produces the gzip-compressed NBT layout consumed by ``/place template``,
    structure blocks, and jigsaw ``template_pool`` pieces:

        DataVersion : int
        size        : [int, int, int]   (W, H, L)
        palette     : [ {Name: str, Properties?: {str: str}} ]
        blocks      : [ {state: int, pos: [int, int, int]} ]
        entities    : []

    Only the blocks present in ``blocks`` are emitted; any unfilled position is
    left as structure void (i.e. untouched on placement).
    """
    if not blocks:
        raise ValueError("blocks dict is empty")

    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    minx, miny, minz = min(xs), min(ys), min(zs)
    maxx, maxy, maxz = max(xs), max(ys), max(zs)
    W = maxx - minx + 1
    H = maxy - miny + 1
    L = maxz - minz + 1

    palette = []
    palette_index = {}
    block_entries = []
    for (x, y, z), state in blocks.items():
        name, props = parse_blockstate(state)
        key = (name, tuple(sorted(props.items())))
        if key not in palette_index:
            palette_index[key] = len(palette)
            palette.append((name, props))
        block_entries.append((x - minx, y - miny, z - minz, palette_index[key]))

    palette_list = []
    for name, props in palette:
        comp = [(TAG_STRING, "Name", name)]
        if props:
            comp.append((TAG_COMPOUND, "Properties",
                         [(TAG_STRING, k, v) for k, v in props.items()]))
        palette_list.append(comp)

    blocks_list = []
    for (x, y, z, idx) in block_entries:
        blocks_list.append([
            (TAG_LIST, "pos", (TAG_INT, [x, y, z])),
            (TAG_INT, "state", idx),
        ])

    structure_compound = [
        (TAG_INT, "DataVersion", data_version),
        (TAG_LIST, "size", (TAG_INT, [W, H, L])),
        (TAG_LIST, "palette", (TAG_COMPOUND, palette_list)),
        (TAG_LIST, "blocks", (TAG_COMPOUND, blocks_list)),
        (TAG_LIST, "entities", (TAG_COMPOUND, [])),
    ]

    root = bytearray()
    root.append(TAG_COMPOUND)
    _name(root, "")
    _payload(root, TAG_COMPOUND, structure_compound)
    root.append(TAG_END)

    with gzip.open(path, "wb") as f:
        f.write(bytes(root))


def _write_java_utf(buf: io.BytesIO, s: str) -> None:
    """Write a Java DataOutputStream UTF string: big-endian unsigned short length + MUTF-8 bytes."""
    encoded = s.encode("utf-8")
    buf.write(struct.pack(">H", len(encoded)))
    buf.write(encoded)


def write_iob(path: str, blocks: dict) -> None:
    """Write blocks dict {(x,y,z): blockstate} to an Iris V2 IOB binary file.

    Format (Java DataOutputStream, big-endian):
        int   w
        int   h
        int   d
        UTF   "Iris V2 IOB;"
        short paletteSize
        UTF   blockstate  (repeated paletteSize times)
        int   blockCount
        short x, short y, short z, short paletteIndex  (repeated blockCount times)
        int   stateCount  (always 0 - no tile entities for trees)
    """
    if not blocks:
        raise ValueError("blocks dict is empty")

    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    minx, miny, minz = min(xs), min(ys), min(zs)
    maxx, maxy, maxz = max(xs), max(ys), max(zs)
    w = maxx - minx + 1
    h = maxy - miny + 1
    d = maxz - minz + 1

    # Build palette (insertion order = encounter order, matching Java behaviour).
    # Iris stores block coords RELATIVE TO THE OBJECT CENTER (w//2, h//2, d//2) and
    # re-adds that center at placement time (IrisObject.place -> at.add(getCenter())).
    #
    # X/Z: center on the bounding box so the trunk sits in the middle of the column.
    # Y:   anchor on the caller's y=0 (the trunk base), NOT the bbox bottom. The
    #      generator builds the trunk base at y=0, roots at y<0, canopy at y>0.
    #      Storing base at -(h//2) makes Iris place the trunk base exactly on the
    #      surface (surface + h//2 + (-(h//2)) = surface); roots (y<0) then bury and
    #      canopy rises above. Subtracting miny instead (bbox-centering) would push
    #      the whole tree UP by the root depth, leaving it floating.
    cx, cy, cz = w // 2, h // 2, d // 2
    palette = []
    palette_index = {}
    norm = {}
    for (x, y, z), state in blocks.items():
        nx, ny, nz = x - minx - cx, y - cy, z - minz - cz
        norm[(nx, ny, nz)] = state
        if state not in palette_index:
            palette_index[state] = len(palette)
            palette.append(state)

    buf = io.BytesIO()
    # Header: w, h, d
    buf.write(struct.pack(">iii", w, h, d))
    # Magic string
    _write_java_utf(buf, "Iris V2 IOB;")
    # Palette
    buf.write(struct.pack(">h", len(palette)))
    for state in palette:
        _write_java_utf(buf, state)
    # Blocks
    buf.write(struct.pack(">i", len(norm)))
    for (nx, ny, nz), state in norm.items():
        buf.write(struct.pack(">hhhh", nx, ny, nz, palette_index[state]))
    # States (tile entities) - always 0 for trees
    buf.write(struct.pack(">i", 0))

    with open(path, "wb") as f:
        f.write(buf.getvalue())
