/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * A submit node collector that draws each submission straight into a Remix capture.
 *
 * <p>Renderers no longer write vertices when asked to render: they submit models,
 * quads and custom geometry for a feature pass to draw later, by which time the
 * owner a capture keys its instances on is gone. Handing a renderer this collector
 * instead runs each submission at once, exactly as the vanilla feature renderers
 * would, into the {@link RemixBufferSource} it is bound to. Everything else in the
 * capture (bones, cuboids, baked quads) still arrives through the consumers.
 *
 * <p>Shadows, outlines, crumbling, gizmos and particles are dropped: Remix traces
 * real shadows and draws particles and the crack from its own passes. Text and the
 * fire overlay are not captured yet.
 *
 * <p>Render thread only; one instance is rebound per capture.
 */
public final class RemixSubmitCollector implements SubmitNodeCollector {
    private static final Direction[] QUAD_FACES = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final int LEASH_STEPS = 24;
    private static final float LEASH_WIDTH = 0.05f;

    private final QuadInstance quadInstance = new QuadInstance();
    private RemixBufferSource target;

    /** The capture submissions currently draw into. */
    public RemixBufferSource target() {
        return target;
    }

    /** Binds the capture the next submissions draw into. */
    public RemixSubmitCollector bind(RemixBufferSource target) {
        this.target = target;
        return this;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor,
                                @Nullable UvMapping uvMapping, int outlineColor) {
        VertexConsumer buffer = target.getBuffer(renderType);
        if (uvMapping != null) {
            buffer = uvMapping.wrap(buffer);
        }
        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        VertexConsumer buffer = target.getBuffer(renderType);
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : parts) {
            for (Direction face : QUAD_FACES) {
                for (BakedQuad quad : part.getQuads(face)) {
                    quadInstance.setColor(layerColor(tintLayers, quad.materialInfo()));
                    buffer.putBakedQuad(pose, quad, quadInstance);
                }
            }
        }
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                           ItemStackRenderState.FoilType foilType) {
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads.all()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            quadInstance.setColor(layerColor(tintLayers, material));
            target.getBuffer(material.itemRenderType()).putBakedQuad(pose, quad, quadInstance);
        }
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        customGeometryRenderer.render(poseStack.last(), target.getBuffer(renderType));
    }

    /** The same rope vanilla's leash feature draws: a sagging strip, flat in the wide direction. */
    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
        VertexConsumer buffer = target.getBuffer(RenderTypes.leash());
        Matrix4f pose = new Matrix4f(poseStack.last().pose());
        float dx = (float) (leash.end.x - leash.start.x);
        float dy = (float) (leash.end.y - leash.start.y);
        float dz = (float) (leash.end.z - leash.start.z);
        float offset = Mth.invSqrt(dx * dx + dz * dz) * LEASH_WIDTH / 2.0f;
        float offsetX = dz * offset, offsetZ = dx * offset;
        pose.translate((float) leash.offset.x, (float) leash.offset.y, (float) leash.offset.z);
        for (int step = 0; step <= LEASH_STEPS; step++) {
            addLeashPair(buffer, pose, dx, dy, dz, LEASH_WIDTH, offsetX, offsetZ, step, false, leash);
        }
        for (int step = LEASH_STEPS; step >= 0; step--) {
            addLeashPair(buffer, pose, dx, dy, dz, 0.0f, offsetX, offsetZ, step, true, leash);
        }
    }

    private static void addLeashPair(VertexConsumer buffer, Matrix4f pose, float dx, float dy, float dz, float inset,
                                     float offsetX, float offsetZ, int step, boolean backwards,
                                     EntityRenderState.LeashState leash) {
        float progress = step / (float) LEASH_STEPS;
        int light = LightCoordsUtil.pack(
                (int) Mth.lerp(progress, leash.startBlockLight, leash.endBlockLight),
                (int) Mth.lerp(progress, leash.startSkyLight, leash.endSkyLight));
        float shade = step % 2 == (backwards ? 1 : 0) ? 0.7f : 1.0f;
        float red = 0.5f * shade, green = 0.4f * shade, blue = 0.3f * shade;
        float x = dx * progress, z = dz * progress;
        float y = !leash.slack ? dy * progress
                : dy > 0.0f ? dy * progress * progress
                : dy - dy * (1.0f - progress) * (1.0f - progress);
        buffer.addVertex(pose, x - offsetX, y + inset, z + offsetZ).setColor(red, green, blue, 1.0f).setLight(light);
        buffer.addVertex(pose, x + offsetX, y + LEASH_WIDTH - inset, z - offsetZ)
                .setColor(red, green, blue, 1.0f).setLight(light);
    }

    private static int layerColor(int[] tintLayers, BakedQuad.MaterialInfo material) {
        int tintIndex = material.tintIndex();
        return tintIndex >= 0 && tintIndex < tintLayers.length ? tintLayers[tintIndex] : -1;
    }

    // ---- Not captured ----

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                           int outlineColor) {
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                     Font.DisplayMode displayMode, int lightCoords) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                           int lightCoords, int overlayCoords, int tintedColor,
                                           ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                         boolean isBlockTranslucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }
}
