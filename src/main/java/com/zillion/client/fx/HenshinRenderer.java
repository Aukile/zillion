package com.zillion.client.fx;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zillion.Zillion;
import com.zillion.client.ClientHenshinHandler;
import com.zillion.client.render.ZRenderTypes;
import com.zillion.henshin.HenshinTiming;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * World-space transformation VFX (everything that is not part of the armor model itself):
 * back emblem, ground rings, drones, data bits, whirlwind ribbons, scarf energy, shockwaves.
 * Also hides the vanilla player model while Gazer Zero armor is worn.
 */
public final class HenshinRenderer {
    private HenshinRenderer() {}

    public static final ResourceLocation EMBLEM_TEX = Zillion.id("textures/gazerzeroeffect.png");
    public static final ResourceLocation DRONE_TEX = Zillion.id("textures/gazerzero_effect_drone.png");

    private static final Vector3f GREEN = new Vector3f(0.30f, 1.0f, 0.50f);
    private static final Vector3f GREEN_SOFT = new Vector3f(0.45f, 1.0f, 0.65f);
    private static final Vector3f RED = new Vector3f(1.0f, 0.18f, 0.20f);
    private static final Vector3f WHITE = new Vector3f(1f, 1f, 1f);

    /** Vertical offset vanilla applies to the 0.9375-scaled player model (1.501 - 1.5 * 0.9375). */
    public static final float MODEL_Y_OFFSET = 1.501f - 1.5f * 0.9375f;

