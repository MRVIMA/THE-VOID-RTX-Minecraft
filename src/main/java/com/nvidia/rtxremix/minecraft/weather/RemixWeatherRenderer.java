/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.nvidia.rtxremix.minecraft.util.CameraFov;
import java.nio.FloatBuffer;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

/**
 * Draws rain and snow straight onto the composited Remix image, testing each pixel
 * against the Remix depth image so path-traced geometry hides what is behind it.
 *
 * <p>Vanilla still decides where weather falls: this reads the columns it extracts
 * into {@link WeatherRenderState}. Its own draw lands on the world image the Remix
 * copy replaces, so nothing of it survives. The renderpearl pipelines cannot sample
 * an interop texture, so this draws in raw GL, on the OpenGL backend the mod forces.
 *
 * <p>Render thread only, after the composite and before the frame's interop release:
 * the depth image belongs to GL only between those two points. Every piece of GL state
 * this touches is put back, because the backend caches what it believes is bound.
 */
public final class RemixWeatherRenderer {
    private static final Identifier RAIN_TEXTURE = Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW_TEXTURE = Identifier.withDefaultNamespace("textures/environment/snow.png");
    /** Must match the Remix camera, or the depth comparison is wrong. */
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    /** Vanilla's per-column maximum opacity for rain and snow. */
    private static final float RAIN_MAX_ALPHA = 1.0f;
    private static final float SNOW_MAX_ALPHA = 0.8f;
    /** How much of the rain texture's blue is kept (0 = grey), and its overall opacity. */
    private static final float RAIN_COLOUR = 0.2f;
    private static final float RAIN_OPACITY = 0.55f;
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int VERTICES_PER_COLUMN = 6;

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 Uv;
            layout(location = 2) in float Alpha;
            layout(location = 3) in vec2 Light;
            uniform mat4 ViewProjection;
            out vec2 uv;
            out float alpha;
            out vec2 light;
            void main() {
                gl_Position = ViewProjection * vec4(Position, 1.0);
                uv = Uv;
                alpha = Alpha;
                light = Light;
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D Weather;
            uniform sampler2D Lightmap;
            uniform sampler2D RemixDepth;
            uniform vec2 FramebufferSize;
            uniform float ColorAmount;
            uniform float AlphaScale;
            in vec2 uv;
            in float alpha;
            in vec2 light;
            out vec4 fragColor;
            void main() {
                // The interop image's first row is the top of the screen.
                vec2 screen = vec2(gl_FragCoord.x / FramebufferSize.x, 1.0 - gl_FragCoord.y / FramebufferSize.y);
                if (gl_FragCoord.z > texture(RemixDepth, screen).r) discard;
                vec4 color = texture(Weather, uv);
                color.a *= alpha;
                if (color.a < 0.004) discard;
                // Vanilla paints rain saturated blue; real rain is pale and barely there.
                float grey = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
                vec3 tint = mix(vec3(grey), color.rgb, ColorAmount);
                fragColor = vec4(mix(tint, vec3(1.0), 0.35) * texture(Lightmap, light).rgb, color.a * AlphaScale);
            }
            """;

    private final float[] columnSizeX = new float[32 * 32];
    private final float[] columnSizeZ = new float[32 * 32];
    private FloatBuffer vertices = BufferUtils.createFloatBuffer(1024 * VERTICES_PER_COLUMN * FLOATS_PER_VERTEX);

    private int program;
    private int vertexArray;
    private int vertexBuffer;
    private int weatherSampler;
    private int clampedSampler;
    private int framebuffer;
    private int viewProjectionLocation;
    private int framebufferSizeLocation;
    private int colorAmountLocation;
    private int alphaScaleLocation;

    public RemixWeatherRenderer() {
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                float deltaX = x - 16, deltaZ = z - 16;
                float distance = Math.max(0.001f, (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
                columnSizeX[z * 32 + x] = -deltaZ / distance;
                columnSizeZ[z * 32 + x] = deltaX / distance;
            }
        }
    }

    /** Draws this frame's rain and snow over the main render target. */
    public void render(int depthTexture) {
        Minecraft client = Minecraft.getInstance();
        WeatherRenderState state = client.gameRenderer.gameRenderState().levelRenderState.weatherRenderState;
        int rainColumns = state.rainColumns.size(), snowColumns = state.snowColumns.size();
        if (rainColumns + snowColumns == 0 || depthTexture == 0) {
            return;
        }
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTexture color = target == null ? null : target.getColorTexture();
        int rain = textureId(client, RAIN_TEXTURE), snow = textureId(client, SNOW_TEXTURE);
        if (!(color instanceof GlTexture glColor)
                || !(client.gameRenderer.lightmap().texture() instanceof GlTexture lightmap)
                || rain == 0 || snow == 0) {
            return;
        }
        if (program == 0) {
            create();
        }

        Camera camera = client.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();
        vertices = ensureCapacity(vertices, (rainColumns + snowColumns) * VERTICES_PER_COLUMN * FLOATS_PER_VERTEX);
        vertices.clear();
        addColumns(state.rainColumns, cameraPos, RAIN_MAX_ALPHA, state.radius, state.intensity);
        addColumns(state.snowColumns, cameraPos, SNOW_MAX_ALPHA, state.radius, state.intensity);
        vertices.flip();

        try (GlStateSnapshot ignored = new GlStateSnapshot()) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, glColor.glId(), 0);
            GL11.glViewport(0, 0, target.width, target.height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);

            GL20.glUseProgram(program);
            GL20.glUniform2f(framebufferSizeLocation, target.width, target.height);
            float[] matrix = new float[16];
            viewProjection(camera, target).get(matrix);
            GL20.glUniformMatrix4fv(viewProjectionLocation, false, matrix);

            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);

            bind(1, lightmap.glId(), clampedSampler);
            bind(2, depthTexture, clampedSampler);
            if (rainColumns > 0) {
                bind(0, rain, weatherSampler);
                GL20.glUniform1f(colorAmountLocation, RAIN_COLOUR);
                GL20.glUniform1f(alphaScaleLocation, RAIN_OPACITY);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, rainColumns * VERTICES_PER_COLUMN);
            }
            if (snowColumns > 0) {
                bind(0, snow, weatherSampler);
                GL20.glUniform1f(colorAmountLocation, 1.0f);
                GL20.glUniform1f(alphaScaleLocation, 1.0f);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, rainColumns * VERTICES_PER_COLUMN, snowColumns * VERTICES_PER_COLUMN);
            }
        }
    }

    private static int textureId(Minecraft client, Identifier id) {
        AbstractTexture texture = client.getTextureManager().getTexture(id);
        return texture.getTexture() instanceof GlTexture gl ? gl.glId() : 0;
    }

    private static void bind(int unit, int texture, int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33.glBindSampler(unit, sampler);
    }

    private static Matrix4f viewProjection(Camera camera, RenderTarget target) {
        return new Matrix4f()
                .perspective((float) Math.toRadians(CameraFov.worldDegrees()),
                        (float) target.width / target.height, NEAR_PLANE, FAR_PLANE)
                .rotate(camera.rotation().conjugate(new Quaternionf()));
    }

    /** Vanilla's column layout: a camera-facing quad per column, in camera-relative space. */
    private void addColumns(List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 cameraPos,
                            float maxAlpha, int radius, float intensity) {
        int cameraX = (int) Math.floor(cameraPos.x), cameraZ = (int) Math.floor(cameraPos.z);
        float radiusSq = radius * radius;
        for (WeatherEffectRenderer.ColumnInstance column : columns) {
            float relativeX = (float) (column.x() + 0.5 - cameraPos.x);
            float relativeZ = (float) (column.z() + 0.5 - cameraPos.z);
            float t = Math.min((relativeX * relativeX + relativeZ * relativeZ) / radiusSq, 1.0f);
            float alpha = (maxAlpha + (0.5f - maxAlpha) * t) * intensity;
            int index = (column.z() - cameraZ + 16) * 32 + column.x() - cameraX + 16;
            float halfX = columnSizeX[index] / 2.0f, halfZ = columnSizeZ[index] / 2.0f;
            float x0 = relativeX - halfX, x1 = relativeX + halfX;
            float z0 = relativeZ - halfZ, z1 = relativeZ + halfZ;
            float y0 = (float) (column.bottomY() - cameraPos.y), y1 = (float) (column.topY() - cameraPos.y);
            float u0 = column.uOffset(), u1 = column.uOffset() + 1.0f;
            float v0 = column.bottomY() * 0.25f + column.vOffset(), v1 = column.topY() * 0.25f + column.vOffset();
            float lightU = (LightCoordsUtil.block(column.lightCoords()) + 0.5f) / 16.0f;
            float lightV = (LightCoordsUtil.sky(column.lightCoords()) + 0.5f) / 16.0f;
            addVertex(x0, y1, z0, u0, v0, alpha, lightU, lightV);
            addVertex(x1, y1, z1, u1, v0, alpha, lightU, lightV);
            addVertex(x1, y0, z1, u1, v1, alpha, lightU, lightV);
            addVertex(x1, y0, z1, u1, v1, alpha, lightU, lightV);
            addVertex(x0, y0, z0, u0, v1, alpha, lightU, lightV);
            addVertex(x0, y1, z0, u0, v0, alpha, lightU, lightV);
        }
    }

    private void addVertex(float x, float y, float z, float u, float v, float alpha, float lightU, float lightV) {
        vertices.put(x).put(y).put(z).put(u).put(v).put(alpha).put(lightU).put(lightV);
    }

    private static FloatBuffer ensureCapacity(FloatBuffer buffer, int floats) {
        return buffer.capacity() >= floats ? buffer : BufferUtils.createFloatBuffer(floats * 2);
    }

    private void create() {
        int vertexShader = compile(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertexShader);
        GL20.glAttachShader(linked, fragmentShader);
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(linked);
            GL20.glDeleteProgram(linked);
            throw new IllegalStateException("Failed to link weather shader: " + log);
        }

        try (GlStateSnapshot ignored = new GlStateSnapshot()) {
            GL20.glUseProgram(linked);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked, "Weather"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked, "Lightmap"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(linked, "RemixDepth"), 2);
            viewProjectionLocation = GL20.glGetUniformLocation(linked, "ViewProjection");
            framebufferSizeLocation = GL20.glGetUniformLocation(linked, "FramebufferSize");
            colorAmountLocation = GL20.glGetUniformLocation(linked, "ColorAmount");
            alphaScaleLocation = GL20.glGetUniformLocation(linked, "AlphaScale");

            vertexArray = GL30.glGenVertexArrays();
            vertexBuffer = GL15.glGenBuffers();
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, stride, 5L * Float.BYTES);
            GL20.glEnableVertexAttribArray(3);
            GL20.glVertexAttribPointer(3, 2, GL11.GL_FLOAT, false, stride, 6L * Float.BYTES);
        }

        // Rain and snow scroll past the texture's edge, so they repeat; the others must not.
        weatherSampler = sampler(GL11.GL_REPEAT, GL11.GL_NEAREST);
        clampedSampler = sampler(GL13.GL_CLAMP_TO_EDGE, GL11.GL_LINEAR);
        framebuffer = GL30.glGenFramebuffers();
        program = linked;
    }

    private static int sampler(int wrap, int filter) {
        int sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, wrap);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, filter);
        return sampler;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile weather shader: " + log);
        }
        return shader;
    }

    /** Releases the GL objects. Render thread only. */
    public void cleanup() {
        if (program == 0) {
            return;
        }
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(vertexArray);
        GL15.glDeleteBuffers(vertexBuffer);
        GL33.glDeleteSamplers(weatherSampler);
        GL33.glDeleteSamplers(clampedSampler);
        GL30.glDeleteFramebuffers(framebuffer);
        program = 0;
    }

    /** Records the GL state this renderer touches and restores it on close. */
    private static final class GlStateSnapshot implements AutoCloseable {
        private static final int UNITS = 3;
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int[] textures = new int[UNITS];
        private final int[] samplers = new int[UNITS];
        private final boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final int[] viewport = new int[4];
        private final boolean[] colorMask = new boolean[4];

        private GlStateSnapshot() {
            for (int unit = 0; unit < UNITS; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                samplers[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            }
            GL13.glActiveTexture(activeTexture);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) {
                    colorMask[i] = mask.get(i) != 0;
                }
            }
        }

        @Override
        public void close() {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            for (int unit = 0; unit < UNITS; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[unit]);
                GL33.glBindSampler(unit, samplers[unit]);
            }
            GL13.glActiveTexture(activeTexture);
            set(GL11.GL_DEPTH_TEST, depthTest);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_SCISSOR_TEST, scissor);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }

        private static void set(int capability, boolean enabled) {
            if (enabled) {
                GL11.glEnable(capability);
            } else {
                GL11.glDisable(capability);
            }
        }
    }
}
