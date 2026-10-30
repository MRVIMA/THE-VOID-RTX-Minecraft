# RTX Remix for Minecraft → Fabric 26.3: port status

Target: Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.161.0+26.3, Java 25
(the versions installed in TLauncher at `%APPDATA%\.minecraft`).
Untouched 1.21.1 original: `rtx-remix-minecraft-1.21.1-original\`.

**State: RTX path tracing works on 26.3.** Compiles, all 796 tests pass, every
mixin applies, and on an RTX 3050 Laptop GPU (driver 621.01) the dev client joins
a world rendered through Remix: path-traced water reflections, sky and lighting,
with hand and HUD composited.

Native parts come from NVIDIA's official release v0.1.0
(`rtx-remix-renderer-0.1.0-202610230634-170197a.zip`): its `remixbridge.dll` and
Remix runtime. The bridge's JNI surface was checked against this port with
`javap`: every native signature and every wrapper class it reads is identical
(only the pure-Java `CommandLineRedaction` helper differs). No MSVC needed.

Jars in `rtx-remix-minecraft-main\build\libs\`:
- `...-rtx-win64.jar` (1.1 GB): the 26.3 mod plus the win64 runtime, `rtx.conf`,
  `rtx-remix/` payload, runtime textures and runtime licenses. This is the one to
  install; on first launch it extracts `remix-sdk/` etc. into the game folder.
- `....jar` (1.3 MB): no native payload; runs in vanilla-fallback mode.

Without the Remix runtime the mod falls back to vanilla rendering.

## Done

- Build: Loom 1.19.3 (`net.fabricmc.fabric-loom`, no remapping: 26.x ships
  unobfuscated), Gradle wrapper 9.8.2, Java 25 toolchain, `fabric.mod.json`,
  mixin config and access widener updated (old widener entries that no longer
  resolve were dropped; new ones for `RenderType`/`RenderSetup` and `ViewArea`).
- Names: all mod code, tests, mixin targets and the access widener migrated from
  Yarn to Mojang names with Loom's migration tasks. `ResourceLocation` →
  `Identifier`, `Util` moved, colour blocks → `ColorCollection.pick(DyeColor)`.
- Window and present: OpenGL backend forced (`PreferredGraphicsApiMixin`; the
  Remix interop is GL-based), GL 4.4 context requested in `GlBackend.createWindow`
  (`GlBackendMixin`), HWND read from SDL window properties, buffer-swap redirect
  moved to `GlSurface.present` (`RenderSystemMixin`).
- Input: GLFW → SDL-era `InputConstants`, `KeyEvent`, `MouseButtonInfo`; SDL text
  input is withheld during overlay capture; key bindings via `KeyMappingHelper`
  with a registered `KeyMapping.Category`.
- Screens: `Minecraft.screen`/`setScreen` → `Minecraft.gui.screen()`/`setScreen`.
- Terrain capture: `SectionBuilderMixin` retargeted to 26.3 `SectionCompiler`
  (`isSolidRender()`, `getRenderShape()`, `FluidRenderer.tesselate`);
  `BlockQuadEmitter` reads `BlockStateModel` parts and record `BakedQuad`s, takes
  layer and tint per quad, and sends modded models through FRAPI's
  `Renderer.quadEmitter` callback; fluids read `FluidModel` sprites and tints.
- World render integration:
  - Vanilla terrain draws cancelled at `ChunkSectionsToRender.renderGroup/renderOit`,
    clouds at `CloudRenderer.render/renderOit`, particles at their submit.
  - Section scheduling (`WorldRendererTerrainAccess`) moved to `RenderSection`
    nodes, `compileAsync` and `LevelExtractor.setSectionDirty`; the water-occlusion
    rebuild margin moved to `LevelExtractor.setBlocksDirty`.
  - Empty-section removal and block-entity flags via `RenderSection.setSectionMesh`.
  - Star field captured from vanilla's `SkyRenderer.buildStars` (`StarFieldCapture`).
  - Frame order: world render → first-person capture → Remix submit + composite →
    screen effects → GUI (`GameRendererMixin`). The composite is a direct GL blit
    into the main render target (`TextureOverlay`).
- Entity capture: 26.3 renderers submit nodes instead of writing vertices. A new
  `RemixSubmitCollector` executes models, block models, items and custom
  geometry straight into the existing capture consumers. Entities, block entities
  and the crack are captured at extraction (`LevelExtractorMixin`), the last point
  that has both the game object and its render state; first-person hands go
  through `FirstPersonHandsAndItemsRenderer`; block outlines through Fabric's
  `LevelRenderEvents.BEFORE_BLOCK_OUTLINE`. Baked quads are repacked into the old
  vertex layout so the capture geometry code is unchanged.
- Render-type classification reads 26.3 pipelines and blend functions.
- Textures: GPU texture uploads mirrored at `FrontendCommandEncoder.writeToTexture`;
  glyph pages at `GlyphBitmap.upload`; animated sprites forwarded from the CPU frame
  sheet when `SpriteContents.AnimationState` advances (the state gets its contents
  and atlas position from the sprite that creates it).
- Time of day: `util/WorldTime` over world clocks and the `SUN_ANGLE` attribute;
  sky type → `DimensionType.Skybox`; cloud height/colour, stars and moon phase
  from environment attributes; FOV from the camera's projection (`util/CameraFov`).
- Copper Beacon: registration, block entity save data, menu and screen, articulated
  renderer, and its data files in the 26.x formats (string recipe ingredients,
  single loot `condition`, `items/` model definition).
- Load-time fixes found by launching: sprite animation outer-instance field, sky
  light `isEdgeOccluded` signature, `ClientChunkCache.replaceWithPacketData`
  handler, particle collision redirect, chunk handlers, `Thread.yield` hooks.
- Atlas padding: 26.x pads every sprite in its atlas (`1 << mipLevel <<
  anisotropy`), and `getX()/getY()` are the padded cell's corner while the UVs start
  `padding` pixels in. Remix-side atlases, LabPBR maps, particle and entity atlases and
  animation patches now place pixels at the real origin (`texture/SpritePixels`), and
  the albedo atlas fills the padding with edge pixels. Before this, textures were
  shifted off their UVs: half-textures with dark gaps at high mipmap/anisotropy.
- FRAPI cull test: a null cull face (never culled) crashed section builds whenever
  Continuity wrapped a model. Fixed in `BlockQuadEmitter.frapiCullTest`.
- Translucent layer: 26.x assigns a quad's layer from its sprite's pixels
  (`ChunkSectionLayer.byTransparency`), so any sprite with one partly transparent
  texel, fire among them, lands on TRANSLUCENT. The translucent material reads the
  albedo as transmittance, turning empty black texels into black glass. Only
  `HalfTransparentBlock`, `BeaconBeamBlock` and `NetherPortalBlock` now take it; other
  translucent-layer quads draw as cutout, as in 1.21.1 (`BlockQuadEmitter.layerName`).
- Skins from mods: textures that create and upload their own GPU texture (TLauncher's
  skin mod `LightTexture`) never reached the `DynamicTexture` hooks, so player bodies
  were dropped ("no material could be created"). Whole-image uploads up to 512x512
  are now mirrored at `CommandEncoder.writeToTexture(GpuTexture, NativeImage)` and
  bound to the id on register (`TextureUploadMirrorMixin`, `TextureManagerMixin`).
- Vanilla fallback: when the Remix renderer is not initialized (runtime missing or
  failed to start) RTX capture is switched off at client start. Before this, capture
  stayed on and hid all terrain from vanilla with nothing drawing it, so the world
  was invisible. (The 1.21.1 original behaves the same way.)
- Performance-only code whose vanilla targets are gone is parked uncompiled in
  `src/parked-26.3/` (see its README): snapshot pooling, allocation-free variant
  selection, off-thread visibility walk, ViewArea torus and chunk-load re-flag
  filter, per-section GL buffers, server tick list.

## Remaining

1. **Longer play testing** in TLauncher: entities, block entities, particles,
   nether/end, Copper Beacon. Only a short in-world look has been done.
2. **Weather** (`RemixWeatherRenderer`): rebuilt in raw GL over the composite, reading vanilla's `WeatherRenderState` and depth-testing against the Remix depth image. Rain was seen and is blocked by walls; snow has not been seen in-game.
   Puddles (`weather/RemixPuddles`): per-chunk water and mud meshes cut from a noise
   field by a wetness value, with tufts skipped on hollow grass blocks in
   `BlockQuadEmitter`. Seen in rain; drying, ripple animation and the frame-rate cost
   have not been checked.
3. Text and the entity fire overlay are not captured by the new collector yet.
   Name tags are drawn in the HUD layer (`entity/RemixNameTags`): each extracted
   name is projected to the screen, with vanilla's size and wall rules. A name
   behind a wall was seen faint, and the sneaking rule has not been seen in-game.
4. Interpolated sprite animations are forwarded at key frames only; the blend
   between frames happens on the GPU.
5. Re-port the parked performance work against 26.3's renderer where it still pays.

6. Block atlas limits: LabPBR maps are filtered out of the block and item atlases
   (`assets/minecraft/atlases`), since the loader reads them directly and in the atlas
   they sat beside their own sprite, showing as violet lines at block edges. At 256 the
   block atlas is 2048² and video memory peaks near 2.7 GB in the flat test world.
   Sizes above 256 were last tried before this filter, when they ran out of Java heap.

## RTX Remix Toolkit capture (USD) does not work with this runtime

- With `rtx.gpuResidentScene = true` (required by the mod; material updates are
  "only supported for GPU-resident scenes") Capture Scene never starts:
  `SceneManager::prepareSceneData` returns before `GameCapturer::step()` when the CPU
  instance manager is empty, and the GPU-resident path leaves it empty.
- With it off, the capture starts (`[GameCapturer][0000] New capture`) and the runtime
  crashes at once: access violation in `d3d9.dll+0x306A4D` (runtime build
  `minecraft-rtx2-71a581da`, Sentry session 19cb5486-a251-4b46-fd39-db79d5f978ec).
- Both are inside NVIDIA's runtime. A Toolkit project needs a capture, so it is blocked
  until NVIDIA supports capturing Remix-API scenes. The mod's own authoring routes are
  LabPBR resource packs and the conf layers in `rtx-remix/mods/minecraft-logic`.

## Installing in TLauncher

- Installed: the `-rtx-win64.jar` is in `%APPDATA%\.minecraft\mods` with
  `fabric-api-0.161.0+26.3`. The 1.21.1 Fabric API and the lightweight jar were
  moved to `%APPDATA%\.minecraft\mods-disabled`.
- Iris replaces the world renderer as well; do not run it together with this mod.
  It was moved to `mods-disabled` too.
- First launch extracts about 1 GB (`remix-sdk/`, `rtx.conf`, `rtx-remix/`,
  `textures/`) into `.minecraft`, so it takes longer.
- DLSS Ray Reconstruction is not available on the RTX 3050 Laptop; Remix falls
  back on its own. It also lowers quality settings for 8 GB GPUs.
