/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.entity.RemixItemSource;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the lit element of a hand-held item right after the item's quads, while
 * the pose stack still holds the item's display transform. Running here rather
 * than in a hand-specific renderer covers every hand-held render with one hook.
 * Only a Remix capture gets the element; vanilla draws its own particles.
 */
@Mixin(targets = "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState")
public abstract class ItemLayerRenderStateMixin {

    @Shadow
    private ItemTransform itemTransform;

    @Shadow
    @Final
    ItemStackRenderState this$0;

    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILnet/minecraft/client/resources/model/geometry/ItemQuads;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",
                    shift = At.Shift.AFTER))
    private void rtxremix$renderHeldFlame(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                          int overlayCoords, int outlineColor, CallbackInfo ci) {
        if (!(collector instanceof RemixSubmitCollector remixCollector)) {
            return;
        }
        HeldItemEffectsRenderer.render(
                ((RemixItemSource) this$0).rtxremix$getItemStack(),
                ((ItemStackRenderStateAccessor) this$0).rtxremix$getDisplayContext(),
                poseStack,
                remixCollector.target(),
                itemTransform.scale().x());
    }
}
