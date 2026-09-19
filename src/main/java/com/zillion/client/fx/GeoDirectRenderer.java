package com.zillion.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.util.RenderUtil;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Renders a baked GeckoLib model directly (no entity, no renderer singleton, no animation controller),
 * with externally supplied per-bone transforms. Used for the effect drones so they can be positioned in world space
 * every frame with perfect interpolation.
 */
public final class GeoDirectRenderer {
    private GeoDirectRenderer() {}

    /** Per-bone transform override: rotation (radians, GeckoLib sign convention), position (model units), scale. */
    public static final class BoneTransform {
        public float rotX, rotY, rotZ;
        public float posX, posY, posZ;
        public float scaleX = 1, scaleY = 1, scaleZ = 1;
    }

    @Nullable
    public static BakedGeoModel model(ResourceLocation geoPath) {
        return GeckoLibCache.getBakedModels().get(geoPath);
    }

    public static void render(BakedGeoModel model, PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int colour,
                              @Nullable Map<String, BoneTransform> transforms, @Nullable BiConsumer<GeoBone, PoseStack> boneHook) {
        for (GeoBone bone : model.topLevelBones())
            renderBone(bone, poseStack, buffer, light, overlay, colour, transforms, boneHook);
    }

    private static void renderBone(GeoBone bone, PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int colour,
                                   @Nullable Map<String, BoneTransform> transforms, @Nullable BiConsumer<GeoBone, PoseStack> boneHook) {
        poseStack.pushPose();
        BoneTransform t = transforms != null ? transforms.get(bone.getName()) : null;
        // same order as RenderUtil.prepMatrixForBone, but with our own animated values
        float px = t != null ? t.posX : 0, py = t != null ? t.posY : 0, pz = t != null ? t.posZ : 0;
        poseStack.translate(-px / 16f, py / 16f, pz / 16f);
        poseStack.translate(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f);
        float rx = bone.getRotX() + (t != null ? t.rotX : 0);
        float ry = bone.getRotY() + (t != null ? t.rotY : 0);
        float rz = bone.getRotZ() + (t != null ? t.rotZ : 0);
        if (rz != 0) poseStack.mulPose(new Quaternionf().rotationZ(rz));
        if (ry != 0) poseStack.mulPose(new Quaternionf().rotationY(ry));
        if (rx != 0) poseStack.mulPose(new Quaternionf().rotationX(rx));
        if (t != null && (t.scaleX != 1 || t.scaleY != 1 || t.scaleZ != 1))
            poseStack.scale(t.scaleX, t.scaleY, t.scaleZ);
        poseStack.translate(-bone.getPivotX() / 16f, -bone.getPivotY() / 16f, -bone.getPivotZ() / 16f);

        if (boneHook != null)
            boneHook.accept(bone, poseStack);

        for (GeoCube cube : bone.getCubes()) {
            poseStack.pushPose();
            renderCube(cube, poseStack, buffer, light, overlay, colour);
            poseStack.popPose();
        }
        for (GeoBone child : bone.getChildBones())
            renderBone(child, poseStack, buffer, light, overlay, colour, transforms, boneHook);
        poseStack.popPose();
    }

    public static void renderCube(GeoCube cube, PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int colour) {
        RenderUtil.translateToPivotPoint(poseStack, cube);
        RenderUtil.rotateMatrixAroundCube(poseStack, cube);
        RenderUtil.translateAwayFromPivotPoint(poseStack, cube);
        Matrix3f normalMat = poseStack.last().normal();
        Matrix4f pose = poseStack.last().pose();
        Vector3f normal = new Vector3f();
        Vector4f pos = new Vector4f();
        for (GeoQuad quad : cube.quads()) {
            if (quad == null)
                continue;
            normalMat.transform(normal.set(quad.normal()));
            RenderUtil.fixInvertedFlatCube(cube, normal);
            for (GeoVertex vertex : quad.vertices()) {
                Vector3f p = vertex.position();
                pose.transform(pos.set(p.x(), p.y(), p.z(), 1f));
                buffer.addVertex(pos.x(), pos.y(), pos.z(), colour, vertex.texU(), vertex.texV(), overlay, light, normal.x(), normal.y(), normal.z());
            }
        }
    }

    /**
     * Renders one bone (and its children) with the bone pivot placed at the current pose origin,
     * ignoring the bone's own base rotation / position. Used for free-flying drones.
     */
    public static void renderBoneCentered(GeoBone bone, PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int colour) {
        poseStack.pushPose();
        poseStack.translate(-bone.getPivotX() / 16f, -bone.getPivotY() / 16f, -bone.getPivotZ() / 16f);
        for (GeoCube cube : bone.getCubes()) {
            poseStack.pushPose();
            renderCube(cube, poseStack, buffer, light, overlay, colour);
            poseStack.popPose();
        }
        for (GeoBone child : bone.getChildBones())
            renderBone(child, poseStack, buffer, light, overlay, colour, null, null);
        poseStack.popPose();
    }
}
