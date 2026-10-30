/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets each entity submit with a capture boundary on the thread-local
 * {@link EntityRenderContext}.
 *
 * <p>{@code EntityRenderDispatcher.submit()} opens the boundary at HEAD with the
 * remix-pass flag and closes it at RETURN. Nesting is the context's concern: an
 * entity submitted from inside another submit appends to the enclosing capture
 * instead of starting its own.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void rtxremix$onRenderStart(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityRenderContext.get().begin(submitNodeCollector instanceof RemixSubmitCollector);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends EntityRenderState> void rtxremix$onRenderEnd(
            S renderState, CameraRenderState camera, double x, double y, double z,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityRenderContext.get().end();
    }
}
