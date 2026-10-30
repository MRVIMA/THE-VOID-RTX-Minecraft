/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Lays muddy puddles on open soil while it rains.
 *
 * <p>A wetness value rises during rain and falls after it, and a noise field over the
 * world, cut at a level set by wetness, decides where water stands: the patches start
 * small, spread, then shrink as the ground dries. Each loaded chunk near the camera
 * gets one mesh of two layers cut from that field, a wider ring of wet mud with
 * smooth brown water on top, so the path tracer reflects the sky in it. Chunks rebuild
 * one per frame, when wetness moves a stage or their blocks may have changed.
 *
 * <p>The field closes at the edge of any block it cannot cross, so a puddle never
 * hangs over a drop or runs up a wall.
 */
public final class RemixPuddles {
    /** Chunks kept around the camera, in each direction. */
    private static final int RADIUS_CHUNKS = 4;
    private static final int STAGES = 12;
    private static final float FILL_SECONDS = 90.0f;
    private static final float DRY_SECONDS = 150.0f;
    private static final long REFRESH_NANOS = 5_000_000_000L;

    private static final int CELLS_PER_BLOCK = 4;
    private static final int CELLS = 16 * CELLS_PER_BLOCK;
    /** Cells per texture tile: four blocks. A tile divides a chunk, so chunks meet cleanly. */
    private static final int CELLS_PER_TILE = 4 * CELLS_PER_BLOCK;

    /** Field level where water starts, from first drops to soaked ground. */
    private static final float WATER_LEVEL_DRY = 0.76f;
    private static final float WATER_LEVEL_SOAKED = 0.64f;
    /** How far below the water's level the ring of wet mud reaches. */
    private static final float MUD_MARGIN = 0.035f;
    /** Heights above the block, enough to clear the ground in the path tracer. */
    private static final float MUD_LIFT = 0.012f;
    private static final float WATER_LIFT = 0.02f;
    private static final float NOT_SOIL = Float.NEGATIVE_INFINITY;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int DOUBLE_SIDED = 1;
    private static final long NO_INSTANCE_EXTENSION = 0L;

    private static final int MUD_SIZE = 256;
    private static final int RIPPLE_SIZE = 128;
    private static final int RIPPLE_COLUMNS = 4;
    private static final int RIPPLE_ROWS = 2;
    private static final int RIPPLE_FPS = 8;
    private static final int RIPPLE_DROPS = 16;

    private final RemixAPI api;
    private final Transform transform = new Transform();
    private final Map<Long, PuddleChunk> chunks = new HashMap<>();
    private final List<RemixTexture> textures = new ArrayList<>();
    private final float[][] surface = new float[18][18];
    private final float[][] field = new float[CELLS + 1][CELLS + 1];

    private RemixMaterial mudMaterial;
    private RemixMaterial rippleMaterial;
    private RemixMaterial stillMaterial;
    private ClientLevel level;
    private float wetness;
    private long lastNanos;

    public RemixPuddles(RemixAPI api) {
        this.api = api;
    }

    /**
     * Whether a block lies where water can stand when the ground is soaked. Grass tufts
     * leave these blocks in all weather, since they would stand taller than the water.
     */
    public static boolean isHollow(int x, int z) {
        return fieldAt(x + 0.5f, z + 0.5f) >= WATER_LEVEL_SOAKED;
    }

    /** Advances wetness, rebuilds at most one chunk, and draws what stands. */
    public void submit(ClientLevel world, Vec3 camera) {
        long now = System.nanoTime();
        boolean raining = world.isRaining();
        if (world != level) {
            clearChunks();
            level = world;
            wetness = raining ? 1.0f : 0.0f;
        } else {
            float seconds = (now - lastNanos) * 1.0e-9f;
            wetness = Mth.clamp(wetness + seconds / (raining ? FILL_SECONDS : -DRY_SECONDS), 0.0f, 1.0f);
        }
        lastNanos = now;
        if (wetness <= 0.0f) {
            clearChunks();
            return;
        }
        if (!ensureMaterials()) {
            return;
        }

        int stage = Mth.ceil(wetness * STAGES) * 2 + (raining ? 1 : 0);
        int centerX = Mth.floor(camera.x) >> 4;
        int centerZ = Mth.floor(camera.z) >> 4;
        forgetFarChunks(centerX, centerZ);
        rebuildNearestStale(world, centerX, centerZ, stage, now, raining);

        for (PuddleChunk chunk : chunks.values()) {
            if (chunk.mesh != null) {
                transform.setTranslation(chunk.x * 16.0f, 0.0f, chunk.z * 16.0f);
                api.drawInstance(chunk.mesh.getHandle(), transform.matrix, 0, DOUBLE_SIDED, NO_INSTANCE_EXTENSION);
            }
        }
    }

