/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips vanilla's cloud draws while the Remix cloud renderer replaces them. */
@Mixin(CloudRenderer.class)
public abstract class VanillaCloudRendererMixin {

    private static boolean rtxremix$suppressed() {
        return RtxRemixMod.isRemixWorldRendering()
                && RtxRemixMod.getInstance().getRenderer().getCloudRenderer().shouldSuppressVanillaClouds();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaClouds(CallbackInfo ci) {
        if (rtxremix$suppressed()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaOitClouds(CallbackInfo ci) {
        if (rtxremix$suppressed()) {
            ci.cancel();
        }
    }
}
