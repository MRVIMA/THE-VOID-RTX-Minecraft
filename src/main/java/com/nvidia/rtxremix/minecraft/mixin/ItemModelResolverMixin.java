/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixItemSource;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records on each item render state the stack it was extracted from and its
 * living holder. Every hand-held item, first person and every entity's hands
 * alike, is extracted through {@code updateForTopItem}, so the holder recorded
 * here is what lets the held-flame hook decide whether a held torch is burning.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    @Inject(method = "updateForTopItem", at = @At("HEAD"))
    private void rtxremix$recordItemSource(ItemStackRenderState output, ItemStack item,
            ItemDisplayContext displayContext, @Nullable Level level, @Nullable ItemOwner owner, int seed,
            CallbackInfo ci) {
        ((RemixItemSource) output).rtxremix$setItemSource(
                item, owner instanceof LivingEntity living ? living : null);
    }
}
