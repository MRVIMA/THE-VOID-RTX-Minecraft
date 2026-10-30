/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Renders the independently rotating yoke and lamp head above the static mount. */
public final class CopperBeaconBlockEntityRenderer
        implements BlockEntityRenderer<CopperBeaconBlockEntity, CopperBeaconBlockEntityRenderer.State> {

    public static final ModelLayerLocation BODY_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "body");
    public static final ModelLayerLocation LENS_MODEL_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "lens");
    private static final Identifier COPPER_SPRITE_ID = Identifier.withDefaultNamespace("block/copper_block");
    static final Identifier LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens");
    static final Identifier UNLIT_LENS_SPRITE_ID =
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens_unlit");
    private static final RenderType BLOCK_ATLAS_SOLID = RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS);

    private final ArticulatedModel body;
    private final ArticulatedModel lensModel;

    /** What one beacon needs at submit time; models are drawn after extraction ends. */
    public static final class State extends BlockEntityRenderState {
        final CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
        boolean lit;
        int lensTint = -1;
    }

    /**
     * A beacon part tree posed by its render state. Vanilla draws submitted models
     * after every block entity has been submitted, so the articulation is applied
     * in {@code setupAnim}, at draw time, rather than to the shared parts when the
     * beacon is submitted.
     */
    private static final class ArticulatedModel extends Model<State> {
        private final @Nullable ModelPart yoke;
        private final @Nullable ModelPart head;
        private final @Nullable ModelPart lens;

        ArticulatedModel(ModelPart root, @Nullable ModelPart yoke, @Nullable ModelPart head,
                         @Nullable ModelPart lens) {
            super(root, RenderTypes::entitySolid);
            this.yoke = yoke;
            this.head = head;
            this.lens = lens;
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            applyArticulation(yoke, head, lens, state.pose);
        }
    }

    public CopperBeaconBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart bodyRoot = context.bakeLayer(BODY_MODEL_LAYER);
        ModelPart yoke = bodyRoot.getChild("yoke");
        body = new ArticulatedModel(bodyRoot, yoke, yoke.getChild("head"), null);
        ModelPart lensRoot = context.bakeLayer(LENS_MODEL_LAYER);
        lensModel = new ArticulatedModel(lensRoot, null, null, lensRoot.getChild("lens"));
    }

    public static LayerDefinition getBodyTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        PartDefinition yokeData = root.addOrReplaceChild(
                "yoke",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(0.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-5.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f)
                        .texOffs(0, 0).addBox(3.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        yokeData.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-6.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(4.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f),
                PartPose.ZERO);
        return LayerDefinition.create(modelData, 16, 16);
    }

    public static LayerDefinition getLensTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        root.addOrReplaceChild(
                "lens",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -3.0f, -4.01f, 6.0f, 6.0f, 0.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        return LayerDefinition.create(modelData, 6, 6);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(
            CopperBeaconBlockEntity beacon,
            State state,
            float tickDelta,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(beacon, state, tickDelta, cameraPosition, breakProgress);
        BlockState blockState = beacon.getBlockState();
        double animationTick = CopperBeaconAnimationClock.sample(beacon.getLevel(), tickDelta);
        CopperBeaconPose.evaluate(
                blockState.getValue(CopperBeaconBlock.MOUNT),
                blockState.getValue(BlockStateProperties.FACING),
                beacon.yawAngleAt(animationTick),
                beacon.pitchAngleAt(animationTick),
                state.pose);
        state.lit = beacon.isActive() && beacon.getIntensityMultiplier() > 0.0f;
        state.lensTint = beacon.getLensTint();
    }

    @Override
    public void submit(State state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        matrices.rotate(state.pose.mountingRotation());
        matrices.translate(-0.5, -0.5, -0.5);

        collector.submitModel(body, state, matrices, BLOCK_ATLAS_SOLID, state.lightCoords,
                OverlayTexture.NO_OVERLAY, -1, copperStrip(blockSprite(COPPER_SPRITE_ID)), 0);
        collector.submitModel(lensModel, state, matrices, BLOCK_ATLAS_SOLID, state.lightCoords,
                OverlayTexture.NO_OVERLAY, state.lensTint, blockSprite(lensTexture(state.lit)), 0);
        matrices.popPose();
    }

    private static TextureAtlasSprite blockSprite(Identifier id) {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(id);
    }

    static Quaternionf mountingRotation(Direction upDirection, Direction forwardDirection) {
        return CopperBeaconPose.mountingRotation(
                upDirection, forwardDirection, new Quaternionf());
    }

    static void applyArticulation(
            @Nullable ModelPart yoke,
            @Nullable ModelPart head,
            @Nullable ModelPart lens,
            CopperBeaconPose.Pose pose) {
        if (yoke != null) {
            yoke.yRot = pose.modelYawRadians();
            yoke.xRot = 0.0f;
        }
        if (head != null) {
            head.yRot = 0.0f;
            head.xRot = pose.modelPitchRadians();
        }
        if (lens != null) {
            lens.yRot = pose.modelYawRadians();
            lens.xRot = pose.modelPitchRadians();
        }
    }

    static Identifier lensTexture(boolean lit) {
        return lit ? LENS_SPRITE_ID : UNLIT_LENS_SPRITE_ID;
    }

    static Identifier lensTexture(boolean active, float intensityMultiplier) {
        return lensTexture(active && intensityMultiplier > 0.0f);
    }

    static float copperTextureV(float v) {
        return 0.25f + v * 0.5f;
    }

    /**
     * Keeps resource-pack details along the texture edges while excluding all
     * four corners, where stylized copper textures commonly place rivets.
     */
    private static UvMapping copperStrip(TextureAtlasSprite sprite) {
        return new UvMapping() {
            @Override
            public float getU(float offset) {
                return sprite.getU(offset);
            }

            @Override
            public float getV(float offset) {
                return sprite.getV(copperTextureV(offset));
            }
        };
    }
}