    private static MultiBufferSource.BufferSource buffers;

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null)
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));
        return buffers;
    }

    // ------------------------------------------------------------------ player model hiding
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        if (ClientHenshinHandler.shouldHidePlayerModel(player)) {
            // the armor layer re-enables what it needs through copyPropertiesTo / setPartVisibility
            event.getRenderer().getModel().setAllVisible(false);
        }
    }

    // ------------------------------------------------------------------ world VFX
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource src = buffers();
        boolean any = false;
        for (LivingEntity entity : mc.level.getEntitiesOfClass(LivingEntity.class, camera.getEntity().getBoundingBox().inflate(96))) {
            HenshinInstance inst = ClientHenshinHandler.get(entity);
            if (inst == null)
                continue;
            any = true;
            renderInstance(inst, poseStack, src, camera, cam, pt);
        }
        if (any)
            src.endBatch();
    }

    private static void renderInstance(HenshinInstance inst, PoseStack poseStack, MultiBufferSource.BufferSource src, Camera camera, Vec3 cam, float pt) {
        float t = inst.time(pt);
        Vec3 origin = inst.origin(pt);
        float yaw = inst.bodyYaw(pt);
        Vector3f off = new Vector3f((float) (origin.x - cam.x), (float) (origin.y - cam.y), (float) (origin.z - cam.z));

        poseStack.pushPose();
        poseStack.translate(off.x, off.y, off.z);

        renderEmblem(inst, t, yaw, poseStack, src);
        renderGroundRing(t, poseStack, src);
        renderWind(inst, t, off, poseStack, src);
        renderDataBits(inst, pt, camera, poseStack, src);
        renderDrones(inst, t, yaw, camera, poseStack, src);
        renderScarfEnergy(inst, t, yaw, off, poseStack, src);
        renderFinaleFlash(t, poseStack, src, camera);

        poseStack.popPose();
    }

    // ------------------------------------------------------------------ emblem
    /** Emblem height (blocks above the feet) at time t. */
    private static float emblemY(float t) {
        if (t < HenshinTiming.EMBLEM_RISE_END) {
            float k = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.EMBLEM_GROW_END - 8, HenshinTiming.EMBLEM_RISE_END));
            return Mth.lerp(k, 1.0f, HenshinTiming.EMBLEM_TOP_Y);
        }
        // sink from above the head down to the feet, easing in then steady
        float k = HenshinInstance.frac(t, HenshinTiming.EMBLEM_RISE_END, HenshinTiming.EMBLEM_DESCEND_END);
        k = HenshinInstance.easeInOutSine(k);
        return Mth.lerp(k, HenshinTiming.EMBLEM_TOP_Y, HenshinTiming.EMBLEM_BOTTOM_Y);
    }

    private static void renderEmblem(HenshinInstance inst, float t, float yaw, PoseStack poseStack, MultiBufferSource src) {
        float a = HenshinInstance.window(t, HenshinTiming.EMBLEM_START, HenshinTiming.EMBLEM_DESCEND_END, 6f, 6f);
        if (a <= 0f)
            return;
        // phase 1: grow behind the back (vertical, facing the camera-ish / player's back)
        float grow = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.EMBLEM_START, HenshinTiming.EMBLEM_GROW_END));
        float size = Mth.lerp(grow, 0.3f, 2.0f) * (1f + 0.03f * Mth.sin(t * 0.6f));
        // phase 2: move up above the head while flipping to horizontal
        float flip = HenshinInstance.smooth(HenshinInstance.frac(t, HenshinTiming.EMBLEM_GROW_END - 4, HenshinTiming.EMBLEM_RISE_END - 4));
        float pitch = Mth.lerp(flip, 0f, -90f);   // tilt away from the body (top edge swings backwards) so it never clips the player
        float y = emblemY(t);
        // behind the back at first (z > 0 in body space), centred on the player once it is flat
        float zBack = Mth.lerp(flip, 0.45f, 0f);
        // phase 3: while sinking it shrinks slightly to hug the body and spins faster
        float sink = HenshinInstance.frac(t, HenshinTiming.EMBLEM_RISE_END, HenshinTiming.EMBLEM_DESCEND_END);
        size *= Mth.lerp(sink, 1f, 0.8f);
        float spin = t * 1.2f + sink * sink * 90f;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        poseStack.translate(0, y, zBack);
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(spin));
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = src.getBuffer(ZRenderTypes.GLOW_TEX.apply(EMBLEM_TEX));
        float h = size / 2f;
        float r = GREEN.x, g = GREEN.y, b = GREEN.z;
        quad(vc, m, -h, -h, h, h, 0, 0, 1, 1, r, g, b, a);
        float h2 = h * 1.06f;
        quad(vc, m, -h2, -h2, h2, h2, 0, 0, 1, 1, r, g, b, a * 0.35f);
        poseStack.popPose();

        // while sinking, a faint scan ring follows the emblem around the body
        if (sink > 0f && sink < 1f) {
            poseStack.pushPose();
            poseStack.translate(0, y, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            Matrix4f m2 = poseStack.last().pose();
            VertexConsumer vc2 = src.getBuffer(ZRenderTypes.circle(2, 1f, 1f, 1f, false));
            float rr = 0.75f;
            quad(vc2, m2, -rr, -rr, rr, rr, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, a * 0.5f * Mth.sin(sink * Mth.PI));
            poseStack.popPose();
        }

        // a flare at the centre when it first appears
        float flare = HenshinInstance.window(t, HenshinTiming.EMBLEM_START, HenshinTiming.EMBLEM_START + 16, 3f, 8f);
        if (flare > 0)
            billboard(poseStack, src, ZRenderTypes.circle(1, 1f, 1f, 1f, true), new Vector3f(0, 1.0f, 0), 0.9f * flare + 0.3f, WHITE, flare * 0.9f, cameraRotation());
        // flash when the emblem reaches the feet and vanishes
        float end = HenshinInstance.window(t, HenshinTiming.EMBLEM_DESCEND_END - 4, HenshinTiming.EMBLEM_DESCEND_END + 10, 2f, 8f);
        if (end > 0) {
            poseStack.pushPose();
            poseStack.translate(0, 0.04f, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            Matrix4f m3 = poseStack.last().pose();
            VertexConsumer vc3 = src.getBuffer(ZRenderTypes.circle(2, 1f, 1f, 1f, true));
            float k = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.EMBLEM_DESCEND_END - 4, HenshinTiming.EMBLEM_DESCEND_END + 10));
            float rr = Mth.lerp(k, 0.6f, 2.2f);
            quad(vc3, m3, -rr, -rr, rr, rr, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, end * 0.8f);
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------ ground rings
    private static void renderGroundRing(float t, PoseStack poseStack, MultiBufferSource src) {
        float a = HenshinInstance.window(t, HenshinTiming.GROUND_RING, HenshinTiming.GROUND_RING + 26, 2f, 12f);
        if (a > 0f) {
            float k = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.GROUND_RING, HenshinTiming.GROUND_RING + 26));
            float radius = Mth.lerp(k, 0.4f, 3.2f);
            poseStack.pushPose();
            poseStack.translate(0, 0.03f, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            Matrix4f m = poseStack.last().pose();
            VertexConsumer vc = src.getBuffer(ZRenderTypes.circle(2, 1f, 1f, 1f, true));
            quad(vc, m, -radius, -radius, radius, radius, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, a);
            poseStack.popPose();
        }
        // a second, tighter ring that stays around the feet while the drones spawn
        float a2 = HenshinInstance.window(t, HenshinTiming.DRONE_SPAWN - 2, HenshinTiming.CONTRACT_END, 6f, 10f);
        if (a2 > 0) {
            poseStack.pushPose();
            poseStack.translate(0, 0.02f, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            poseStack.mulPose(Axis.ZP.rotationDegrees(t * 1.5f));
            Matrix4f m2 = poseStack.last().pose();
            VertexConsumer vc2 = src.getBuffer(ZRenderTypes.circle(0, 1f, 0.5f, 1f, true));
            float rr = 1.1f + 0.05f * Mth.sin(t * 0.3f);
            quad(vc2, m2, -rr, -rr, rr, rr, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, a2 * 0.55f);
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------ whirlwind
    private static void renderWind(HenshinInstance inst, float t, Vector3f off, PoseStack poseStack, MultiBufferSource src) {
        if (t < HenshinTiming.WIND_START || t > HenshinTiming.CONTRACT_END + 16)
            return;
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = src.getBuffer(ZRenderTypes.ENERGY);
        int segments = 26;
        Vector3f[] pts = new Vector3f[segments + 1];
        for (int i = 0; i <= segments; i++)
            pts[i] = new Vector3f();
        // camera position relative to the entity origin (so view vectors are correct in local space)
        Vector3f camLocal = new Vector3f(off).negate();

        // --- curved energy lines (green + red), each a crisp core with a soft halo ---------------------
        for (HenshinInstance.WindLine line : inst.windLines) {
            float alpha = line.alpha(t);
            if (alpha <= 0.002f)
                continue;
            for (int i = 0; i <= segments; i++)
                line.point(i / (float) segments, t, pts[i]);
            Vector3f col = line.red ? RED : GREEN;
            float contraction = line.contraction(t);
            float w = line.width * line.thickness() * (0.8f + 0.7f * (1f - contraction));
            ribbon(vc, m, pts, w, col, alpha, camLocal, 0f);
            ribbon(vc, m, pts, w * 3.2f, col, alpha * 0.16f, camLocal, 0.005f);
        }

        // --- inner green airflow: a tight helical column of broad translucent sheets around the body ------
        float flowA = HenshinInstance.window(t, HenshinTiming.WIND_START + 4, HenshinTiming.CONTRACT_END + 10, 14f, 10f);
        if (flowA > 0) {
            float contraction = 1f - HenshinInstance.easeInCubic((t - HenshinTiming.CONTRACT_START) / (HenshinTiming.CONTRACT_END - HenshinTiming.CONTRACT_START)) * 0.55f;
            int strands = 8;
            for (int sIdx = 0; sIdx < strands; sIdx++) {
                float dir = sIdx % 2 == 0 ? 1f : -1f;
                float phase = sIdx * Mth.TWO_PI / strands + t * 0.11f * dir;
                float baseR = (0.55f + 0.12f * (sIdx % 3)) * contraction;
                for (int i = 0; i <= segments; i++) {
                    float u = i / (float) segments;
                    float ang = phase + u * 3.4f * dir;
                    float rad = baseR + 0.08f * Mth.sin(u * 7f + t * 0.25f + sIdx);
                    float y = -0.05f + u * 2.15f + 0.05f * Mth.sin(t * 0.2f + sIdx * 1.7f);
                    pts[i].set(Mth.cos(ang) * rad, y, Mth.sin(ang) * rad);
                }
                // wide soft sheet + a thin brighter streak on top for definition
                ribbon(vc, m, pts, 0.26f, GREEN_SOFT, flowA * 0.10f, camLocal, 0.0f);
                ribbon(vc, m, pts, 0.05f, GREEN_SOFT, flowA * 0.35f, camLocal, 0.0f);
            }
            // rising vertical wisps in the very centre (airflow going up along the body)
            for (int w = 0; w < 6; w++) {
                float ph = w * 1.05f + t * 0.06f;
                float rr = 0.38f * contraction;
                float speed = 0.09f + 0.02f * (w % 3);
                float baseY = ((t * speed + w * 0.37f) % 1.0f) * 2.2f - 0.1f;
                for (int i = 0; i <= segments; i++) {
                    float u = i / (float) segments;
                    float y = baseY + u * 0.6f;
                    float ang = ph + y * 2.2f;
                    pts[i].set(Mth.cos(ang) * rr, y, Mth.sin(ang) * rr);
                }
                float fade = Mth.sin(Mth.clamp(baseY / 2.1f, 0f, 1f) * Mth.PI);
                ribbon(vc, m, pts, 0.09f, GREEN_SOFT, flowA * 0.28f * fade, camLocal, 0.0f);
            }
        }

        // --- broad outer airflow sheets: slow, low alpha, give the whirlwind body --------------------------
        float sheetA = HenshinInstance.window(t, HenshinTiming.WIND_START + 10, HenshinTiming.CONTRACT_END + 8, 16f, 10f);
        if (sheetA > 0) {
            for (int s = 0; s < 6; s++) {
                float phase = s * Mth.TWO_PI / 6f + t * 0.045f * (s % 2 == 0 ? 1 : -1);
                float contraction = 1f - HenshinInstance.easeInCubic((t - HenshinTiming.CONTRACT_START) / (HenshinTiming.CONTRACT_END - HenshinTiming.CONTRACT_START)) * 0.8f;
                for (int i = 0; i <= segments; i++) {
                    float u = i / (float) segments;
                    float ang = phase + u * 2.4f;
                    float rad = (1.35f + 0.25f * Mth.sin(u * 5f + t * 0.1f)) * contraction;
                    float y = 0.1f + u * 1.9f + 0.1f * Mth.sin(t * 0.15f + s);
                    pts[i].set(Mth.cos(ang) * rad, y, Mth.sin(ang) * rad);
                }
                ribbon(vc, m, pts, 0.32f, GREEN_SOFT, sheetA * 0.10f, camLocal, 0.03f);
            }
        }
    }

    // ------------------------------------------------------------------ data bits
    private static void renderDataBits(HenshinInstance inst, float pt, Camera camera, PoseStack poseStack, MultiBufferSource src) {
        if (inst.dataBits.isEmpty())
            return;
        Quaternionf rot = camera.rotation();
        float yaw = inst.bodyYaw(pt);
        // data bits live in dock space (they are spawned from drone poses)
        poseStack.pushPose();
        dockSpace(poseStack, yaw);
        Quaternionf face = new Quaternionf().rotationY((float) Math.toRadians(-(180f - yaw))).mul(rot);
        VertexConsumer vc = src.getBuffer(ZRenderTypes.circle(1, 0f, 0f, 1f, true));
        for (HenshinInstance.DataBit bit : inst.dataBits) {
            float life = (bit.age + pt) / bit.life;
            float a = (1f - life) * 0.9f;
            float x = Mth.lerp(pt, bit.px, bit.x), y = Mth.lerp(pt, bit.py, bit.y), z = Mth.lerp(pt, bit.pz, bit.z);
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            poseStack.mulPose(face);
            Matrix4f m = poseStack.last().pose();
            float s = bit.size * 3f;
            quad(vc, m, -s, -s, s, s, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, a);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ drones
    private static void renderDrones(HenshinInstance inst, float t, float yaw, Camera camera, PoseStack poseStack, MultiBufferSource src) {
        if (t < HenshinTiming.DRONE_SPAWN)
            return;
        BakedGeoModel model = GeoDirectRenderer.model(Drone.GEO);
        if (model == null)
            return;
        poseStack.pushPose();
        dockSpace(poseStack, yaw);
        Quaternionf camRot = new Quaternionf().rotationY((float) Math.toRadians(-(180f - yaw))).mul(camera.rotation());

        for (Drone drone : inst.drones) {
            if (!drone.isVisible(t))
                continue;
            GeoBone bone = model.getBone(drone.boneName).orElse(null);
            if (bone == null)
                continue;
            drone.evaluate(bone, t, yaw);
            float white = drone.whiteFactor(t);
            float spawn = drone.spawnFactor(t);
            // the drone hands over to the real wingman bone the moment it slams in
            float endFade = t < HenshinTiming.DOCK_END - 0.5f ? 1f : 0f;
            if (endFade <= 0)
                continue;

            poseStack.pushPose();
            poseStack.translate(drone.currentPos.x, drone.currentPos.y, drone.currentPos.z);
            poseStack.mulPose(drone.currentRot);
            poseStack.scale(drone.currentScale.x, drone.currentScale.y, drone.currentScale.z);

            // green outline shell (slightly enlarged, translucent hologram green)
            {
                poseStack.pushPose();
                float sh = 1.14f;
                poseStack.scale(sh, sh, sh);
                RenderType shell = ZRenderTypes.hologram(DRONE_TEX, new ZRenderTypes.HoloParams(1f, 0.30f * endFade * (1f - white * 0.5f), 0f, 0f, 1f, 0f, 0.6f));
                GeoDirectRenderer.renderBoneCentered(bone, poseStack, src.getBuffer(shell), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
                poseStack.popPose();
            }
            // body
            RenderType body = ZRenderTypes.hologram(DRONE_TEX, new ZRenderTypes.HoloParams(0f, endFade, 0f, 0f, 1f, white, 0.4f));
            GeoDirectRenderer.renderBoneCentered(bone, poseStack, src.getBuffer(body), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
            poseStack.popPose();

            // spawn glow flare
            if (white > 0.01f)
                billboard(poseStack, src, ZRenderTypes.circle(1, 0.5f, 0.5f, 1f, true), drone.currentPos, 0.55f * spawn + 0.3f * white, WHITE, white * 0.95f * endFade, camRot);
            // subtle constant green glow around the drone; brighter while charging the slam
            float charge = HenshinInstance.window(t, HenshinTiming.DOCK_START + HenshinTiming.DOCK_APPROACH, HenshinTiming.DOCK_END, 4f, 0f);
            billboard(poseStack, src, ZRenderTypes.circle(1, 0f, 0f, 1f, true), drone.currentPos, 0.45f + 0.25f * charge, GREEN, (0.35f + 0.5f * charge) * (1f - white) * endFade, camRot);
        }
        // impact: each slot flashes when the drones dock
        float impact = HenshinInstance.window(t, HenshinTiming.DOCK_END - 0.5f, HenshinTiming.DOCK_END + 9, 0.5f, 7f);
        if (impact > 0) {
            float k = HenshinInstance.frac(t, HenshinTiming.DOCK_END, HenshinTiming.DOCK_END + 9);
            for (Drone drone : inst.drones) {
                GeoBone bone = model.getBone(drone.boneName).orElse(null);
                if (bone == null)
                    continue;
                Vector3f p = new Vector3f(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f);
                billboard(poseStack, src, ZRenderTypes.circle(1, 0f, 0f, 1f, false), p, 0.35f + 0.6f * k, WHITE, impact * 0.9f, camRot);
                billboard(poseStack, src, ZRenderTypes.circle(2, 1f, 1f, 1f, false), p, 0.2f + 1.1f * HenshinInstance.easeOutCubic(k), GREEN, impact * 0.7f, camRot);
            }
        }
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ energy scarf
    /**
     * Before the real scarf model appears, an "energy scarf" made of red ribbons flutters down the front-right of the chest (where the model scarf tail hangs):
     * it unfurls from the collar, waves in the wind, then snaps into the strip shape and hands over to the model.
     */
    private static void renderScarfEnergy(HenshinInstance inst, float t, float yaw, Vector3f off, PoseStack poseStack, MultiBufferSource src) {
        float a = HenshinInstance.window(t, HenshinTiming.SCARF_FX_START, HenshinTiming.SCARF_APPEAR + 6, 4f, 5f);
        if (a <= 0f)
            return;
        poseStack.pushPose();
        dockSpace(poseStack, yaw);
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = src.getBuffer(ZRenderTypes.ENERGY);
        Vector3f camLocal = new Vector3f(off).negate();
        camLocal.rotateY((float) Math.toRadians(-(180f - yaw)));
        camLocal.y -= MODEL_Y_OFFSET;
        camLocal.div(0.9375f);
        int segments = 16;
        Vector3f[] pts = new Vector3f[segments + 1];
        for (int i = 0; i <= segments; i++)
            pts[i] = new Vector3f();

        float unfurl = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.SCARF_FX_START, HenshinTiming.SCARF_FX_START + 10));
        float settle = HenshinInstance.smooth(HenshinInstance.frac(t, HenshinTiming.SCARF_APPEAR - 10, HenshinTiming.SCARF_APPEAR));
        // scarf model (bone6): collar ring around the neck at y ~22/16 and a tail hanging down the FRONT on the
        // player's right side (model x = +2..+5 -> dock x = -0.13..-0.30, z = -2.9/16), from y = 22/16 down to ~17/16.
        float collarY = 22.4f / 16f;
        float tailX = -0.20f, tailZ = -0.20f;
        int strands = 11;
        for (int s = 0; s < strands; s++) {
            float f = (s - (strands - 1) / 2f) / ((strands - 1) / 2f);       // -1..1 across the tail width
            float wave = t * 0.42f + s * 0.9f;
            float len = (0.48f + 0.10f * Math.abs(f)) * unfurl;
            for (int i = 0; i <= segments; i++) {
                float u = i / (float) segments;
                // free flutter: falls down the front-right of the chest, whipped forwards / sideways by the wind
                float fx = tailX + f * 0.05f - u * 0.10f + Mth.sin(wave + u * 5f) * 0.09f * u;
                float fy = collarY - u * len + Mth.sin(wave * 1.3f + u * 6f + f) * 0.05f * u;
                float fz = tailZ - u * 0.30f * unfurl - u * u * 0.12f + Mth.cos(wave * 0.8f + u * 3f) * 0.06f * u;
                // settled: hugging the hanging tail strip of the model
                float sx = tailX + f * 0.045f - u * 0.06f;
                float sy = collarY - u * 0.34f;
                float sz = tailZ + 0.01f * Mth.sin(u * 3f + s);
                pts[i].set(Mth.lerp(settle, fx, sx), Mth.lerp(settle, fy, sy), Mth.lerp(settle, fz, sz));
            }
            float bright = 0.75f + 0.25f * Mth.sin(t * 0.5f + s);
            ribbon(vc, m, pts, 0.045f, RED, a * bright, camLocal, 0f);
            ribbon(vc, m, pts, 0.14f, RED, a * 0.22f, camLocal, 0.004f);
        }
        // collar strands: short ribbons wrapping around the neck ring, so the whole scarf (not just the tail) forms
        for (int s = 0; s < 6; s++) {
            float ang0 = s * (Mth.TWO_PI / 6f);
            for (int i = 0; i <= segments; i++) {
                float u = i / (float) segments;
                float ang = ang0 + u * (Mth.TWO_PI / 6f) * 1.15f + Mth.sin(t * 0.3f + s) * 0.1f * (1f - settle);
                float rad = Mth.lerp(settle, 0.26f + 0.04f * Mth.sin(t * 0.5f + u * 4f), 0.21f);
                pts[i].set(Mth.cos(ang) * rad, collarY + Mth.lerp(settle, Mth.sin(t * 0.4f + ang * 2f) * 0.03f, 0f), Mth.sin(ang) * rad * 0.8f);
            }
            ribbon(vc, m, pts, 0.04f, RED, a * 0.8f * unfurl, camLocal, 0f);
            ribbon(vc, m, pts, 0.11f, RED, a * 0.18f * unfurl, camLocal, 0.004f);
        }
        // red flare at the scarf tail at the moment the scarf model appears
        float flare = HenshinInstance.window(t, HenshinTiming.SCARF_APPEAR - 3, HenshinTiming.SCARF_APPEAR + 12, 2f, 9f);
        if (flare > 0)
            billboard(poseStack, src, ZRenderTypes.circle(1, 0f, 0f, 1f, false), new Vector3f(tailX, collarY - 0.1f, tailZ), 0.8f, RED, flare,
                    new Quaternionf().rotationY((float) Math.toRadians(-(180f - yaw))).mul(cameraRotation()));
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ shockwaves
    private static void renderFinaleFlash(float t, PoseStack poseStack, MultiBufferSource src, Camera camera) {
        // armor materialisation shockwave
        float a = HenshinInstance.window(t, HenshinTiming.ARMOR_TICK, HenshinTiming.ARMOR_TICK + 18, 1f, 10f);
        if (a > 0) {
            float k = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.ARMOR_TICK, HenshinTiming.ARMOR_TICK + 18));
            poseStack.pushPose();
            poseStack.translate(0, 1.0f, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            Matrix4f m = poseStack.last().pose();
            VertexConsumer vc = src.getBuffer(ZRenderTypes.circle(2, 1f, 1f, 1f, true));
            float r = Mth.lerp(k, 0.3f, 2.6f);
            quad(vc, m, -r, -r, r, r, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, a);
            poseStack.popPose();
            billboard(poseStack, src, ZRenderTypes.circle(1, 0f, 0f, 1f, true), new Vector3f(0, 1.0f, 0), 1.6f * (1f - k) + 0.4f, WHITE, a * 0.8f, camera.rotation());
        }
        // final lock-in pulse
        float b = HenshinInstance.window(t, HenshinTiming.FINALE_END - 6, HenshinTiming.END_TICK + 6, 2f, 10f);
        if (b > 0) {
            float k = HenshinInstance.easeOutCubic(HenshinInstance.frac(t, HenshinTiming.FINALE_END - 6, HenshinTiming.END_TICK + 6));
            poseStack.pushPose();
            poseStack.translate(0, 0.05f, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            Matrix4f m = poseStack.last().pose();
            VertexConsumer vc = src.getBuffer(ZRenderTypes.circle(2, 1f, 1f, 1f, true));
            float r = Mth.lerp(k, 0.5f, 4.0f);
            quad(vc, m, -r, -r, r, r, 0, 0, 1, 1, GREEN.x, GREEN.y, GREEN.z, b * 0.8f);
            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------ spaces & primitives

    /**
     * Enter "dock space": the exact frame the GeckoLib armor model geometry ends up in
     * (entity origin, rotated by 180 - bodyYaw, vanilla model offset, scaled 0.9375).
     */
    static void dockSpace(PoseStack poseStack, float yaw) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        poseStack.translate(0, MODEL_Y_OFFSET, 0);
        poseStack.scale(0.9375f, 0.9375f, 0.9375f);
    }

    private static Quaternionf cameraRotation() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
    }

    /** Camera-facing quad of the given render type at a local position. */
    private static void billboard(PoseStack poseStack, MultiBufferSource src, RenderType type, Vector3f pos, float size, Vector3f col, float alpha, Quaternionf rot) {
        if (alpha <= 0.002f)
            return;
        poseStack.pushPose();
        poseStack.translate(pos.x, pos.y, pos.z);
        poseStack.mulPose(rot);
        Matrix4f m = poseStack.last().pose();
        VertexConsumer vc = src.getBuffer(type);
        float h = size / 2f;
        quad(vc, m, -h, -h, h, h, 0, 0, 1, 1, col.x, col.y, col.z, alpha);
        poseStack.popPose();
    }

    static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
        vc.addVertex(m, x0, y0, 0).setUv(u0, v1).setColor(r, g, b, a);
        vc.addVertex(m, x1, y0, 0).setUv(u1, v1).setColor(r, g, b, a);
        vc.addVertex(m, x1, y1, 0).setUv(u1, v0).setColor(r, g, b, a);
        vc.addVertex(m, x0, y1, 0).setUv(u0, v0).setColor(r, g, b, a);
    }

    /**
     * Camera-facing ribbon through {@code pts} (local space). {@code camLocal} is the camera position in the same space.
     * u runs along the ribbon (0..1), v across (0..1) - the energy shader uses that for the core/glow falloff.
     */
    static void ribbon(VertexConsumer vc, Matrix4f m, Vector3f[] pts, float width, Vector3f col, float alpha, Vector3f camLocal, float lift) {
        if (alpha <= 0.002f)
            return;
        int n = pts.length;
        Vector3f tangent = new Vector3f(), view = new Vector3f(), side = new Vector3f();
        Vector3f[] sides = new Vector3f[n];
        for (int i = 0; i < n; i++) {
            Vector3f p = pts[i];
            if (i == 0) tangent.set(pts[1]).sub(p);
            else if (i == n - 1) tangent.set(p).sub(pts[n - 2]);
            else tangent.set(pts[i + 1]).sub(pts[i - 1]);
            view.set(camLocal).sub(p);
            tangent.cross(view, side);
            if (side.lengthSquared() < 1e-8f)
                side.set(0, 1, 0);
            side.normalize(width * 0.5f);
            sides[i] = new Vector3f(side);
        }
        for (int i = 0; i < n - 1; i++) {
            float u0 = i / (float) (n - 1), u1 = (i + 1) / (float) (n - 1);
            Vector3f a = pts[i], b = pts[i + 1], sa = sides[i], sb = sides[i + 1];
            vc.addVertex(m, a.x - sa.x, a.y - sa.y + lift, a.z - sa.z).setUv(u0, 0).setColor(col.x, col.y, col.z, alpha);
            vc.addVertex(m, a.x + sa.x, a.y + sa.y + lift, a.z + sa.z).setUv(u0, 1).setColor(col.x, col.y, col.z, alpha);
            vc.addVertex(m, b.x + sb.x, b.y + sb.y + lift, b.z + sb.z).setUv(u1, 1).setColor(col.x, col.y, col.z, alpha);
            vc.addVertex(m, b.x - sb.x, b.y - sb.y + lift, b.z - sb.z).setUv(u1, 0).setColor(col.x, col.y, col.z, alpha);
        }
    }
}
