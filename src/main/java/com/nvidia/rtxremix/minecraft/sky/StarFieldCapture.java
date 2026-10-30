/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The star field vanilla's sky renderer builds once, as positions on the
 * 100-block sky sphere. Written on the render thread when the sky renderer is
 * created ({@code VanillaSkyRendererMixin}), read by the Remix sky.
 */
public final class StarFieldCapture {
    private static final int POSITION_STRIDE = 3 * Float.BYTES;
    private static float[] vertices = new float[0];

    private StarFieldCapture() {}

    /** Records the star vertex buffer, which is in the position-only vertex format. */
    public static void capture(ByteBuffer positionVertices) {
        ByteBuffer source = positionVertices.duplicate().order(ByteOrder.nativeOrder());
        int vertexCount = source.remaining() / POSITION_STRIDE;
        float[] positions = new float[vertexCount * 3];
        int base = source.position();
        for (int i = 0; i < positions.length; i++) {
            positions[i] = source.getFloat(base + i * Float.BYTES);
        }
        vertices = positions;
    }

    public static float[] copyVertices() {
        return vertices.clone();
    }
}
