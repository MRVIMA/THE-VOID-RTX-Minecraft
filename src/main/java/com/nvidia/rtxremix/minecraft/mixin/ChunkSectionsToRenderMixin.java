/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips vanilla's terrain draws while Remix renders the world, because Remix owns
 * that geometry. Only the draws are skipped: the sections still compile, and that
 * compile is where the terrain capture runs.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrain(CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTranslucentTerrain(CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            ci.cancel();
        }
    }
}
