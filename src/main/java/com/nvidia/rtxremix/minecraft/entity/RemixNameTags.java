/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.util.CameraFov;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws name tags in the HUD while Remix renders the world.
 *
 * <p>Vanilla's name tags are drawn onto the world image the Remix copy replaces, and
 * Remix has no text, so each frame this projects every extracted name onto the screen
 * and draws it with the font at the size vanilla's would appear. It keeps vanilla's
 * rules: a clear view shows the name in full, a wall leaves it faint, and a sneaking
 * entity's name is faint when seen and gone behind a wall.
 */
public final class RemixNameTags implements HudElement {
    /** World units per font unit, and the line step, as in vanilla's name tag. */
    private static final float WORLD_SCALE = 0.025f;
    private static final float LINE_HEIGHT = 9.0f * 1.15f;
    /** Vanilla lifts a name half a block above its attachment point. */
    private static final double ATTACHMENT_LIFT = 0.5;
    private static final float BACKGROUND_OPACITY = 0.25f;
    private static final int FULL_ALPHA = 0xFF;
    private static final int FAINT_ALPHA = 0x20;
    /** Nearer than this, a name would sit on the camera. */
    private static final float MIN_DEPTH = 0.1f;

    /** Filled at extraction, render thread only: captured entities skip the level's list. */
    private static final List<EntityRenderState> NAMED = new ArrayList<>();

    private final Vector3f view = new Vector3f();
    private final Quaternionf toView = new Quaternionf();

    /** Forgets the last frame's names. Called as a world extraction starts. */
    public static void beginFrame() {
        NAMED.clear();
    }

    /** Keeps an extracted entity if it has a name to show. */
    public static void collect(EntityRenderState state) {
        if (state.nameTagAttachment != null && (state.nameTag != null || state.scoreText != null)) {
            NAMED.add(state);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || NAMED.isEmpty()) {
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();

        // Farthest first, so a nearer name lands on top.
        NAMED.sort(Comparator.comparingDouble((EntityRenderState s) -> -cameraPos.distanceToSqr(s.x, s.y, s.z)));

        camera.rotation().conjugate(toView);
        int width = graphics.guiWidth(), height = graphics.guiHeight();
        float pixelsPerUnit = (height / 2.0f) / (float) Math.tan(Math.toRadians(CameraFov.worldDegrees()) / 2.0);
        for (EntityRenderState state : NAMED) {
            draw(graphics, client, camera, state, width, height, pixelsPerUnit);
        }
    }

    private void draw(GuiGraphicsExtractor graphics, Minecraft client, Camera camera, EntityRenderState state,
                      int width, int height, float pixelsPerUnit) {
        Vec3 cameraPos = camera.position();
        Vec3 anchor = new Vec3(state.x + state.nameTagAttachment.x,
                state.y + state.nameTagAttachment.y + ATTACHMENT_LIFT,
                state.z + state.nameTagAttachment.z);
        view.set((float) (anchor.x - cameraPos.x), (float) (anchor.y - cameraPos.y), (float) (anchor.z - cameraPos.z));
        toView.transform(view);
        float depth = -view.z;
        if (depth < MIN_DEPTH) {
            return;
        }
        float scale = WORLD_SCALE * pixelsPerUnit / depth;

        boolean blocked = client.level.clip(new ClipContext(cameraPos, anchor, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, camera.entity())).getType() != HitResult.Type.MISS;
        if (blocked && state.isDiscrete) {
            return;
        }
        int alpha = blocked || state.isDiscrete ? FAINT_ALPHA : FULL_ALPHA;

        float screenX = width / 2.0f + view.x / depth * pixelsPerUnit;
        float screenY = height / 2.0f - view.y / depth * pixelsPerUnit;
        graphics.pose().pushMatrix();
        graphics.pose().translate(screenX, screenY);
        graphics.pose().scale(scale, scale);
        int background = client.options.getBackgroundColor(BACKGROUND_OPACITY);
        float line = 0;
        if (state.scoreText != null) {
            drawLine(graphics, client.font, state.scoreText, line, alpha, background);
            line -= LINE_HEIGHT;
        }
        if (state.nameTag != null) {
            drawLine(graphics, client.font, state.nameTag, line, alpha, background);
        }
        graphics.pose().popMatrix();
    }

    private static void drawLine(GuiGraphicsExtractor graphics, Font font, Component text, float y, int alpha, int background) {
        int top = Math.round(y);
        int left = -font.width(text) / 2;
        graphics.fill(left - 1, top - 1, -left + 1, top + 8, background);
        graphics.text(font, text, left, top, alpha << 24 | 0xFFFFFF, false);
    }
}
