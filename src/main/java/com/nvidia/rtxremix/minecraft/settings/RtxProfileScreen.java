/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.RemixProfile;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** The in-game preset changer: one button per {@link RemixProfile}, the active one marked. */
public final class RtxProfileScreen extends Screen {

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);

    public RtxProfileScreen(@Nullable Screen parent) {
        super(Component.translatable("rtxremix.profile.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical());
        header.addChild(new StringWidget(this.title, this.font), LayoutSettings::alignHorizontallyCenter);

        LinearLayout profiles = LinearLayout.vertical().spacing(8);
        RemixProfile active = RtxRemixMod.activeProfile();
        for (RemixProfile profile : RemixProfile.values()) {
            Component label = Component.translatable("rtxremix.profile." + profile.id());
            profiles.addChild(Button.builder(
                            profile == active
                                    ? Component.translatable("rtxremix.profile.active", label)
                                    : label,
                            button -> choose(profile))
                    .tooltip(Tooltip.create(Component.translatable("rtxremix.profile." + profile.id() + ".tip")))
                    .width(260)
                    .build());
        }
        this.layout.addToContents(profiles);

        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    private void choose(RemixProfile profile) {
        onClose();
        Component result = RtxRemixMod.applyProfile(profile.id());
        Player player = this.minecraft.player;
        if (player != null) {
            player.sendOverlayMessage(result);
        }
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
