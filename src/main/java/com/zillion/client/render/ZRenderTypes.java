package com.zillion.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.zillion.Zillion;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Custom render types backed by the mod's core shaders.
 * <p>
 * <b>Shader pack compatibility (Iris):</b> while a shader pack is active, Iris replaces the vanilla shader programs
 * with the pack's programs and does not know the mod's own core shaders - everything drawn with them disappears.
 * Every render type here therefore is a <i>switching</i> render type: without a shader pack it uses the original
 * custom shader; with a shader pack it uses a vanilla shader that Iris maps onto the pack
 * ({@code position_tex_color} / entity shaders) together with textures that are pre-baked from the original GLSL
 * (energy line profile, circle / flare / ring styles). The vertex format stays the same, so callers are unchanged.
 */
public final class ZRenderTypes extends RenderType {
    private ZRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static ShaderInstance hologramShader;
    public static ShaderInstance energyShader;
    public static ShaderInstance circleShader;
    public static ShaderInstance glowTexShader;

    // ================================================================== shader pack detection

    private static MethodHandle irisInstance;
    private static MethodHandle irisInUse;
    private static boolean irisLookedUp;

    /** True if Iris (or a fork with the same API) is installed and a shader pack is currently in use. */
    public static boolean shaderPackInUse() {
        if (!irisLookedUp) {
            irisLookedUp = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                irisInstance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
                irisInUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
            } catch (Throwable ignored) {
                irisInstance = null;
                irisInUse = null;
            }
        }
        if (irisInstance == null)
            return false;
        try {
            return (boolean) irisInUse.invoke(irisInstance.invoke());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Render type that decides at draw time between the custom-shader version and the shader-pack fallback.
     * Both must use the same vertex format / mode.
     */
    private static RenderType switching(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean sortOnUpload,
                                        RenderType normal, RenderType fallback, Runnable normalExtra, Runnable fallbackExtra,
                                        Runnable fallbackClearExtra) {
        final RenderType[] active = new RenderType[1];
        final boolean[] fb = new boolean[1];
        return new ZRenderTypes(name, format, mode, bufferSize, false, sortOnUpload,
                () -> {
                    fb[0] = shaderPackInUse();
                    if (fb[0]) {
                        FallbackTextures.ensure();
                        active[0] = fallback;
                        fallback.setupRenderState();
                        if (fallbackExtra != null)
                            fallbackExtra.run();
                    } else {
                        active[0] = normal;
                        normal.setupRenderState();
                        if (normalExtra != null)
                            normalExtra.run();
                    }
                },
                () -> {
                    if (active[0] != null)
                        active[0].clearRenderState();
                    if (fb[0] && fallbackClearExtra != null)
                        fallbackClearExtra.run();
                    active[0] = null;
                });
    }

    private static final ShaderStateShard HOLOGRAM_SHADER = new ShaderStateShard(() -> hologramShader);
    private static final ShaderStateShard ENERGY_SHADER = new ShaderStateShard(() -> energyShader);
    private static final ShaderStateShard CIRCLE_SHADER = new ShaderStateShard(() -> circleShader);
    private static final ShaderStateShard GLOWTEX_SHADER = new ShaderStateShard(() -> glowTexShader);
    /** vanilla position_tex_color - Iris maps it onto the shader pack */
    private static final ShaderStateShard VANILLA_POS_TEX_COLOR = new ShaderStateShard(GameRenderer::getPositionTexColorShader);

    // ================================================================== hologram (armor / drone)

    /**
     * Uniform values for the hologram shader. The values are baked into the RenderType's setup runnable, so every
     * distinct (quantised) parameter set gets its own RenderType instance (cached by key).
     */
    public record HoloParams(float holo, float alpha, float redGlow, float lines, float mode, float flash, float rim) {
        public static final HoloParams ARMOR_IDLE = new HoloParams(0, 1, 1.0f, 0, 0, 0, 0);

        /** Quantise so the RenderType cache stays small. */
        HoloParams quantised() {
            return new HoloParams(q(holo), q(alpha), q(redGlow), q(lines), mode, q(flash), q(rim));
        }

        private static float q(float v) {
            return Math.round(v * 24f) / 24f;
        }
    }

    private static final Map<HoloKey, RenderType> HOLO_CACHE = new HashMap<>();

    private record HoloKey(ResourceLocation texture, HoloParams params, boolean translucent) {}

    /** Armor / drone with the hologram shader. Depth is only written when the surface is effectively solid. */
    public static RenderType hologram(ResourceLocation texture, HoloParams params) {
        HoloParams p = params.quantised();
        boolean translucent = p.holo() > 0.001f || p.alpha() < 0.999f;
        HoloKey key = new HoloKey(texture, p, translucent);
        return HOLO_CACHE.computeIfAbsent(key, k -> {
            Runnable paramSetup = () -> {
                ShaderInstance s = hologramShader;
                if (s != null) {
                    if (s.getUniform("Params") != null)
                        s.getUniform("Params").set(p.holo(), p.alpha(), p.redGlow(), p.lines());
                    if (s.getUniform("Params2") != null)
                        s.getUniform("Params2").set(p.mode(), p.flash(), p.rim(), 0f);
                }
            };
            CompositeState state = CompositeState.builder()
                    .setShaderState(HOLOGRAM_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(translucent ? COLOR_WRITE : COLOR_DEPTH_WRITE)
                    .createCompositeState(false);
            RenderType base = RenderType.create("zillion_hologram", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, translucent, state);
            // shader pack: vanilla entity shaders (Iris supports them); the hologram alpha goes through ColorModulator
            RenderType fallback = translucent ? RenderType.entityTranslucentEmissive(texture) : RenderType.entityCutoutNoCull(texture);
            float fadeAlpha = Math.max(0f, Math.min(1f, p.alpha()));
            return switching("zillion_hologram_p", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, translucent,
                    base, fallback, paramSetup,
                    translucent ? () -> RenderSystem.setShaderColor(1f, 1f, 1f, fadeAlpha) : null,
                    translucent ? () -> RenderSystem.setShaderColor(1f, 1f, 1f, 1f) : null);
        });
    }

    // ================================================================== energy lines

    private static RenderType energyFallback(boolean depthTest) {
        CompositeState.CompositeStateBuilder b = CompositeState.builder()
                .setShaderState(VANILLA_POS_TEX_COLOR)
                .setTextureState(new TextureStateShard(FallbackTextures.ENERGY, false, false))
                .setTransparencyState(LIGHTNING_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_WRITE);
        if (!depthTest)
            b.setDepthTestState(NO_DEPTH_TEST);
        return RenderType.create(depthTest ? "zillion_energy_fb" : "zillion_energy_nodepth_fb", DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS, 4096, false, true, b.createCompositeState(false));
    }

    /** Additive glowing lines (POSITION_TEX_COLOR). */
    public static final RenderType ENERGY = switching("zillion_energy_s", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096, true,
            RenderType.create("zillion_energy", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096, false, true,
                    CompositeState.builder()
                            .setShaderState(ENERGY_SHADER)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)),
            energyFallback(true), null, null, null);

    /** Same but with depth test disabled (drawn over the model). */
    public static final RenderType ENERGY_NO_DEPTH = switching("zillion_energy_nodepth_s", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1024, true,
            RenderType.create("zillion_energy_nodepth", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1024, false, true,
                    CompositeState.builder()
                            .setShaderState(ENERGY_SHADER)
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)),
            energyFallback(false), null, null, null);

