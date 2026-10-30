/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixItemSource;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the terrain layer of the block item being drawn, and who holds it,
 * for the capture.
 *
 * <p>Minecraft routes ordinary opaque items through its translucent item layer,
 * and clear glass through its cutout one, so the render layer alone cannot tell
 * a pane of stained glass from an apple, or clear glass from a torch. The
 * {@code ItemStack} can; the state carries it from extraction
 * ({@link ItemModelResolverMixin}) to here, where the geometry is submitted.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemRendererMixin implements RemixItemSource {

    @Unique
    private ItemStack rtxremix$stack = ItemStack.EMPTY;

    @Unique
    private @Nullable LivingEntity rtxremix$holder;

    @Override
    public void rtxremix$setItemSource(ItemStack stack, @Nullable LivingEntity holder) {
        rtxremix$stack = stack;
        rtxremix$holder = holder;
    }

    @Override
    public ItemStack rtxremix$getItemStack() {
        return rtxremix$stack;
    }

    @Override
    public @Nullable LivingEntity rtxremix$getItemHolder() {
        return rtxremix$holder;
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$beginItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                    int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(rtxremix$blockLayer(rtxremix$stack));
            if (rtxremix$holder != null) context.setHeldItemHolder(rtxremix$holder);
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtxremix$endItem(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                  int overlayCoords, int outlineColor, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
            context.clearHeldItemHolder();
        }
    }

    /**
     * Asks the question terrain asks of a placed block, through the same
     * classifier, so a held block is classified exactly as its placed
     * counterpart is.
     */
    private static int rtxremix$blockLayer(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}
