package net.ankrya.zillion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ankrya.zillion.client.ClientTransformationState;
import net.ankrya.zillion.client.model.RiderArmorModel;
import net.ankrya.zillion.item.RiderArmorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.util.RenderUtil;

/** Armor renderer verified against the published GeckoLib 4.8.2 source artifact. */
public final class RiderArmorRenderer extends GeoArmorRenderer<RiderArmorItem> {
    private final boolean driver;
    private float transformationTick = -1;
    private boolean extraPass;

    public RiderArmorRenderer(boolean driver) {
        super(new RiderArmorModel(driver));
        this.driver = driver;
    }

    @Override
    public void preRender(PoseStack stack, RiderArmorItem item, BakedGeoModel baked,
            MultiBufferSource buffers, VertexConsumer vertices, boolean reRender,
            float partialTick, int light, int overlay, int color) {
        // Baked models are shared by ALL items/wearers. Reset every changed field per pass.
        for (GeoBone root : baked.topLevelBones()) reset(root);
        this.transformationTick = this.currentEntity == null ? -1
                : ClientTransformationState.ticks(this.currentEntity.getUUID(), partialTick);
        super.preRender(stack, item, baked, buffers, vertices, reRender, partialTick, light, overlay, color);
        // Also filter for GeoAnimatable wearers, which GeckoLib otherwise leaves unfiltered.
        applyBoneVisibilityBySlot(this.currentSlot);
        if (!this.driver) {
            for (int i = 0; i < 5; i++) {
                final int index = i;
                getGeoModel().getBone("wingman" + (i + 1)).ifPresent(bone -> bone.setHidden(
                        this.transformationTick >= 0 && this.transformationTick < WingmanDocking.dockTick(index)));
            }
        }
    }

    private static void reset(GeoBone bone) {
        var initial = bone.getInitialSnapshot();
        if (initial != null) {
            bone.updateRotation(initial.getRotX(), initial.getRotY(), initial.getRotZ());
            bone.updatePosition(initial.getOffsetX(), initial.getOffsetY(), initial.getOffsetZ());
            bone.updateScale(initial.getScaleX(), initial.getScaleY(), initial.getScaleZ());
        }
        bone.setHidden(false);
        for (GeoBone child : bone.getChildBones()) reset(child);
    }

    @Override
    protected void applyBoneVisibilityBySlot(EquipmentSlot slot) {
        if (this.driver) {
            // A belt in LEGS has no armor*Leg bones; vanilla/GeckoLib's LEGS filter hides it.
            setAllBonesVisible(false);
            setBoneVisible(this.body, slot == EquipmentSlot.LEGS);
            getGeoModel().getBone("bone").ifPresent(bone -> bone.setHidden(slot != EquipmentSlot.LEGS));
        } else {
            // FEET intentionally exposes armor*Boot, whose geometry is the full legs.
            super.applyBoneVisibilityBySlot(slot);
        }
    }

    @Override
    protected void applyBaseTransformations(HumanoidModel<?> base) {
        super.applyBaseTransformations(base);
        // Explicitly follow the legs even if a resource pack omits the empty armor*Leg bones.
        if (this.rightBoot != null) {
            RenderUtil.matchModelPartRot(base.rightLeg, this.rightBoot);
            this.rightBoot.updatePosition(base.rightLeg.x + 2, 12 - base.rightLeg.y, base.rightLeg.z);
        }
        if (this.leftBoot != null) {
            RenderUtil.matchModelPartRot(base.leftLeg, this.leftBoot);
            this.leftBoot.updatePosition(base.leftLeg.x - 2, 12 - base.leftLeg.y, base.leftLeg.z);
        }
    }

    @Override
    public void actuallyRender(PoseStack stack, RiderArmorItem item, BakedGeoModel baked,
            RenderType type, MultiBufferSource buffers, VertexConsumer vertices, boolean reRender,
            float partialTick, int light, int overlay, int color) {
        // Keep the physical driver visible throughout; the summoned suit begins at tick 84.
        if (!this.driver && this.transformationTick >= 0 && this.transformationTick < 84) return;
        super.actuallyRender(stack, item, baked, type, buffers, vertices, reRender, partialTick, light, overlay, color);
        if (reRender || this.extraPass) return;
        this.extraPass = true;
        try {
            RiderArmorModel armorModel = (RiderArmorModel)getGeoModel();
            var mask = armorModel.emissiveTexture();
            // Optional precise, transparent UV mask. Missing masks never render the missing texture.
            if (Minecraft.getInstance().getResourceManager().getResource(mask).isPresent()) {
                RenderType emissive = RenderType.eyes(mask);
                super.actuallyRender(stack, item, baked, emissive, buffers, buffers.getBuffer(emissive), true,
                        partialTick, LightTexture.FULL_BRIGHT, overlay, 0xFFFFFFFF);
            }
            if (!this.driver && this.transformationTick >= 84 && this.transformationTick < 150) {
                float fade = Math.min(1, (150 - this.transformationTick) / 16f);
                int alpha = Math.round(190 * fade);
                // The shader supplies scan lines/grid/fresnel, NOT a flat-tint-only overlay.
                // Fade is vertex alpha. The documented draw scope safely isolates per-wearer time.
                RenderType hologram = FxRenderTypes.hologram(getTextureLocation(item));
                if (buffers instanceof MultiBufferSource.BufferSource immediate) {
                    FxShaders.draw(immediate, hologram, this.transformationTick / 20f, 1f,
                            () -> super.actuallyRender(stack, item, baked, hologram, immediate,
                                    immediate.getBuffer(hologram), true, partialTick, LightTexture.FULL_BRIGHT,
                                    overlay, (alpha << 24) | 0x48FFAE));
                }
            }
        } finally {
            this.extraPass = false;
        }
    }

    @Override
    public void renderRecursively(PoseStack stack, RiderArmorItem item, GeoBone bone, RenderType type,
            MultiBufferSource buffers, VertexConsumer vertices, boolean reRender, float partialTick,
            int light, int overlay, int color) {
        // The driver detail is a separate root in the original asset. Apply the body's EXACT
        // full pivot transformation, rather than copying rotations around the detail's own pivot.
        boolean driverDetail = this.driver && bone.getName().equals("bone") && this.body != null;
        if (driverDetail) {
            stack.pushPose();
            RenderUtil.prepMatrixForBone(stack, this.body);
        }
        try {
            int index = WingmanDocking.index(bone.getName());
            if (!this.driver && !reRender && !this.extraPass && index >= 0
                    && WingmanDocking.slot(index) == this.currentSlot && this.currentEntity instanceof Player player) {
                stack.pushPose();
                try {
                    RenderUtil.prepMatrixForBone(stack, bone);
                    RenderUtil.translateToPivotPoint(stack, bone);
                    WingmanDocking.publish(player, index, stack, buffers, light, partialTick, this.transformationTick);
                } finally {
                    stack.popPose();
                }
            }
            super.renderRecursively(stack, item, bone, type, buffers, vertices, reRender, partialTick, light, overlay, color);
        } finally {
            if (driverDetail) stack.popPose();
        }
    }
}
