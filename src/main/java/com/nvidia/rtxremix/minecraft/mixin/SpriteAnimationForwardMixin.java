/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.SpriteAtlasPosition;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards an animated sprite's new frame to Remix whenever its animation advances.
 *
 * <p>The atlas animates on the GPU, copying a frame texture into the atlas, so no
 * frame pixels pass the CPU any more. The frame sheet the frames were cut from is
 * still on the CPU, so the patch is read from there, at the frame's place in the
 * sheet. Interpolated animations are forwarded at their key frames only; the
 * blend between two frames happens on the GPU.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimationState")
public abstract class SpriteAnimationForwardMixin implements SpriteAtlasPosition {

    @Shadow
    private int frame;

    @Shadow
    private boolean isDirty;

    @Shadow
    @Final
    private SpriteContents.AnimatedTexture animationInfo;

    @Unique
    private SpriteContents rtxremix$contents;

    @Unique
    private int rtxremix$atlasX;

    @Unique
    private int rtxremix$atlasY;

    @Override
    public void rtxremix$setAtlasPosition(SpriteContents contents, int x, int y) {
        rtxremix$contents = contents;
        rtxremix$atlasX = x;
        rtxremix$atlasY = y;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$forwardFrame(CallbackInfo ci) {
        SpriteContents contents = rtxremix$contents;
        if (!isDirty || contents == null) {
            return;
        }
        NativeImage sheet = ((SpriteContentsAccessor) contents).getImage();
        if (sheet == null || sheet.isClosed()) {
            return;
        }
        int index = ((SpriteContents.FrameInfo) animationInfo.frames.get(frame)).index;
        int width = contents.width();
        int height = contents.height();
        int srcX = (index % animationInfo.frameRowSize) * width;
        int srcY = (index / animationInfo.frameRowSize) * height;
        int sheetWidth = sheet.getWidth();
        long pixelPtr = ((NativeImageAccessor) (Object) sheet).getPointer()
                + ((long) srcY * sheetWidth + srcX) * 4;
        AtlasAnimationForwarder.addPatch(contents.name(), rtxremix$atlasX, rtxremix$atlasY, srcX, srcY,
                width, height, sheetWidth, sheet.getHeight(), pixelPtr, sheetWidth * 4);
    }
}
