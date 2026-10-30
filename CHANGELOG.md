# Changelog

## THE VOID RTX for Minecraft 26.3 — v0.1.0 (26.3 port)

First release. An unofficial port of NVIDIA's RTX Remix for Minecraft 0.1.0
(Minecraft 1.21.1) to Minecraft Java 26.3 on Fabric. Not made or supported by
NVIDIA or Mojang.

### Added
- **Preset menu**: press F9 or type `/void menu` to switch between Lite, Standard and
  Cinematic. Each sets the materials pack and graphics quality together, and the choice
  is remembered. Commands: `/void profile low`, `balanced` or `cinematic`.
- **Rain and snow** under RTX: drawn over the path-traced image, hidden by walls and
  roofs, lit by the world, with rain softened to a pale grey.
- **Name tags** under RTX: player and mob names above their heads, faint through
  walls, and hidden behind walls for sneaking players.
- **Muddy puddles** in rain: water collects in hollows on grass, dirt, paths and
  farmland, with a rim of wet mud and real sky reflections. Puddles grow during rain
  and dry up afterwards. Grass tufts stay clear of the hollows in all weather.
- Support for Minecraft **26.3**, Fabric Loader **0.20.1**, Fabric API **0.161.0+26.3**
  and Java **25**.
- Bundled NVIDIA RTX Remix runtime (from NVIDIA's public v0.1.0 release), so a
  single jar gives full path tracing.
- Automatic fallback to normal Minecraft rendering when the RTX runtime cannot
  start, instead of an invisible world.
- Player skins from skin mods (including TLauncher's) now render in RTX.

### Changed
- All commands now start with `/void` instead of `/rtx`.
- **Natural daylight**: a clearer, bluer sky and a small soft glow around the sun in
  place of a wide white blowout.
- **Cinematic look**: warmer light, richer colour, firmer contrast, deeper shadows
  and a light haze in the distance. Lantern-lit rooms adapt brighter.
- Rendering rebuilt for Minecraft 26.3's new renderer: terrain, entities, block
  entities, held items, particles, sky, clouds and textures.
- Smaller download: the jar is compressed (about 620 MB instead of 1.1 GB).

### Fixed
- Textures shifted off their blocks (half-drawn textures with dark gaps), caused
  by 26.3's padded texture atlas.
- Black patches inside fire and other blocks with soft-edged textures.
- Crash a few seconds after joining a world when Continuity is installed.
- Crash when loading a world with the Copper Beacon's recipe and loot table.
- Copper Beacon item missing its model.
- Sky brightness readback failing after taking a screenshot.
- Purple lines along block edges with high-resolution textures.
- Leads not showing on animals.
- Much less video memory: material maps no longer take up room in the block atlas.

### Known issues
- Text and burning-mob fire are not drawn under RTX yet.
- "Capture Scene" (RTX Remix Toolkit USD capture) does not work with this
  runtime and can crash the game.
- Pressing "Save" in the Alt+X menus overwrites the mod's own `rtx.conf`.
- Not compatible with Iris, OptiFine, shader packs, Sodium or Embeddium.

---

## THE VOID RTX Materials Lite (resource pack) — v1.5.0

A lighter edition of Materials v1.5.0 for low-end PCs.

### Changed
- Every texture is 32×32 or smaller, down from 128×128 for ground and 64×64 for
  plants and leaves.
- No 3D grass on grass blocks, so far fewer surfaces for the path tracer.

---

## THE VOID RTX Materials (resource pack) — v1.6.0

### Added
- **256×256 timber and stone**: planks, logs and log ends for nine trees, bookshelves
  of gold-banded books, cobblestone, mossy cobblestone, stone and stone bricks. Each
  wood keeps its own colour.

### Changed
- Needs a little more video memory: about 2.7 GB in testing on a 4 GB card.

---

## THE VOID RTX Materials (resource pack) — v1.5.0

### Added
- **Rain splashes**: four new shapes of water crowns and flying droplets.

---

## THE VOID RTX Materials (resource pack) — v1.4.0

### Changed
- **Tree leaves** redrawn at 4× detail as many small pointed leaves with real gaps,
  for every tree: spruce gets needles, and each species keeps its own colour.
  Sunlight still glows through them.
- Biome tree colours are deeper and more muted, to match the grass.

---

## THE VOID RTX Materials (resource pack) — v1.3.0

### Changed
- **Lava** redrawn: dark drifting basalt crust with glowing cracks and molten pools,
  animated in a seamless loop, and flowing lava scrolls downhill. Only the cracks
  emit light, so lava no longer blows out to a flat pale sheet.

---

## THE VOID RTX Materials (resource pack) — v1.2.0

### Added
- **Crops**: wheat, carrots, potatoes and beetroots redrawn in every growth stage
  with real stems and leaves at 4× detail. Wheat ripens from green to golden ears;
  ripe carrots, potatoes and beetroots show at the soil line.
- **Farmland**: 128×128 tilled soil with furrows; watered soil is darker and damp.
- **Sugar cane**: jointed stalks.

---

## THE VOID RTX Materials (resource pack) — v1.1.0

### Added
- **Natural ground**: original 128×128 textures (8× vanilla's detail) for grass,
  dirt, coarse dirt, rooted dirt, podzol and dirt paths, with matching materials.
- **3D grass** on every grass block, in random layouts so it never lines up in rows.
- Redrawn short grass and tall grass with thin, dense blades.

### Changed
- Grass colours in every biome are deeper and more muted.
- Grass and plants are matte, so they no longer shine in sunlight.

---

## THE VOID RTX Materials (resource pack) — v1.0.0

First release. Realistic RTX materials (LabPBR) for Minecraft 26.3, for use with
THE VOID RTX.

### Added
- Materials for **1,292 textures**: every block, every tool and weapon, chests and
  armor, worn and as items.
- **Tools and weapons** split into a wooden handle and a real metal or diamond
  head, for every tier.
- **Chests** in wood with metal latches; ender chests in obsidian and gold;
  copper chests that weather into green patina.
- **Armor**: diamond with real gem reflectance, polished gold, brushed iron,
  copper, dark netherite, ring-relief chainmail, turtle shell and leather.
- **Planks** with recessed gaps between the boards and a satin finish.
- **Polished and smooth stone** (polished deepslate, andesite, blackstone...)
  honed and glossy with bevelled edges, like cut stone.
- **Frameless glass**: clear glass, panes and all 16 stained glasses without the
  dark grid between blocks.
- **Smoked tinted glass** that is dark and violet but lets bright light, such as
  lava, glow through.
- Pack icon.

### Kept as NVIDIA made them
- NVIDIA's hand-made materials for glowing blocks, leaves and ice.
- Glowing, animated and food textures are left vanilla.

### Fixed
- Glass, glass pane and tinted glass icons disappearing from the inventory.
