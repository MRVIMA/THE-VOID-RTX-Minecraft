/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.SpriteContents;

/**
 * Where an animated sprite's frames land in its atlas. A sprite's animation state
 * draws into the atlas on the GPU and does not know its own place in it, so the
 * sprite that creates the state records it ({@code SpriteAnimationPositionMixin}).
 * The state keeps no reference to its sprite contents either, so those come too.
 */
public interface SpriteAtlasPosition {
    void rtxremix$setAtlasPosition(SpriteContents contents, int x, int y);
}