    private void forgetFarChunks(int centerX, int centerZ) {
        Iterator<PuddleChunk> it = chunks.values().iterator();
        while (it.hasNext()) {
            PuddleChunk chunk = it.next();
            if (Math.max(Math.abs(chunk.x - centerX), Math.abs(chunk.z - centerZ)) > RADIUS_CHUNKS) {
                chunk.destroyMesh();
                it.remove();
            }
        }
    }

    private void rebuildNearestStale(ClientLevel world, int centerX, int centerZ, int stage, long now, boolean raining) {
        for (int ring = 0; ring <= RADIUS_CHUNKS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = centerX + dx, z = centerZ + dz;
                    if (!world.hasChunk(x, z)) {
                        continue;
                    }
                    long key = ChunkPos.pack(x, z);
                    PuddleChunk chunk = chunks.get(key);
                    if (chunk == null) {
                        chunk = new PuddleChunk(x, z);
                        chunks.put(key, chunk);
                    } else if (chunk.stage == stage && now - chunk.builtNanos < REFRESH_NANOS) {
                        continue;
                    }
                    chunk.stage = stage;
                    chunk.builtNanos = now;
                    chunk.destroyMesh();
                    chunk.mesh = buildMesh(world, x, z, raining);
                    return;
                }
            }
        }
    }

    private RemixMesh buildMesh(ClientLevel world, int chunkX, int chunkZ, boolean raining) {
        readSurface(world, chunkX * 16, chunkZ * 16);
        readField(chunkX * 16, chunkZ * 16);

        float waterLevel = Mth.lerp(wetness, WATER_LEVEL_DRY, WATER_LEVEL_SOAKED);
        PuddleLayer mud = new PuddleLayer();
        PuddleLayer water = new PuddleLayer();
        for (int i = 0; i < CELLS; i++) {
            for (int j = 0; j < CELLS; j++) {
                float y = surface[i / CELLS_PER_BLOCK + 1][j / CELLS_PER_BLOCK + 1];
                if (y == NOT_SOIL) {
                    continue;
                }
                mud.addCell(field, i, j, waterLevel - MUD_MARGIN, y + MUD_LIFT);
                water.addCell(field, i, j, waterLevel, y + WATER_LIFT);
            }
        }
        List<RemixMeshSurface> surfaces = new ArrayList<>(2);
        if (mud.indexCount > 0) {
            surfaces.add(mud.toSurface(mudMaterial));
        }
        if (water.indexCount > 0) {
            surfaces.add(water.toSurface(raining ? rippleMaterial : stillMaterial));
        }
        if (surfaces.isEmpty()) {
            return null;
        }
        RemixMesh mesh = new RemixMesh(surfaces.toArray(new RemixMeshSurface[0]));
        RemixMeshStore.get().create(mesh);
        return mesh;
    }

    /** The soil height of each column of the chunk and the ring around it. */
    private void readSurface(ClientLevel world, int originX, int originZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int seaLevel = world.getSeaLevel();
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                int blockX = originX + x - 1, blockZ = originZ + z - 1;
                int top = world.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ) - 1;
                pos.set(blockX, top, blockZ);
                BlockState state = world.getBlockState(pos);
                boolean soil = (state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.DIRT)
                        || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND))
                        && world.getBiome(pos).value().getPrecipitationAt(pos, seaLevel) == Biome.Precipitation.RAIN;
                surface[x][z] = soil
                        ? top + (float) state.getCollisionShape(world, pos).max(Direction.Axis.Y)
                        : NOT_SOIL;
            }
        }
    }

    /**
     * Noise at every cell corner, sunk on any corner shared by columns of unequal
     * height, which closes a puddle there.
     */
    private void readField(int originX, int originZ) {
        for (int i = 0; i <= CELLS; i++) {
            int left = (i - 1) / CELLS_PER_BLOCK + 1, right = i / CELLS_PER_BLOCK + 1;
            if (i == 0) {
                left = 0;
            }
            for (int j = 0; j <= CELLS; j++) {
                int near = (j - 1) / CELLS_PER_BLOCK + 1, far = j / CELLS_PER_BLOCK + 1;
                if (j == 0) {
                    near = 0;
                }
                float height = surface[left][near];
                boolean flat = height != NOT_SOIL && surface[right][near] == height
                        && surface[left][far] == height && surface[right][far] == height;
                field[i][j] = flat
                        ? fieldAt(originX + (float) i / CELLS_PER_BLOCK, originZ + (float) j / CELLS_PER_BLOCK)
                        : -1.0f;
            }
        }
    }

    private static float fieldAt(float x, float z) {
        return 0.6f * noise(x / 5.0f, z / 5.0f)
                + 0.3f * noise(x / 2.3f + 97.0f, z / 2.3f + 13.0f)
                + 0.1f * noise(x / 1.1f + 41.0f, z / 1.1f + 71.0f);
    }

    private static float noise(float x, float z) {
        int ix = Mth.floor(x), iz = Mth.floor(z);
        float fx = x - ix, fz = z - iz;
        float sx = fx * fx * (3.0f - 2.0f * fx), sz = fz * fz * (3.0f - 2.0f * fz);
        return Mth.lerp(sz,
                Mth.lerp(sx, hash(ix, iz), hash(ix + 1, iz)),
                Mth.lerp(sx, hash(ix, iz + 1), hash(ix + 1, iz + 1)));
    }

    private static float hash(int x, int z) {
        long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    private boolean ensureMaterials() {
        if (mudMaterial != null && !mudMaterial.isDeleted() && !rippleMaterial.isDeleted() && !stillMaterial.isDeleted()) {
            return true;
        }
        releaseAssets();
        RemixTexture mudAlbedo = upload(mudAlbedoPixels(), MUD_SIZE, MUD_SIZE, TexturePurpose.COLOR);
        RemixTexture mudNormal = upload(mudNormalPixels(), MUD_SIZE, MUD_SIZE, TexturePurpose.NORMAL_DX);
        RemixTexture ripples = upload(ripplePixels(), RIPPLE_SIZE * RIPPLE_COLUMNS, RIPPLE_SIZE * RIPPLE_ROWS,
                TexturePurpose.NORMAL_DX);
        mudMaterial = createMaterial(MaterialInfo.builder()
                .albedoTexture(mudAlbedo.getHandle())
                .normalTexture(mudNormal.getHandle())
                .opaqueExt(surfaceMaterial(0.2f, 0.16f, 0.12f, 0.4f)));
        rippleMaterial = createMaterial(MaterialInfo.builder()
                .normalTexture(ripples.getHandle())
                .spriteSheetRow(RIPPLE_ROWS)
                .spriteSheetCol(RIPPLE_COLUMNS)
                .spriteSheetFps(RIPPLE_FPS)
                .opaqueExt(surfaceMaterial(0.13f, 0.095f, 0.06f, 0.05f)));
        stillMaterial = createMaterial(MaterialInfo.builder()
                .opaqueExt(surfaceMaterial(0.13f, 0.095f, 0.06f, 0.03f)));
        return true;
    }

    private static MaterialInfoOpaqueEXT surfaceMaterial(float red, float green, float blue, float roughness) {
        return MaterialInfoOpaqueEXT.builder()
                .albedoConstant(red, green, blue)
                .roughnessConstant(roughness)
                .metallicConstant(0.0f)
                .build();
    }

    private static RemixMaterial createMaterial(MaterialInfo.Builder builder) {
        RemixMaterial material = new RemixMaterial(builder.build());
        RemixMaterialStore.get().create(material);
        return material;
    }

    private RemixTexture upload(byte[] pixels, int width, int height, int purpose) {
        ByteBuffer buffer = DirectBufferPool.acquire(pixels.length);
        buffer.put(pixels).flip();
        RemixTexture texture = new RemixTexture(width, height, TextureFormat.RGBA8_UNORM, purpose, buffer);
        RemixTextureStore.get().create(texture);
        textures.add(texture);
        return texture;
    }

    /** Height of the mud at a texel, tiling over the texture. */
    private static float mudHeight(int x, int y) {
        float u = (float) x / MUD_SIZE, v = (float) y / MUD_SIZE;
        return 0.55f * tileNoise(u, v, 6) + 0.3f * tileNoise(u, v, 17) + 0.15f * tileNoise(u, v, 53);
    }

    private static float tileNoise(float u, float v, int period) {
        float x = u * period, z = v * period;
        int ix = Mth.floor(x), iz = Mth.floor(z);
        float fx = x - ix, fz = z - iz;
        float sx = fx * fx * (3.0f - 2.0f * fx), sz = fz * fz * (3.0f - 2.0f * fz);
        int x0 = Math.floorMod(ix, period), x1 = (x0 + 1) % period;
        int z0 = Math.floorMod(iz, period), z1 = (z0 + 1) % period;
        return Mth.lerp(sz,
                Mth.lerp(sx, hash(x0 + period * 31, z0), hash(x1 + period * 31, z0)),
                Mth.lerp(sx, hash(x0 + period * 31, z1), hash(x1 + period * 31, z1)));
    }

    private static byte[] mudAlbedoPixels() {
        byte[] pixels = new byte[MUD_SIZE * MUD_SIZE * 4];
        for (int y = 0; y < MUD_SIZE; y++) {
            for (int x = 0; x < MUD_SIZE; x++) {
                float shade = 0.55f + 0.9f * mudHeight(x, y);
                int at = (y * MUD_SIZE + x) * 4;
                pixels[at] = (byte) Mth.clamp((int) (46 * shade), 0, 255);
                pixels[at + 1] = (byte) Mth.clamp((int) (33 * shade), 0, 255);
                pixels[at + 2] = (byte) Mth.clamp((int) (22 * shade), 0, 255);
                pixels[at + 3] = (byte) 255;
            }
        }
        return pixels;
    }

    private static byte[] mudNormalPixels() {
        byte[] pixels = new byte[MUD_SIZE * MUD_SIZE * 4];
        for (int y = 0; y < MUD_SIZE; y++) {
            for (int x = 0; x < MUD_SIZE; x++) {
                float height = mudHeight(x, y);
                float dx = mudHeight((x + 1) % MUD_SIZE, y) - height;
                float dy = mudHeight(x, (y + 1) % MUD_SIZE) - height;
                putNormal(pixels, (y * MUD_SIZE + x) * 4, dx * 18.0f, dy * 18.0f);
            }
        }
        return pixels;
    }

    /** A looping sheet of frames in which raindrops open rings on the water. */
    private static byte[] ripplePixels() {
        int width = RIPPLE_SIZE * RIPPLE_COLUMNS;
        int height = RIPPLE_SIZE * RIPPLE_ROWS;
        int frames = RIPPLE_COLUMNS * RIPPLE_ROWS;
        byte[] pixels = new byte[width * height * 4];
        for (int frame = 0; frame < frames; frame++) {
            float time = (float) frame / frames;
            int originX = frame % RIPPLE_COLUMNS * RIPPLE_SIZE;
            int originY = frame / RIPPLE_COLUMNS * RIPPLE_SIZE;
            for (int y = 0; y < RIPPLE_SIZE; y++) {
                for (int x = 0; x < RIPPLE_SIZE; x++) {
                    float here = rippleHeight(x, y, time);
                    float dx = rippleHeight(x + 1, y, time) - here;
                    float dy = rippleHeight(x, y + 1, time) - here;
                    putNormal(pixels, ((originY + y) * width + originX + x) * 4, dx * 3.0f, dy * 3.0f);
                }
            }
        }
        return pixels;
    }

    private static float rippleHeight(int x, int y, float time) {
        float height = 0.0f;
        for (int drop = 0; drop < RIPPLE_DROPS; drop++) {
            float age = (time + hash(drop, 7)) % 1.0f;
            float dx = wrappedDistance(x - hash(drop, 1) * RIPPLE_SIZE);
            float dy = wrappedDistance(y - hash(drop, 2) * RIPPLE_SIZE);
            float past = Mth.sqrt(dx * dx + dy * dy) - age * 26.0f;
            float fade = (1.0f - age) * (1.0f - age);
            height += fade * Mth.cos(past * 0.9f) * (float) Math.exp(-past * past / 30.0f);
        }
        return height;
    }

    private static float wrappedDistance(float distance) {
        float wrapped = Math.abs(distance) % RIPPLE_SIZE;
        return Math.min(wrapped, RIPPLE_SIZE - wrapped);
    }

    private static void putNormal(byte[] pixels, int at, float slopeX, float slopeY) {
        float length = Mth.sqrt(slopeX * slopeX + slopeY * slopeY + 1.0f);
        pixels[at] = (byte) (int) ((-slopeX / length * 0.5f + 0.5f) * 255.0f);
        pixels[at + 1] = (byte) (int) ((-slopeY / length * 0.5f + 0.5f) * 255.0f);
        pixels[at + 2] = (byte) (int) ((1.0f / length * 0.5f + 0.5f) * 255.0f);
        pixels[at + 3] = (byte) 255;
    }

    private void clearChunks() {
        for (PuddleChunk chunk : chunks.values()) {
            chunk.destroyMesh();
        }
        chunks.clear();
    }

    /** Drops every mesh, material and texture; they return when puddles next stand. */
    public void clear() {
        releaseAssets();
        level = null;
    }

    private void releaseAssets() {
        clearChunks();
        for (RemixMaterial material : new RemixMaterial[]{mudMaterial, rippleMaterial, stillMaterial}) {
            if (material != null && !material.isDeleted()) {
                material.destroy();
            }
        }
        mudMaterial = rippleMaterial = stillMaterial = null;
        for (RemixTexture texture : textures) {
            if (!texture.isDeleted()) {
                texture.destroy();
            }
        }
        textures.clear();
    }

    private static final class PuddleChunk {
        final int x;
        final int z;
        int stage = -1;
        long builtNanos;
        RemixMesh mesh;

        PuddleChunk(int x, int z) {
            this.x = x;
            this.z = z;
        }

        void destroyMesh() {
            if (mesh != null && !mesh.isDeleted()) {
                mesh.destroy();
            }
            mesh = null;
        }
    }

    /** The part of a field above a level, as flat polygons with tiling UVs. */
    private static final class PuddleLayer {
        private static final int[] CORNER_X = {0, 1, 1, 0};
        private static final int[] CORNER_Z = {0, 0, 1, 1};

        private final float[] polygonX = new float[8];
        private final float[] polygonZ = new float[8];
        private HardcodedVertex[] vertices = new HardcodedVertex[1024];
        private int[] indices = new int[2048];
        private int vertexCount;
        int indexCount;

        void addCell(float[][] field, int i, int j, float level, float y) {
            int corners = 0;
            for (int k = 0; k < 4; k++) {
                int next = (k + 1) & 3;
                float a = field[i + CORNER_X[k]][j + CORNER_Z[k]];
                float b = field[i + CORNER_X[next]][j + CORNER_Z[next]];
                if (a >= level) {
                    polygonX[corners] = i + CORNER_X[k];
                    polygonZ[corners++] = j + CORNER_Z[k];
                }
                if (a >= level != b >= level) {
                    float t = (level - a) / (b - a);
                    polygonX[corners] = i + Mth.lerp(t, CORNER_X[k], CORNER_X[next]);
                    polygonZ[corners++] = j + Mth.lerp(t, CORNER_Z[k], CORNER_Z[next]);
                }
            }
            if (corners < 3) {
                return;
            }
            if (vertexCount + corners > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            if (indexCount + (corners - 2) * 3 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            int tileX = i / CELLS_PER_TILE * CELLS_PER_TILE, tileZ = j / CELLS_PER_TILE * CELLS_PER_TILE;
            int first = vertexCount;
            for (int k = 0; k < corners; k++) {
                vertices[vertexCount++] = HardcodedVertex.create(
                        polygonX[k] / CELLS_PER_BLOCK, y, polygonZ[k] / CELLS_PER_BLOCK,
                        0.0f, 1.0f, 0.0f,
                        (polygonX[k] - tileX) / CELLS_PER_TILE, (polygonZ[k] - tileZ) / CELLS_PER_TILE,
                        WHITE);
            }
            for (int k = 1; k < corners - 1; k++) {
                indices[indexCount++] = first;
                indices[indexCount++] = first + k;
                indices[indexCount++] = first + k + 1;
            }
        }

        RemixMeshSurface toSurface(RemixMaterial material) {
            return new RemixMeshSurface(material)
                    .setVertices(vertices, vertexCount)
                    .setIndices(indices, indexCount);
        }
    }
}
