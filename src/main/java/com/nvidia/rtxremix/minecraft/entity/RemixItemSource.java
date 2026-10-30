/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The stack an item render state was extracted from, and the living entity
 * holding it. Item rendering no longer sees the stack when it draws, only the
 * state extracted from it, so the extraction records both on the state.
 */
public interface RemixItemSource {
    void rtxremix$setItemSource(ItemStack stack, @Nullable LivingEntity holder);

    ItemStack rtxremix$getItemStack();

    @Nullable LivingEntity rtxremix$getItemHolder();
}
