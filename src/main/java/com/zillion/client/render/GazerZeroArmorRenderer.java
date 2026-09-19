package com.zillion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zillion.client.ClientHenshinHandler;
import com.zillion.client.fx.HenshinInstance;
import com.zillion.client.fx.HenshinRendererAccess;
import com.zillion.henshin.HenshinTiming;
import com.zillion.item.GazerZeroArmorItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Gazer Zero armor renderer.
 * <ul>
 *   <li>Render type = hologram shader; parameters follow the transformation timeline (green tech filter → solid,
 *       red parts glow, sliding line patterns during the finale).</li>
 *   <li>Wingman bones stay hidden until the drones have docked.</li>
 *   <li>Scarf (bone6) stays hidden until the scarf reveal.</li>
 *   <li>Chest ring layer draws the double tech ring on the chest during the finale.</li>
 * </ul>
 */
public class GazerZeroArmorRenderer extends GeoArmorRenderer<GazerZeroArmorItem> {
    private static final String[] WINGMEN = {"wingman1", "wingman2", "wingman3", "wingman4", "wingman5"};
    private static final String SCARF = "bone6";

    /** Sequence time for the entity currently being rendered, or -1 if it is not transforming. */
    private float seqTime = -1;
    private HenshinInstance instance;

    public GazerZeroArmorRenderer() {
        super(new GazerZeroArmorModel());
        addRenderLayer(new ChestRingLayer(this));
    }

    private void refreshState(float partialTick) {
        this.instance = this.currentEntity != null ? ClientHenshinHandler.get(this.currentEntity) : null;
        this.seqTime = this.instance != null ? this.instance.time(partialTick) : -1;
    }

