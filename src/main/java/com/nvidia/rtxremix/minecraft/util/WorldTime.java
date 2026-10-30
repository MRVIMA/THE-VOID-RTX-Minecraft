/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;

/**
 * Time of day over the world clocks that replaced the per-level day time.
 *
 * <p>A dimension's day time is its default clock's tick count; a dimension
 * without one reads 0, as a fixed-time dimension did before. The celestial
 * fraction is the sun angle environment attribute as a fraction of a turn, the
 * value {@code getTimeOfDay} returned: 0 at noon, 0.5 at midnight.
 */
public final class WorldTime {
    private WorldTime() {}

    /** The dimension's day time in ticks, unwrapped. */
    public static long dayTime(Level level) {
        return level.getDefaultClockTime();
    }

    /**
     * Sets every dimension's default clock on the integrated server, which syncs
     * the clients. A remote server owns its clocks, so there this does nothing.
     */
    public static void setDayTime(Minecraft client, long ticks) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                level.dimensionType().defaultClock()
                        .ifPresent(clock -> server.clockManager().setTotalTicks(clock, ticks));
            }
        });
    }

    /** The sun's angle at the camera as a fraction of a turn, 0 at noon. */
    public static float celestialFraction(float partialTicks) {
        Minecraft client = Minecraft.getInstance();
        float degrees = client.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks);
        return degrees / 360.0f;
    }
}
