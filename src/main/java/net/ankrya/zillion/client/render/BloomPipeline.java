package net.ankrya.zillion.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.ankrya.zillion.Config;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL30.*;

/**
 * Bounded, opt-in, energy-only bloom. Call after opaque scene depth has been rendered.
 * begin does NOT redirect the global framebuffer: only FxRenderTypes.energy's output shard
 * binds the emission target, and restores the previous target after each actual draw.
 *
 * Preferred usage: capture(source, () -> emitOnlyEnergy(source)). This flushes energy before
 * begin and before composite, in a finally block. Emit the energy ONCE: composite adds both
 * its sharp image and blurred halo. If begin returns false, emit normally and flush normally.
 * Never keep a pending energy batch across begin/composite. Armor/holograms are not captured.
 * All methods are render-thread-only; no asynchronous GL work or whole-scene extraction.
 */
public final class BloomPipeline {
    private static RenderTarget emission;
    private static RenderTarget ping;
    private static RenderTarget pong;
    private static boolean active;
    private static boolean failed;
    private static int energyReadTarget;
    private static int energyDrawTarget;
    private static boolean energyBound;

    private BloomPipeline() {}

    @net.neoforged.fml.common.EventBusSubscriber(modid = "zillion", value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class Lifecycle {
        private Lifecycle() {}

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            // Release potentially large full-resolution attachments when returning to the title screen.
            close();
        }
    }