    // ================================================================== procedural circles

    private static final Map<Integer, RenderType> CIRCLE_CACHE = new HashMap<>();

    /** Procedural circle quad. style 0 = tech double ring, 1 = flare, 2 = ground ring. */
    public static RenderType circle(int style, float spin, float detail, float radiusScale, boolean depthTest) {
        float rs = Math.round(radiusScale * 32f) / 32f;
        float sp = Math.round(spin * 8f) / 8f;
        float dt = Math.round(detail * 8f) / 8f;
        int key = Objects.hash(style, sp, dt, rs, depthTest);
        return CIRCLE_CACHE.computeIfAbsent(key, k -> {
            Runnable paramSetup = () -> {
                ShaderInstance s = circleShader;
                if (s != null && s.getUniform("Params") != null)
                    s.getUniform("Params").set((float) style, sp, dt, rs);
            };
            CompositeState.CompositeStateBuilder b = CompositeState.builder()
                    .setShaderState(CIRCLE_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE);
            if (!depthTest)
                b.setDepthTestState(NO_DEPTH_TEST);
            RenderType base = RenderType.create("zillion_circle", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true, b.createCompositeState(false));

            CompositeState.CompositeStateBuilder fbb = CompositeState.builder()
                    .setShaderState(VANILLA_POS_TEX_COLOR)
                    .setTextureState(new TextureStateShard(FallbackTextures.circle(style), false, false))
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE);
            if (!depthTest)
                fbb.setDepthTestState(NO_DEPTH_TEST);
            RenderType fallback = RenderType.create("zillion_circle_fb", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true, fbb.createCompositeState(false));

            return switching("zillion_circle_p", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, true,
                    base, fallback, paramSetup, null, null);
        });
    }

    // ================================================================== textured glow (emblems)

    /** Textured additive glow quad (back emblem). */
    public static final Function<ResourceLocation, RenderType> GLOW_TEX = Util.memoize(texture -> {
        RenderType base = RenderType.create("zillion_glowtex", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true,
                CompositeState.builder()
                        .setShaderState(GLOWTEX_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
        RenderType fallback = RenderType.create("zillion_glowtex_fb", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true,
                CompositeState.builder()
                        .setShaderState(VANILLA_POS_TEX_COLOR)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
        return switching("zillion_glowtex_s", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, true,
                base, fallback, null, null, null);
    });

    // ================================================================== baked fallback textures

    /**
     * Textures generated at runtime from the same formulas as the GLSL shaders (time = 0), used only while a shader
     * pack is active. White RGB + alpha profile; the vertex colour tints them.
     */
    static final class FallbackTextures {
        static final ResourceLocation ENERGY = Zillion.id("fx_fallback/energy");
        private static final ResourceLocation[] CIRCLES = {
                Zillion.id("fx_fallback/circle0"), Zillion.id("fx_fallback/circle1"), Zillion.id("fx_fallback/circle2")};
        private static boolean created;

        static ResourceLocation circle(int style) {
            return CIRCLES[Math.max(0, Math.min(2, style))];
        }

        /** Must run on the render thread before a fallback texture state is set up. */
        static void ensure() {
            if (created)
                return;
            created = true;
            var tm = Minecraft.getInstance().getTextureManager();
            tm.register(ENERGY, bake(128, 32, FallbackTextures::energy));
            for (int s = 0; s < 3; s++) {
                final int style = s;
                tm.register(CIRCLES[s], bake(256, 256, (u, v) -> circle(style, u, v)));
            }
        }

        private interface Alpha {
            float at(float u, float v);
        }

        private static DynamicTexture bake(int w, int h, Alpha f) {
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float a = Math.max(0f, Math.min(1f, f.at((x + 0.5f) / w, (y + 0.5f) / h)));
                    int ai = Math.round(a * 255f);
                    // NativeImage pixels are ABGR
                    img.setPixelRGBA(x, y, (ai << 24) | 0x00FFFFFF);
                }
            }
            DynamicTexture tex = new DynamicTexture(img);
            tex.setFilter(true, false);
            return tex;
        }

        private static float energy(float u, float v) {
            float d = Math.abs(v - 0.5f) * 2f;
            float core = (float) Math.exp(-d * d * 22f);
            float glow = (float) Math.exp(-d * d * 3f) * 0.45f;
            float end = smooth(0f, 0.10f, u) * (1f - smooth(0.78f, 1f, u));
            return (core + glow) * end;
        }

        private static float ring(float r, float r0, float w) {
            float k = (r - r0) / w;
            return (float) Math.exp(-k * k);
        }

        private static float circle(int style, float u, float v) {
            float px = (u - 0.5f) * 2f, py = (v - 0.5f) * 2f;
            float r = (float) Math.sqrt(px * px + py * py);
            float ang = (float) Math.atan2(py, px);
            float twoPi = (float) (Math.PI * 2);
            if (style == 0) {
                float detail = 0.5f;
                float outer = ring(r, 0.94f, 0.035f) * 1.2f;
                float inner = ring(r, 0.66f, 0.03f);
                float ticks = step(0.55f, fract(ang / twoPi * 48f));
                float tickBand = smooth(0.70f, 0.73f, r) * (1f - smooth(0.86f, 0.89f, r)) * ticks * 0.55f;
                float seg = step(0.30f, fract(ang / twoPi * 6f + 0.1f));
                float segBand = smooth(0.885f, 0.9f, r) * (1f - smooth(0.92f, 0.935f, r)) * seg * 0.9f;
                float dashes = step(0.5f, fract(ang / twoPi * 18f));
                float dashBand = smooth(0.50f, 0.52f, r) * (1f - smooth(0.58f, 0.60f, r)) * dashes * 0.6f;
                float qx = px * 5f, qy = py * 5f;
                float hx = Math.abs(fract(qx) - 0.5f), hy = Math.abs(fract(qy) - 0.5f);
                float hex = (1f - smooth(0f, 0.08f, Math.min(hx, hy))) * (1f - smooth(0.35f, 0.48f, r)) * 0.25f * detail;
                float centre = (float) Math.exp(-r * r * 9f) * 0.55f * detail;
                float cross = ((1f - smooth(0f, 0.012f, Math.abs(px))) + (1f - smooth(0f, 0.012f, Math.abs(py))))
                        * (1f - smooth(0.55f, 0.62f, r)) * 0.35f * detail;
                return (outer + inner + tickBand + segBand + dashBand + hex + centre + cross) * (1f - smooth(0.98f, 1f, r));
            }
            if (style == 1) {
                float core = (float) Math.exp(-r * r * 18f);
                float halo = (float) Math.exp(-r * r * 3f) * 0.5f;
                float rays = (float) Math.pow(Math.abs(Math.sin(ang * 3f)), 8f) * (float) Math.exp(-r * r * 4f) * 0.5f;
                return (core * 1.6f + halo + rays) * (1f - smooth(0.85f, 1f, r));
            }
            float main = ring(r, 0.90f, 0.05f) * 1.3f;
            float second = ring(r, 0.72f, 0.02f) * 0.7f;
            float ticks = step(0.5f, fract(ang / twoPi * 64f));
            float tickBand = smooth(0.78f, 0.80f, r) * (1f - smooth(0.86f, 0.88f, r)) * ticks * 0.6f;
            float k = (r - 0.85f) / 0.25f;
            float fill = (float) Math.exp(-k * k) * 0.12f;
            return (main + second + tickBand + fill) * (1f - smooth(0.97f, 1f, r));
        }

        private static float smooth(float e0, float e1, float x) {
            float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
            return t * t * (3f - 2f * t);
        }

        private static float step(float edge, float x) {
            return x < edge ? 0f : 1f;
        }

        private static float fract(float x) {
            return x - (float) Math.floor(x);
        }
    }
}
