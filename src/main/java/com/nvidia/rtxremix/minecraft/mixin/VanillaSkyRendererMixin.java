/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.nvidia.rtxremix.minecraft.sky.StarFieldCapture;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Copies the star field vanilla uploads once, for the Remix sky to draw. */
@Mixin(SkyRenderer.class)
public abstract class VanillaSkyRendererMixin {

    @WrapOperation(
            method = "buildStars",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
            ))
    private GpuBuffer rtxremix$captureStars(GpuDevice device, Supplier<String> label, int usage, ByteBuffer data,
                                            Operation<GpuBuffer> original) {
        StarFieldCapture.capture(data);
        return original.call(device, label, usage, data);
    }
}
