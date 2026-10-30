/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.sky.StarFieldCapture;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reroutes vanilla's world render into the Remix capture while a Remix world is
 * active.
 *
 * <ul>
 *   <li>Vanilla's particle submission is skipped, because Remix owns that
 *       geometry. Terrain and cloud draws are cancelled where they are drawn
 *       ({@link ChunkSectionsToRenderMixin}, {@link VanillaCloudRendererMixin}).</li>
 *   <li>Section rebuild scheduling is exposed to the terrain pipeline.</li>
 *   <li>The star field captured from the sky renderer is handed to the Remix sky.</li>
 * </ul>
 *
 * <p>Entities, block entities and the crack are captured at extraction
 * ({@link LevelExtractorMixin}), the last point that sees the game objects.
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin
        implements WorldRendererProfilingAccess, WorldRendererStarAccess, WorldRendererTerrainAccess {

    @Shadow
    private @Nullable ViewArea viewArea;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Override
    public float[] rtxremix$copyStarVertices() {
        return StarFieldCapture.copyVertices();
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
    }

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int cx, int cz) {
        // The chunk-load re-flag filter that tracked this is parked for 26.3.
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        if (viewArea == null) {
            return built;
        }
        for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                built.add(section.getSectionNode());
            }
        }
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        ClientLevel level = Minecraft.getInstance().level;
        if (viewArea == null || sectionRenderDispatcher == null || level == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }

        // Same gate as vanilla's compile scheduling: a section built before its
        // light is ready would bake bad light.
        LevelLightEngine lightingProvider = level.getLightEngine();

        ArrayList<SectionRenderDispatcher.RenderSection> loadedSections = new ArrayList<>();
        for (SectionRenderDispatcher.RenderSection section : viewArea.sections) {
            long node = section.getSectionNode();
            if (!sections.contains(node)) {
                continue;
            }
            int cx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int cz = SectionPos.z(node);
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk != null && !chunk.getSection(chunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()
                    && lightingProvider.lightOnInColumn(SectionPos.getZeroNode(cx, cz))) {
                loadedSections.add(section);
            }
        }

        // The compile queue is FIFO. Queue nearby sections first so visible
        // holes close promptly while the Remix scene repopulates.
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        loadedSections.sort(Comparator.comparingDouble(section -> {
            SectionPos pos = SectionPos.of(section.getSectionNode());
            double dx = pos.minBlockX() + 8.0 - camera.x;
            double dy = pos.minBlockY() + 8.0 - camera.y;
            double dz = pos.minBlockZ() + 8.0 - camera.z;
            return dx * dx + dy * dy + dz * dz;
        }));

        RenderRegionCache regionCache = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(loadedSections.size());
        for (SectionRenderDispatcher.RenderSection section : loadedSections) {
            section.compileAsync(regionCache.createRegion(level, section.getSectionNode()));
            queued.add(section.getSectionNode());
        }
        return queued;
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        // The update tracker ignores sections outside the view area: slot
        // repositioning already forces a rebuild when one re-enters.
        Minecraft.getInstance().levelExtractor.setSectionDirty(cx, sy, cz);
    }

    @Redirect(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ))
    private void rtxremix$skipVanillaParticleDraw(
            ParticlesRenderState particles, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            particles.submit(collector, camera);
        }
    }
}
