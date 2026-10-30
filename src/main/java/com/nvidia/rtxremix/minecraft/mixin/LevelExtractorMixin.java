/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixNameTags;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures entities, block entities and the block-breaking crack where vanilla
 * extracts them, instead of letting vanilla submit them a second time.
 *
 * <p>Extraction is the last point that sees the game object together with its
 * render state; submission sees only the state. A capture keys its retained
 * instances on the object, so it happens here: the state vanilla just extracted is
 * handed to the Remix capture, and left out of the level's list when Remix took it.
 * When Remix declines, vanilla keeps the state and renders as usual.
 *
 * <p>Also widens block-update rebuilds by the hidden water-top cull radius.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    private static final Direction[] rtxremix$QUAD_FACES = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final RemixSubmitCollector rtxremix$collector = new RemixSubmitCollector();
    private static final List<BlockStateModelPart> rtxremix$parts = new ArrayList<>();
    private static final SingleThreadedRandomSource rtxremix$random = new SingleThreadedRandomSource(0L);
    private static final QuadInstance rtxremix$quadInstance = new QuadInstance();

    @Shadow
    public abstract void setSectionDirty(int sectionX, int sectionY, int sectionZ);

    private static RemixRenderer rtxremix$renderer() {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        return mod == null ? null : mod.getRenderer();
    }

    private static CameraRenderState rtxremix$camera() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void rtxremix$beginWorldFrame(CallbackInfo ci) {
        RtxRemixMod.onWorldExtractStart();
        RemixNameTags.beginFrame();
    }

    /**
     * Entities: the frustum-culled traversal. Entities the camera cannot see are
     * picked up afterwards by the Remix pass, which needs them for shadows and
     * reflections.
     */
    @Redirect(
            method = "extractVisibleEntities",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private boolean rtxremix$captureEntityInPlace(List<EntityRenderState> states, Object value,
            @Local Entity entity, @Local(ordinal = 0) float partialTicks) {
        EntityRenderState state = (EntityRenderState) value;
        RemixNameTags.collect(state);
        RemixRenderer renderer = rtxremix$renderer();
        if (renderer == null || !renderer.renderEntityInPlace(entity, state, partialTicks)) {
            return states.add(state);
        }
        if (!state.appearsGlowing()) {
            return true;
        }
        // The outline post-pass reads a framebuffer only vanilla's draw feeds, so a
        // glowing entity still goes to vanilla. Its blob shadow must not: the GL frame
        // is composited over the traced scene, and Remix already traces the real shadow.
        state.shadowPieces.clear();
        return states.add(state);
    }

    /** Block entities: vanilla's per-section walk and the globally rendered ones. */
    @Redirect(
            method = "extractVisibleBlockEntities",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private boolean rtxremix$captureBlockEntityInPlace(List<BlockEntityRenderState> states, Object value,
            @Local BlockEntity blockEntity) {
        BlockEntityRenderState state = (BlockEntityRenderState) value;
        RemixRenderer renderer = rtxremix$renderer();
        Minecraft client = Minecraft.getInstance();
        boolean captured = renderer != null
                && blockEntity.hasLevel()
                && renderer.renderBlockEntityInPlace(blockEntity,
                        (captureMatrices, captureProvider) -> client.getBlockEntityRenderDispatcher()
                                .submit(state, captureMatrices, rtxremix$collector.bind(captureProvider),
                                        rtxremix$camera()));
        return captured || states.add(state);
    }

    /** The block-breaking crack, captured block-local on the block's own model. */
    @Redirect(
            method = "extractBlockDestroyAnimation",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private boolean rtxremix$captureBlockDamageInPlace(List<BlockBreakingRenderState> states, Object value) {
        BlockBreakingRenderState state = (BlockBreakingRenderState) value;
        RemixRenderer renderer = rtxremix$renderer();
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(state.blockPos(), state.progress(),
                        (matrices, consumer) -> rtxremix$drawBlockModel(
                                state.blockState(), state.blockPos(), matrices, consumer));
        return captured || states.add(state);
    }

    private static void rtxremix$drawBlockModel(BlockState blockState, BlockPos pos, PoseStack matrices,
                                                VertexConsumer consumer) {
        rtxremix$random.setSeed(blockState.getSeed(pos));
        rtxremix$parts.clear();
        Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState)
                .collectParts(rtxremix$random, rtxremix$parts);
        PoseStack.Pose pose = matrices.last();
        rtxremix$quadInstance.setColor(-1);
        for (BlockStateModelPart part : rtxremix$parts) {
            for (Direction face : rtxremix$QUAD_FACES) {
                for (BakedQuad quad : part.getQuads(face)) {
                    consumer.putBakedQuad(pose, quad, rtxremix$quadInstance);
                }
            }
        }
        rtxremix$parts.clear();
    }

    /**
     * Hidden water-top culling reads farther than vanilla's one-block rebuild
     * margin. Expand horizontal invalidation so a ceiling or fluid update
     * cannot leave a neighboring section's occlusion decision stale.
     */
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }

        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    setSectionDirty(sectionX, sectionY, sectionZ);
                }
            }
        }
        ci.cancel();
    }
}
