/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link TextureManager#register} to track dynamic textures
 * for pixel capture across every upload.
 *
 * <p>Handles three texture types:
 * <ul>
 *   <li>{@link DynamicTexture}: if the image is already available at
 *       registration time, pixels are captured immediately; the texture remains
 *       tracked for later mutable uploads such as maps.</li>
 *   <li>{@link HttpTexture}: always async (image downloads from Mojang CDN),
 *       so {@code PlayerSkinTextureMixin} can capture the uploaded image.</li>
 *   <li>Font glyph pages, which {@code GlyphAtlasTextureMixin} gives a
 *       {@link GlyphPageMirror}: the mirror is registered under the page's id so the
 *       submitter reads it like any other dynamic texture. {@code FontStorage}
 *       registers a page before uploading its first glyph.</li>
 * </ul>
 */
@Mixin(TextureManager.class)
public class TextureManagerMixin {

    @Inject(method = "register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V", at = @At("HEAD"))
    private void rtxremix$onRegisterTexture(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        if (texture instanceof DynamicTexture nib) {
            DynamicTextureCache.onRegisterTexture(id, texture, nib.getPixels());
            GpuTexture gpuTexture = ((AbstractTextureAccessor) texture).rtxremix$getGpuTexture();
            if (gpuTexture != null) {
                DynamicTextureCache.forgetPendingUpload(gpuTexture);
            }
        } else if (texture instanceof GlyphPageMirror.Owner page) {
            DynamicTextureCache.onRegisterGlyphPage(id, page.rtxremix$glyphPageMirror());
        } else if (!(texture instanceof ReloadableTexture)) {
            // A texture that creates and uploads its own GPU texture, such as a skin
            // mod's downloaded skin: its pixels were captured at the encoder upload.
            GpuTexture gpuTexture = ((AbstractTextureAccessor) texture).rtxremix$getGpuTexture();
            if (gpuTexture != null) {
                DynamicTextureCache.onRegisterGpuTexture(id, texture, gpuTexture);
            }
        }
    }
}
