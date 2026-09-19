package com.zillion.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Custom render types backed by the mod's core shaders.
 */
public final class ZRenderTypes extends RenderType {
    private ZRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static ShaderInstance hologramShader;
    public static ShaderInstance energyShader;
    public static ShaderInstance circleShader;
    public static ShaderInstance glowTexShader;

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

    private static final ShaderStateShard HOLOGRAM_SHADER = new ShaderStateShard(() -> hologramShader);
    private static final ShaderStateShard ENERGY_SHADER = new ShaderStateShard(() -> energyShader);
    private static final ShaderStateShard CIRCLE_SHADER = new ShaderStateShard(() -> circleShader);
    private static final ShaderStateShard GLOWTEX_SHADER = new ShaderStateShard(() -> glowTexShader);

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
            // wrap so our uniform setup runs after the shader was bound
            return new ZRenderTypes("zillion_hologram_p", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, true, translucent,
                    () -> { base.setupRenderState(); paramSetup.run(); },
                    base::clearRenderState);
        });
    }

    /** Additive glowing lines (POSITION_TEX_COLOR). */
    public static final RenderType ENERGY = RenderType.create("zillion_energy", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096, false, true,
            CompositeState.builder()
                    .setShaderState(ENERGY_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** Same but with depth test disabled (drawn over the model). */
    public static final RenderType ENERGY_NO_DEPTH = RenderType.create("zillion_energy_nodepth", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1024, false, true,
            CompositeState.builder()
                    .setShaderState(ENERGY_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

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
            return new ZRenderTypes("zillion_circle_p", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true,
                    () -> { base.setupRenderState(); paramSetup.run(); },
                    base::clearRenderState);
        });
    }

    /** Textured additive glow quad (back emblem). */
    public static final Function<ResourceLocation, RenderType> GLOW_TEX = Util.memoize(texture ->
            RenderType.create("zillion_glowtex", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 256, false, true,
                    CompositeState.builder()
                            .setShaderState(GLOWTEX_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTransparencyState(LIGHTNING_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)));
}
