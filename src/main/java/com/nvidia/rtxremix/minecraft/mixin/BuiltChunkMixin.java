/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into vanilla's render section lifecycle.
 *
 * <ul>
 *   <li><b>setSectionNode / reset:</b> clear the per-slot block-entity flag when
 *       vanilla reuses a section slot or drops its mesh.
 *   <li><b>setSectionMesh:</b> tracks whether the section contains block
 *       entities and flags empty render data as a removal candidate. The
 *       render thread validates the live world section before removing
 *       Remix terrain because vanilla can publish temporary empty data.
 * </ul>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class BuiltChunkMixin implements ChunkBuilderBlockEntityAccess {

    @Shadow
    public abstract long getSectionNode();

    private boolean rtxremix$hasBlockEntities;

    @Override
    public boolean rtxremix$hasBlockEntities() {
        return rtxremix$hasBlockEntities;
    }

    @Inject(method = "setSectionNode", at = @At("HEAD"))
    private void rtxremix$onSetSectionNode(long sectionNode, CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
    }

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void rtxremix$onSectionMeshSet(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
        rtxremix$hasBlockEntities = !sectionMesh.getRenderableBlockEntities().isEmpty();

        if (sectionMesh == CompiledSectionMesh.UNCOMPILED || sectionMesh.hasRenderableLayers()) {
            return;
        }
        long node = getSectionNode();
        SectionLeftRenderSetQueue.enqueueEmptyCandidate(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
    }

    @Inject(method = "reset()V", at = @At("HEAD"), require = 0)
    private void rtxremix$onClear(CallbackInfo ci) {
        rtxremix$hasBlockEntities = false;
    }
}
