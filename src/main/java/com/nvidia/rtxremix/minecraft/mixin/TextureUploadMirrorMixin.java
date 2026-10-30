/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies a glyph upload into its page mirror.
 *
 * <p>Every texture write ends in this buffer overload: an image write passes its
 * whole tightly packed pixel buffer here. Vanilla assembles glyph pages on the GPU
 * and never keeps them on the CPU, so this is the one place their pixels can be
 * read. Writes outside a glyph upload scope ({@link GlyphAtlasTextureMixin}) are
 * ignored.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class TextureUploadMirrorMixin {

    @Inject(
            method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorGlyphUpload(GpuTexture destination, ByteBuffer source, int mipLevel,
                                            int depthOrLayer, int destX, int destY, int width, int height,
                                            CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        int channels = destination.getFormat().componentCount();
        page.blit(destX, destY, width, height, channels, source, width * channels);
    }

    /**
     * Keeps the pixels of a whole-image RGBA upload, for textures that upload
     * themselves instead of through {@code DynamicTexture}, such as a skin mod's
     * downloaded skins. Without them those entities have no Remix material.
     */
    @Inject(
            method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorImageUpload(GpuTexture destination, NativeImage source, int mipLevel,
                                            int depthOrLayer, int destX, int destY, CallbackInfo ci) {
        if (mipLevel != 0 || destX != 0 || destY != 0 || source.isClosed()
                || source.format() != NativeImage.Format.RGBA
                || source.getWidth() != destination.getWidth(0)
                || source.getHeight() != destination.getHeight(0)) {
            return;
        }
        DynamicTextureCache.onImageUpload(destination, source);
    }
}
