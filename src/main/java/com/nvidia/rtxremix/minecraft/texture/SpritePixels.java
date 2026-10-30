/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.minecraft.mixin.TextureAtlasSpriteAccessor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Where a sprite's own pixels start in its atlas.
 *
 * <p>The atlas surrounds every sprite with padding against mipmap and anisotropic
 * bleeding. {@code getX()}/{@code getY()} are the padded cell's corner, while the
 * sprite's UVs start {@code padding} pixels further in, so pixels copied to the
 * corner would sit offset from every UV that samples them.
 */
public final class SpritePixels {
    private SpritePixels() {
    }

    public static int x(TextureAtlasSprite sprite) {
        return sprite.getX() + padding(sprite);
    }

    public static int y(TextureAtlasSprite sprite) {
        return sprite.getY() + padding(sprite);
    }

    /** The padding on each side of the sprite, in atlas pixels. */
    public static int padding(TextureAtlasSprite sprite) {
        return ((TextureAtlasSpriteAccessor) sprite).rtxremix$getPadding();
    }
}
