/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * The frame's vertical fields of view, read from the extracted camera state that
 * vanilla renders the frame with. The world projection carries dynamic FOV
 * (sprinting, effects, zoom); the hands use their own, fixed, hud FOV.
 */
public final class CameraFov {
    private CameraFov() {}

    private static CameraRenderState camera() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    }

    /** The world's vertical FOV in degrees: the projection's y scale is 1 / tan(fov / 2). */
    public static double worldDegrees() {
        return Math.toDegrees(2.0 * Math.atan(1.0 / camera().projectionMatrix.m11()));
    }

    /** The first-person hands' vertical FOV in degrees. */
    public static double handDegrees() {
        return camera().hudFov;
    }
}
