/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;
import static org.lwjgl.opengl.GL45.glCreateFramebuffers;
import static org.lwjgl.opengl.GL45.glGetTextureLevelParameteri;
import static org.lwjgl.opengl.GL45.glNamedFramebufferTexture;

/**
 * Copies the Remix image over Minecraft's main render target, replacing what
 * vanilla drew for the world. The copy is opaque and touches neither depth nor
 * blending: the Remix image is the scene, not a layer on it.
 *
 * <p>Render thread only. The renderer runs on the OpenGL backend
 * ({@code PreferredGraphicsApiMixin}), so the target's texture is a
 * {@link GlTexture}, and the GL backend executes commands as they are issued, so
 * this copy lands between the world and the GUI that follows.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    private final Minecraft client;
    private int sourceFramebuffer;
    private int targetFramebuffer;

    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Copies a texture over the whole main render target.
     *
     * @param textureId The GL texture ID to copy
     */
    public void render(int textureId) {
        if (textureId <= 0) {
            return;
        }

        if (client == null || client.getWindow() == null) {
            return;
        }

        try {
            copyOverMainTarget(textureId);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void copyOverMainTarget(int texId) {
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTexture color = mainTarget == null ? null : mainTarget.getColorTexture();
        if (!(color instanceof GlTexture glColor)) {
            return;
        }

        if (sourceFramebuffer == 0) {
            sourceFramebuffer = glCreateFramebuffers();
            targetFramebuffer = glCreateFramebuffers();
        }
        glNamedFramebufferTexture(sourceFramebuffer, GL_COLOR_ATTACHMENT0, texId, 0);
        glNamedFramebufferTexture(targetFramebuffer, GL_COLOR_ATTACHMENT0, glColor.glId(), 0);

        int sourceWidth = glGetTextureLevelParameteri(texId, 0, GL_TEXTURE_WIDTH);
        int sourceHeight = glGetTextureLevelParameteri(texId, 0, GL_TEXTURE_HEIGHT);
        // The interop image's first row is the top of the screen, while a GL
        // framebuffer's first row is its bottom, so the copy flips vertically.
        glBlitNamedFramebuffer(
                sourceFramebuffer, targetFramebuffer,
                0, 0, sourceWidth, sourceHeight,
                0, mainTarget.height, mainTarget.width, 0,
                GL_COLOR_BUFFER_BIT, GL_LINEAR);
    }

    /** Releases the copy's framebuffers. Render thread only. */
    public void shutdown() {
        if (sourceFramebuffer != 0) {
            glDeleteFramebuffers(sourceFramebuffer);
            glDeleteFramebuffers(targetFramebuffer);
            sourceFramebuffer = 0;
            targetFramebuffer = 0;
        }
    }
}
