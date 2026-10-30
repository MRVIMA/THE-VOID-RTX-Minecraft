/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Where captured geometry for one render type goes. Vanilla's own buffer source
 * left the renderers' API when rendering moved to submit nodes; the capture keeps
 * the shape so {@link RemixSubmitCollector} can feed it.
 */
public interface RemixBufferSource {
    VertexConsumer getBuffer(RenderType renderType);
}