    /** True when the armor's light lines should stay lit permanently (night time). */
    public static boolean isNight() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null)
            return false;
        long day = level.getDayTime() % 24000L;
        return day >= 12800L && day <= 23200L;
    }

    /** Glow strength of the red / cyan light lines at sequence time t (t < 0 = not transforming). */
    public static float glowFor(float t) {
        float night = isNight() ? 1f : 0f;
        if (t < 0)
            return night;
        // finale: lines light up, hold, and go dark again
        float finale = HenshinInstance.window(t, HenshinTiming.FINALE_START, HenshinTiming.FINALE_END + 6, 14f, 12f);
        finale += HenshinInstance.window(t, HenshinTiming.FINALE_START, HenshinTiming.FINALE_START + 24, 4f, 12f) * 0.7f;
        // one extra flash after everything finished
        float flash = HenshinInstance.window(t, HenshinTiming.AFTER_FLASH_START, HenshinTiming.AFTER_FLASH_END, 3f, 14f) * 1.6f;
        return Math.max(Math.max(finale, flash), night);
    }

    public static ZRenderTypes.HoloParams paramsFor(float t) {
        float glow = glowFor(t);
        if (t < 0)
            return new ZRenderTypes.HoloParams(0, 1, glow, 0, 0, 0, 0);
        float sinceArmor = t - HenshinTiming.ARMOR_TICK;
        // materialisation: alpha 0 -> 1 quickly, hologram 1 -> 0 over the holo window
        float alpha = HenshinInstance.smooth(sinceArmor / 6f);
        float holo = 1f - HenshinInstance.smooth((t - (HenshinTiming.ARMOR_TICK + HenshinTiming.ARMOR_FADE_IN)) / (HenshinTiming.HOLO_END - HenshinTiming.ARMOR_TICK - HenshinTiming.ARMOR_FADE_IN));
        float flash = (1f - HenshinInstance.smooth(sinceArmor / 5f)) * alpha;
        // dock impact: short white-ish pulse over the armor when the drones slam in
        flash += HenshinInstance.window(t, HenshinTiming.DOCK_END - 1, HenshinTiming.DOCK_END + 8, 1f, 6f) * 0.35f;
        float lines = HenshinInstance.window(t, HenshinTiming.FINALE_START, HenshinTiming.FINALE_END, 6f, 14f);
        float rim = holo * 0.5f;
        return new ZRenderTypes.HoloParams(holo, Mth.clamp(alpha, 0f, 1f), glow, lines, 0f, Mth.clamp(flash, 0f, 1f), rim);
    }

    @Override
    public RenderType getRenderType(GazerZeroArmorItem animatable, ResourceLocation texture, @Nullable MultiBufferSource bufferSource, float partialTick) {
        refreshState(partialTick);
        return ZRenderTypes.hologram(texture, paramsFor(this.seqTime));
    }

    @Override
    public void preRender(PoseStack poseStack, GazerZeroArmorItem animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
        float t = this.seqTime;
        boolean wingmenVisible = t < 0 || t >= HenshinTiming.DOCK_END - 0.5f;
        boolean scarfVisible = t < 0 || t >= HenshinTiming.SCARF_APPEAR;
        for (String name : WINGMEN)
            model.getBone(name).ifPresent(b -> hideOnly(b, !wingmenVisible));
        model.getBone(SCARF).ifPresent(b -> hideOnly(b, !scarfVisible));
        // armor pieces don't render at all before they materialise (server equips at ARMOR_TICK, so this is just a guard)
        if (t >= 0 && t < HenshinTiming.ARMOR_TICK)
            setAllBonesVisible(false);
    }

    /** Hide a bone's cubes/children without touching the parent-driven slot visibility of anything else. */
    private static void hideOnly(GeoBone bone, boolean hidden) {
        if (hidden) {
            bone.setHidden(true);
        } else if (bone.isHidden() && bone.getParent() != null && !bone.getParent().isHidden()) {
            bone.setHidden(false);
        }
    }

    @Override
    public void actuallyRender(PoseStack poseStack, GazerZeroArmorItem animatable, BakedGeoModel model, @Nullable RenderType renderType,
                               MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick,
                               int packedLight, int packedOverlay, int colour) {
        if (this.seqTime >= 0 && this.seqTime < HenshinTiming.ARMOR_TICK)
            return;
        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
    }

    public float seqTime() {
        return this.seqTime;
    }

    public EquipmentSlot slot() {
        return this.currentSlot;
    }

    /**
     * Chest double ring: two concentric procedural tech circles drawn just in front of the chest plate during the finale.
     */
    static class ChestRingLayer extends GeoRenderLayer<GazerZeroArmorItem> {
        private final GazerZeroArmorRenderer owner;

        ChestRingLayer(GazerZeroArmorRenderer renderer) {
            super(renderer);
            this.owner = renderer;
        }

        @Override
        public void renderForBone(PoseStack poseStack, GazerZeroArmorItem animatable, GeoBone bone, RenderType renderType,
                                  MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
            if (this.owner.slot() != EquipmentSlot.CHEST || !"armorBody".equals(bone.getName()) || bone.isHidden())
                return;
            float t = this.owner.seqTime();
            if (t < 0)
                return;
            float g0 = 0.30f, g1 = 1.0f, g2 = 0.55f;
            poseStack.pushPose();
            // body bone space (model units / 16); just in front of the chest plate
            poseStack.translate(0, 19.4f / 16f, -(3.4f / 16f));
            // three expanding double-ring pulses bursting out of the chest
            for (int i = 0; i < 3; i++) {
                float start = HenshinTiming.FINALE_START + i * 9f;
                float dur = 26f;
                if (t < start || t > start + dur)
                    continue;
                float k = HenshinInstance.frac(t, start, start + dur);
                float grow = HenshinInstance.easeOutCubic(k);
                float a = (1f - k) * (1f - k) * (i == 0 ? 1f : 0.75f);
                float r = Mth.lerp(grow, 0.10f, 0.95f + i * 0.15f);
                poseStack.pushPose();
                poseStack.translate(0, 0, -grow * 0.25f);
                Matrix4f m = poseStack.last().pose();
                VertexConsumer vc = bufferSource.getBuffer(ZRenderTypes.circle(0, i % 2 == 0 ? 1.2f : -1.4f, 0.6f, 1f, false));
                HenshinRendererAccess.quad(vc, m, -r, -r, r, r, 0, 0, 1, 1, g0, g1, g2, a);
                poseStack.popPose();
            }
            poseStack.popPose();
        }
    }
}
