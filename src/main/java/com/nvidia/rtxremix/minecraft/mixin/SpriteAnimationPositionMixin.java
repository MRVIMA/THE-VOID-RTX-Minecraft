/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.nvidia.rtxremix.minecraft.texture.SpriteAtlasPosition;
import com.nvidia.rtxremix.minecraft.texture.SpritePixels;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands a sprite's animation state the sprite's place in its atlas. */
@Mixin(TextureAtlasSprite.class)
public abstract class SpriteAnimationPositionMixin {

    @Shadow
    public abstract SpriteContents contents();

    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void rtxremix$recordAtlasPosition(GpuBufferSlice uboSlice, int spriteUboSize,
                                              CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
        SpriteContents.AnimationState state = cir.getReturnValue();
        if (state != null) {
            ((SpriteAtlasPosition) (Object) state).rtxremix$setAtlasPosition(contents(),
                    SpritePixels.x((TextureAtlasSprite) (Object) this), SpritePixels.y((TextureAtlasSprite) (Object) this));
        }
    }
}
