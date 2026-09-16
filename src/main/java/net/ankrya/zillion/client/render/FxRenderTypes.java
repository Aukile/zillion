package net.ankrya.zillion.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** Client-only render types. Neither effect writes depth or replaces the normal armor pass. */
public final class FxRenderTypes extends RenderType {
    private static final TransparencyStateShard ENERGY_BLEND = new TransparencyStateShard("zillion_additive",
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE,
                        org.lwjgl.opengl.GL11.GL_ZERO, org.lwjgl.opengl.GL11.GL_ONE);
            }, () -> { RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); });
    private static final OutputStateShard ENERGY_OUTPUT = new OutputStateShard("zillion_emission_only",
            BloomPipeline::bindEnergyTarget, BloomPipeline::restoreEnergyTarget);
    private static final RenderType ENERGY = create("zillion_energy", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 65536, false, false, CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(ENERGY_BLEND).setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                    .setOutputState(ENERGY_OUTPUT).createCompositeState(false));
    private static final Map<ResourceLocation, RenderType> HOLOGRAMS = new HashMap<>();

    private FxRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                          boolean crumbling, boolean sorted, Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sorted, setup, clear);
    }

    /** POSITION_COLOR QUADS. Use alpha-zero outer vertices and opaque inner vertices for soft ribbons. */
    public static RenderType energy() { return ENERGY; }

    /**
     * GeckoLib's complete entity vertex: position, color, UV0, UV1 overlay, UV2 light, normal.
     * POSITION_COLOR_TEX_LIGHTMAP is NOT compatible with GeckoLib's full addVertex overload.
     * Use FxShaders.draw for per-player uniforms; reacquire the consumer inside its callback.
     */
    public static RenderType hologram(ResourceLocation texture) {
        return HOLOGRAMS.computeIfAbsent(texture, key -> create("zillion_hologram", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 16384, false, false, CompositeState.builder()
                        .setShaderState(new ShaderStateShard(FxShaders::hologram))
                        .setTextureState(new TextureStateShard(key, false, false))
                        .setTransparencyState(ENERGY_BLEND).setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE)
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(false)));
    }

    /** Flush only this mod's types, never the entire shared entity/armor batch. */
    public static void flush(MultiBufferSource.BufferSource source) {
        source.endBatch(ENERGY);
        for (RenderType type : HOLOGRAMS.values()) source.endBatch(type);
    }
}
