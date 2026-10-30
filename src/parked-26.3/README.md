# Parked for the Minecraft 26.3 port

These sources are not compiled. They are performance work written against
Minecraft 1.21.1 internals that 26.3 removed or rewrote, and vanilla 26.3 does
the same job without them, only with more allocation:

- Render-region snapshot pooling: `RenderedChunkMixin`, `ChunkRendererRegionMixin`,
  `ChunkRendererRegionBuilderMixin`, `ClientChunkMixin`, `PackedIntegerArrayMixin`,
  `RebuildTaskMixin`, `RegionSnapshotRelease`, `RegionBuilderRelease`,
  `RenderRegionStoragePool`.
- Allocation-free variant and part selection: `WeightedBakedModelMixin`,
  `MultipartBakedModelMixin`.
- Off-thread, time-sliced visibility walk and frustum index:
  `ChunkRenderingDataPreparerMixin`, `RenderableChunksMixin`.
- Per-section GL buffers and dynamic buffer uploads: `BuiltChunkBuffersMixin`,
  `VertexBufferMixin`, `DynamicBufferUpload` (and its test).
- Server per-tick chunk list: `ServerChunkManagerMixin`, `ChunkHolderMixin`,
  `ChunkHolderTickEntry`.

Port them back against 26.3's `SectionCopy`/`RenderSectionRegion`,
`WeightedVariants`/`MultiPartModel`, `SectionOcclusionGraph` and
`ServerChunkCache` once the renderer works, and measure each one.
