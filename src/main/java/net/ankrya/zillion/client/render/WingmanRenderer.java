package net.ankrya.zillion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.ankrya.zillion.client.model.WingmanModel;
import net.ankrya.zillion.client.model.WingmanVisual;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoObjectRenderer;
import software.bernie.geckolib.util.Color;
import software.bernie.geckolib.util.RenderUtil;

/** Direct drone drawing; caller owns placement, flight curve, orientation and buffer flushing. */
public final class WingmanRenderer extends GeoObjectRenderer<WingmanVisual> {
    private final WingmanVisual visual = new WingmanVisual();
    private final boolean attachment;
    private int attachmentIndex;
    private int color = 0xFFFFFFFF;

    private WingmanRenderer(boolean attachment) {
        super(new WingmanModel(attachment));
        this.attachment = attachment;
    }

    private static final class Instances {
        private static final WingmanRenderer DRONE = new WingmanRenderer(false);
        private static final WingmanRenderer ATTACHMENT = new WingmanRenderer(true);
    }

    /**
     * Draw original wingman.geo.json at supplied pose, centered on its authored pivot.
     * Units are blocks, +Y up, GeckoLib's converted model axes; original Z=25deg is retained.
     * Color is ARGB (0xFFFFFFFF = unchanged); time is continuous ticks, not seconds.
     * This method adds no world/entity/camera translation and never flushes buffers.
     */
    public static void renderDrone(PoseStack stack, MultiBufferSource buffers, int light, float time, int color) {
        Instances.DRONE.draw(stack, buffers, light, time, color, 0);
    }

    /**
     * Draw EXACT wingman1..5 armor geometry and its 128x128 UVs at a supplied pivot pose.
     * The pose already includes docking-bone rotation. This method does NOT apply it twice.
     * To match perfectly at docking: target.applyTo(stack), then call this with target.index().
     * During final approach, move/interpolate that pivot pose rather than the free-flight model.
     * The standalone model has different baked shape/orientation/UVs; crossfade to this mesh
     * before the final approach rather than swapping the standalone texture at the endpoint.
     */
    public static void renderDetached(PoseStack stack, MultiBufferSource buffers, int light,
            float time, int color, int index) {
        WingmanDocking.checkIndex(index);
        Instances.ATTACHMENT.draw(stack, buffers, light, time, color, index);
    }

    private void draw(PoseStack stack, MultiBufferSource buffers, int light, float time, int color, int index) {
        this.visual.setTime(time);
        this.color = color;
        this.attachmentIndex = index;
        RenderType type = RenderType.entityTranslucent(getTextureLocation(this.visual));
        // Explicit non-null buffer avoids 4.8.2 GeoObjectRenderer.render replacing the caller's source.
        render(stack, this.visual, buffers, type, buffers.getBuffer(type), light, 0);
    }

    @Override
    public long getInstanceId(WingmanVisual visual) { return this.attachment ? 2 : 1; }

    @Override
    public Color getRenderColor(WingmanVisual visual, float partialTick, int light) {
        return new Color(this.color);
    }

    @Override
    public void preRender(PoseStack stack, WingmanVisual visual, BakedGeoModel model,
            MultiBufferSource buffers, VertexConsumer vertices, boolean reRender,
            float partialTick, int light, int overlay, int color) {
        // Deliberately omit GeoObjectRenderer's block-centering translation (0.5,0.51,0.5).
        selectedBone(model).ifPresent(bone -> RenderUtil.translateAwayFromPivotPoint(stack, bone));
    }

    private java.util.Optional<GeoBone> selectedBone(BakedGeoModel model) {
        return model.getBone(this.attachment ? "wingman" + (this.attachmentIndex + 1) : "wingman");
    }

    @Override
    public void actuallyRender(PoseStack stack, WingmanVisual visual, BakedGeoModel model, RenderType type,
            MultiBufferSource buffers, VertexConsumer vertices, boolean reRender, float partialTick,
            int light, int overlay, int color) {
        selectedBone(model).ifPresent(bone -> {
            if (this.attachment) {
                // Never mutate the shared armor model's visibility/animation state.
                renderDetachedCubes(stack, bone, vertices, light, overlay, color);
            } else {
                renderRecursively(stack, visual, bone, type, buffers, vertices, true, partialTick, light, overlay, color);
            }
        });
    }

    private void renderDetachedCubes(PoseStack stack, GeoBone bone, VertexConsumer vertices,
            int light, int overlay, int color) {
        for (GeoCube cube : bone.getCubes()) {
            stack.pushPose();
            try {
                renderCube(stack, cube, vertices, light, overlay, color);
            } finally {
                stack.popPose();
            }
        }
        for (GeoBone child : bone.getChildBones()) {
            stack.pushPose();
            try {
                RenderUtil.prepMatrixForBone(stack, child);
                renderDetachedCubes(stack, child, vertices, light, overlay, color);
            } finally {
                stack.popPose();
            }
        }
    }
}