    public static boolean begin(int width, int height) {
        RenderSystem.assertOnRenderThread();
        if (active) throw new IllegalStateException("Nested Zillion bloom capture");
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (!Config.bloomEnabled || failed || FxShaders.blurShader == null || FxShaders.compositeShader == null
                || mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS
                || width != main.width || height != main.height || !main.useDepth
                || width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long) width * height > 8_847_360L
                || glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId) return false;
        State state = new State();
        try {
            GlStateManager._activeTexture(GL_TEXTURE0);
            if (emission == null || emission.width != width || emission.height != height) {
                destroyTargets();
                emission = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                // At most two 960x540 blur surfaces, independent of full-screen resolution.
                float scale = Math.min(0.5f, Math.min(960f / width, 540f / height));
                int w = Math.max(1, Math.round(width * scale));
                int h = Math.max(1, Math.round(height * scale));
                ping = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                pong = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                emission.setFilterMode(GL_LINEAR);
                ping.setFilterMode(GL_LINEAR);
                pong.setFilterMode(GL_LINEAR);
                emission.setClearColor(0, 0, 0, 0);
            }
            // A stencil-enabled/shaderpack framebuffer can use an incompatible depth format.
            // Fail closed instead of producing unoccluded glow if the attachments differ.
            if (depthFormat(main) != depthFormat(emission)) return false;
            RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            emission.clear(Minecraft.ON_OSX);
            emission.copyDepthFrom(main);
            active = true;
            return true;
        } catch (RuntimeException exception) {
            failed = true;
            destroyTargets();
            com.mojang.logging.LogUtils.getLogger().warn("Zillion bloom disabled until resource reload", exception);
            return false;
        } finally { state.restore(); }
    }

    private static int depthFormat(RenderTarget target) {
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(target.getDepthTextureId());
        return glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
    }

    public static boolean begin() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        return begin(main.width, main.height);
    }

    public static boolean isActive() { return active; }

    /** Convenience scope; source must be the same concrete BufferSource used by geometry. */
    public static void capture(MultiBufferSource.BufferSource source, Runnable geometry) {
        RenderSystem.assertOnRenderThread();
        source.endBatch(FxRenderTypes.energy());
        boolean capturing = begin();
        try { geometry.run(); }
        finally {
            try { source.endBatch(FxRenderTypes.energy()); }
            finally { if (capturing) composite((float) Config.bloomStrength); }
        }
    }

    static void bindEnergyTarget() {
        if (!active) return;
        energyReadTarget = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        energyDrawTarget = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        emission.bindWrite(false);
        energyBound = true;
    }

    static void restoreEnergyTarget() {
        if (!energyBound) return;
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, energyReadTarget);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, energyDrawTarget);
        energyBound = false;
    }

    /** Flush your source's energy batch BEFORE calling this. Does nothing without a successful begin. */
    public static void composite(float strength) {
        RenderSystem.assertOnRenderThread();
        if (!active) return;
        active = false;
        restoreEnergyTarget();
        State state = new State();
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, false);
            RenderSystem.disableBlend();
            RenderSystem.blendEquation(GL_FUNC_ADD);
            ShaderInstance blur = FxShaders.blurShader;
            blur.setSampler("SceneDepth", emission.getDepthTextureId());
            blur.safeGetUniform("InverseProjection").set(new org.joml.Matrix4f(RenderSystem.getProjectionMatrix()).invert());
            ping.bindWrite(true);
            blur.setSampler("DiffuseSampler", emission.getColorTextureId());
            blur.safeGetUniform("Direction").set(1f / ping.width, 0f);
            quad(blur);
            pong.bindWrite(true);
            blur.setSampler("DiffuseSampler", ping.getColorTextureId());
            blur.safeGetUniform("Direction").set(0f, 1f / pong.height);
            quad(blur);
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            ShaderInstance composite = FxShaders.compositeShader;
            composite.setSampler("EmissionSampler", emission.getColorTextureId());
            composite.setSampler("BloomSampler", pong.getColorTextureId());
            composite.safeGetUniform("Strength").set(Float.isFinite(strength) ? Math.clamp(strength, 0f, 3f) : 0f);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE);
            quad(composite);
        } finally { state.restore(); }
    }

    public static void end() { composite((float) Config.bloomStrength); }

    /** Reload/shutdown cleanup. Shader instances themselves are owned by Minecraft. */
    public static void close() {
        RenderSystem.assertOnRenderThread();
        active = false;
        restoreEnergyTarget();
        destroyTargets();
        failed = false;
    }

    private static void destroyTargets() {
        if (emission != null) emission.destroyBuffers();
        if (ping != null) ping.destroyBuffers();
        if (pong != null) pong.destroyBuffers();
        emission = ping = pong = null;
    }

    private static void quad(ShaderInstance shader) {
        // Clip-space shader deliberately ignores world ModelViewMat/ProjMat. Custom sampler names
        // avoid BufferUploader replacing them with RenderSystem's ordinary Sampler0..11 slots.
        RenderSystem.setShader(() -> shader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(-1, -1, 0).setUv(0, 0);
        buffer.addVertex(1, -1, 0).setUv(1, 0);
        buffer.addVertex(1, 1, 0).setUv(1, 1);
        buffer.addVertex(-1, 1, 0).setUv(0, 1);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** Restores every GL/RenderSystem state touched by target allocation and fullscreen passes. */
    private static final class State {
        final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        final int[] viewport = new int[4];
        final int[] scissorBox = new int[4];
        final float[] clearColor = new float[4];
        final double clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
        final boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST);
        final boolean cull = glIsEnabled(GL_CULL_FACE), scissor = glIsEnabled(GL_SCISSOR_TEST);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        final int[] textures = new int[3];
        final ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
        final ShaderInstance shader = RenderSystem.getShader();
        final int program = glGetInteger(GL_CURRENT_PROGRAM);
        final int blendEquationRgb = glGetInteger(org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_RGB);
        final int blendEquationAlpha = glGetInteger(org.lwjgl.opengl.GL20.GL_BLEND_EQUATION_ALPHA);
        final int vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        final int elementBuffer = glGetInteger(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        State() {
            glGetIntegerv(GL_VIEWPORT, viewport);
            glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
            glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
            glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
            for (int i = 0; i < textures.length; i++) {
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
            }
            GlStateManager._activeTexture(activeTexture);
        }

        void restore() {
            ShaderInstance current = RenderSystem.getShader();
            if (current != shader && current != null) current.clear();
            RenderSystem.setShader(() -> shader);
            GlStateManager._glUseProgram(program);
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, arrayBuffer);
            if (vertexArray != 0) GlStateManager._glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
            for (int i = 0; i < textures.length; i++) {
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                GlStateManager._bindTexture(textures[i]);
            }
            GlStateManager._activeTexture(activeTexture);
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            org.lwjgl.opengl.GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.depthMask(depthMask);
            RenderSystem.colorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.clearDepth(clearDepth);
            RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            if (!scissor) RenderSystem.disableScissor();
        }
    }
}
