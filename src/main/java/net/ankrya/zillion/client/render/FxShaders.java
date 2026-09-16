package net.ankrya.zillion.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.ankrya.zillion.Zillion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Shader ownership belongs to GameRenderer; it closes/replaces instances on reload. */
@EventBusSubscriber(modid = Zillion.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FxShaders {
    private static ShaderInstance hologramShader;
    static ShaderInstance blurShader;
    static ShaderInstance compositeShader;
    private static float time;
    private static float intensity = 1;
    private static boolean flushing;
    private static final Set<MultiBufferSource.BufferSource> SCOPED_SOURCES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private FxShaders() {}

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) {
        // The registration event runs on the render thread during initial load and F3+T reload.
        BloomPipeline.close();
        hologramShader = blurShader = compositeShader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), id("gazer_hologram"),
                    DefaultVertexFormat.NEW_ENTITY), shader -> hologramShader = shader);
        } catch (IOException | RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().warn("Zillion hologram shader unavailable; using vanilla emissive fallback", exception);
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), id("gazer_blur"),
                    DefaultVertexFormat.POSITION_TEX), shader -> blurShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), id("gazer_composite"),
                    DefaultVertexFormat.POSITION_TEX), shader -> compositeShader = shader);
        } catch (IOException | RuntimeException exception) {
            com.mojang.logging.LogUtils.getLogger().warn("Zillion bloom shaders unavailable; rendering energy without bloom", exception);
        }
    }

    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath(Zillion.MODID, name); }

    static ShaderInstance hologram() {
        if (hologramShader == null) return GameRenderer.getRendertypeEntityTranslucentEmissiveShader();
        hologramShader.safeGetUniform("FxTime").set(time);
        hologramShader.safeGetUniform("FxIntensity").set(intensity);
        return hologramShader;
    }

    /** Render thread only. Flushes outstanding mod batches BEFORE changing the value. */
    public static void setTime(float seconds) {
        RenderSystem.assertOnRenderThread();
        float value = Float.isFinite(seconds) ? seconds % 4096f : 0;
        if (time != value) { flushPending(); time = value; }
    }

    /** Render thread only. Intensity scales the hologram; energy uses vertex color/alpha. */
    public static void setIntensity(float value) {
        RenderSystem.assertOnRenderThread();
        value = Float.isFinite(value) ? Math.clamp(value, 0f, 4f) : 0;
        if (intensity != value) { flushPending(); intensity = value; }
    }

    private static void flushPending() {
        if (flushing) return;
        flushing = true;
        try {
            FxRenderTypes.flush(Minecraft.getInstance().renderBuffers().bufferSource());
            for (MultiBufferSource.BufferSource source : SCOPED_SOURCES) FxRenderTypes.flush(source);
        } finally { flushing = false; }
    }

    /**
     * Safe per-player draw, also supporting a dedicated immediate BufferSource. Queued geometry
     * is flushed before changing uniforms, then this draw is flushed before restoring them.
     * The callback MUST obtain its consumer after entering this scope. Do not retain consumers
     * across scopes. Supply a concrete BufferSource, not an OutlineBufferSource wrapper.
     */
    public static void draw(MultiBufferSource.BufferSource source, RenderType type,
                            float seconds, float strength, Runnable geometry) {
        RenderSystem.assertOnRenderThread();
        source.endBatch(type);
        boolean added = SCOPED_SOURCES.add(source);
        float oldTime = time;
        float oldIntensity = intensity;
        try {
            setTime(seconds);
            setIntensity(strength);
            geometry.run();
        } finally {
            try { source.endBatch(type); }
            finally {
                setTime(oldTime);
                setIntensity(oldIntensity);
                if (added) SCOPED_SOURCES.remove(source);
            }
        }
    }
}
