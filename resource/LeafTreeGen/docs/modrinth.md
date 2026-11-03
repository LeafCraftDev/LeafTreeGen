# LeafTreeGen

Procedural, biome-aware custom trees that generate through vanilla worldgen.

LeafTreeGen adds dozens of custom tree species. Trees are mathematically defined in JSON and baked into vanilla `.nbt` structure templates at startup, then added to jigsaw template pools so the game's own worldgen handles placement. No runtime block-placement listeners.

Made as a cross-platform utility based on the Python code I originally wrote for my personal Iris world using `.iob` files. Iris now adopts my procedural engine in its latest builds.

I've reworked it so you can optionally run it once for datapack (`.nbt`-based) generation, or keep it around for its ability to manage procedural generation and custom saplings.

---

## Screenshots

![Cherry Grove Giant](https://raw.githubusercontent.com/DailyStruggle/leaf-treegen/main/docs/images/cherry-grove-giant.png)
*Cherry Grove Giant - a towering cherry-blossom giant anchors its matching biome above a grove of smaller cherry trees.*

![Dark forest canopy](https://raw.githubusercontent.com/DailyStruggle/leaf-treegen/main/docs/images/dark-forest-canopy.png)
*Dark forest canopy - dense overlapping high canopies with scattered giant mushrooms, viewed from above the treeline.*

![Dark Forest Mushroom](https://raw.githubusercontent.com/DailyStruggle/leaf-treegen/main/docs/images/dark-forest-mushroom.png)
*Dark Forest Mushroom.*

![Custom savanna trees](https://raw.githubusercontent.com/DailyStruggle/leaf-treegen/main/docs/images/custom-savanna-trees.png)
*Custom savanna trees.*

![Christmas Tree](https://raw.githubusercontent.com/DailyStruggle/leaf-treegen/main/docs/images/christmas-tree.png)
*Christmas Tree - all trees are placeable via NBT-tagged saplings.*

---

## Features

- **Biome-matched species** - savanna acacias, dark-forest canopies, glacier pines, cherry groves, spiral-crown forests, and rainbow groves, mapped to the biomes they suit
- **Procedural engine** - trees are built in Java from per-species parameters: height, trunk shape, lean angle, and canopy layers
- **Jigsaw worldgen** - the plugin writes a vanilla worldgen datapack on first load and lets Minecraft's structure system place the trees
- **Plantable saplings** - `/leaftree give` hands out NBT-tagged saplings that grow into a random variant when planted in a valid biome
- **Reload command** - `/leaftree reload` rebakes all variants and regenerates the datapack
- **Server-friendly placement** - large trees are placed across multiple ticks under a per-tick budget to keep the main thread responsive
- **Multi-platform** - one jar runs on Paper, Folia, Fabric, and NeoForge with the same features (worldgen, saplings, and the `/leaftree` command) everywhere

---

## Species Highlights

| Category | Examples |
|---|---|
| Temperate | Forest, Birch Forest, Old-Growth Birch, Maple-Birch Giants, Ashwood |
| Tropical | Jungle, Cherry Grove, Jacaranda Grove |
| Cold | Taiga, Old-Growth Pine Taiga, Glacier Pine |
| Dark & Exotic | Dark Forest Giants, Spiral-Crown Forest, Rainbow Grove, Glowcap |

<details>
<summary>Full biome tree map</summary>

| Biome | Species | Iconic shape | Role |
|---|---|---|---|
| `forest` | `forest` | Rounded oak, mid to large | Primary |
| `flower_forest` | `oak-small-mid` | Small-to-mid rounded oak, sporadic | Primary |
| `plains` | `oak-small-mid` | Sparse small-to-mid oak | Primary |
| `meadow` | `oak-small-mid` | Sparse small-to-mid oak | Primary |
| `meadow` | `meadow-azalea` | Small oak trunk with azalea crown | Sprinkled accent |
| `meadow` | `rainbow-grove` | Birch trunk with multi-hue canopy | Rare landmark |
| `dark_forest` | `glowcap` | Lit brown-mushroom shrub | Understory |
| `dark_forest` | `dark-forest-floor-lights` | Tiny shroomlight glow shrooms | Floor litter |
| `dark_forest` | `dark-forest-mids` | Small-to-medium twisted dark oak | Midstory |
| `dark_forest` | `dark-forest-megas` | Titan dark oak with massive canopy | Canopy |
| `birch_forest` | `birch-forest` | Tall slender white birch | Primary |
| `old_growth_birch_forest` | `old-growth-birch-forest` | Towering old-growth birch | Primary |
| `old_growth_birch_forest` | `maple-birch-giants` | Giant maple-and-birch landmark | Rare landmark |
| `taiga` | `taiga` | Conical spruce | Primary |
| `snowy_taiga` | `evergreen` | Snow-dusted conical spruce | Primary |
| `snowy_taiga` | `christmas-tree` | Festive decorated spruce with ornaments | Very rare landmark |
| `old_growth_pine_taiga` | `old-growth-pine-taiga` | Tall bare-trunked pine | Primary |
| `old_growth_spruce_taiga` | `old-growth-pine-taiga` | Tall bare-trunked pine | Primary |
| `snowy_plains` | `glacierpine` | Frost-laden spruce with icicle tips | Primary |
| `ice_spikes` | `glacierpine` | Frost-laden spruce with icicle tips | Primary |
| `grove` | `glacierpine` | Frost-laden spruce with icicle tips | Primary |
| `snowy_slopes` | `glacierpine` | Frost-laden spruce with icicle tips | Sparse |
| `frozen_peaks` | `glacierpine` | Frost-laden spruce with icicle tips | Sparse |
| `jagged_peaks` | `glacierpine` | Frost-laden spruce with icicle tips | Sparse |
| `jungle` | `jungle` | Jungle trees, small to giant | Primary |
| `sparse_jungle` | `jungle` | Jungle trees, small to giant | Primary |
| `bamboo_jungle` | `bamboo-jungle` | Jungle trees ringed by bamboo | Primary |
| `savanna` | `savanna` | Flat-topped leaning acacia | Primary |
| `savanna_plateau` | `savanna` | Flat-topped leaning acacia | Primary |
| `windswept_savanna` | `savanna` | Flat-topped leaning acacia | Primary |
| `cherry_grove` | `cherry-grove` | Layered pink blossom crown | Primary |
| `cherry_grove` | `jacaranda-grove` | Giant weeping cherry-wood jacaranda | Rare landmark |
| `windswept_hills` | `ashwood` | Gaunt gray dead pale-oak with cobwebs | Primary |
| `windswept_forest` | `ashwood` | Gaunt gray dead pale-oak with cobwebs | Primary |
| `windswept_gravelly_hills` | `ashwood` | Gaunt gray dead pale-oak with cobwebs | Primary |
| `swamp` | `swamp` | Vine-draped weeping oak + mangrove | Primary |
| `mangrove_swamp` | `swamp` | Vine-draped weeping oak + mangrove | Primary |

</details>

---

## Commands

| Command | Description |
|---|---|
| `/leaftree generate species=<id>` | Place a tree at your cursor (admin test) |
| `/leaftree give species=<id> [player] [amount]` | Hand out custom saplings |
| `/leaftree list [species=<id>]` | List registered species or variants |
| `/leaftree reload` | Apply config changes and regenerate the datapack |

Requires the `leaftreegen.admin` permission node.

---

## Configuration

Species are defined as JSON files in the `species/` folder. Biome assignments and placement tuning (spacing, separation, salt) live in `config.yml`, with per-species overrides.

```yaml
generation-mode: DATAPACK   # DATAPACK | PROCEDURAL | BOTH | NONE

placement:
  glacierpine:
    spacing: 6
    separation: 3
    biomes:
      - minecraft:snowy_taiga
      - minecraft:grove
```

```json
{
  "name": "glacierpine",
  "procedural": {
    "trunk-block": "minecraft:spruce_log",
    "leaf-block": "minecraft:spruce_leaves",
    "height-min": 10,
    "height-max": 18,
    "trunk-shape": "LINEAR",
    "canopy": [
      { "yOffset": 0, "radius": 4 },
      { "yOffset": 3, "radius": 3 },
      { "yOffset": 6, "radius": 2 }
    ]
  }
}
```

## Requirements

- Java 25+
- One of:
  - Paper or Folia 1.21+
  - Fabric 26.1+ (Fabric Loader 0.18.4+, Fabric API)
  - NeoForge 26.1+
