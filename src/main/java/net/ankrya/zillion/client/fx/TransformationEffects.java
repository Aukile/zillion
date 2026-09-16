package net.ankrya.zillion.client.fx;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.ankrya.zillion.Config;
import net.ankrya.zillion.Zillion;
import net.ankrya.zillion.client.ClientTransformationState;
import net.ankrya.zillion.client.render.BloomPipeline;
import net.ankrya.zillion.client.render.FxRenderTypes;
import net.ankrya.zillion.client.render.FxShaders;
import net.ankrya.zillion.client.render.WingmanDocking;
import net.ankrya.zillion.client.render.WingmanRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static net.ankrya.zillion.client.fx.FxMath.*;

/** One world pass, no simulated/networked entities and no frame-dependent random walks. */
@EventBusSubscriber(modid = Zillion.MODID, value = Dist.CLIENT)
public final class TransformationEffects {
    private static final ResourceLocation DRONE_TEXTURE = ResourceLocation.fromNamespaceAndPath(Zillion.MODID, "textures/item/entity/wingman.png");
    private static final ResourceLocation ARMOR_TEXTURE = ResourceLocation.fromNamespaceAndPath(Zillion.MODID, "textures/item/armor/gazerzero.png");
    private static ByteBufferBuilder storage;
    private static MultiBufferSource.BufferSource buffers;
    private static long frame;
    private TransformationEffects() {}

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            storage = new ByteBufferBuilder(262144);
            buffers = MultiBufferSource.immediate(storage);
        }
        return buffers;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            WingmanDocking.beginFrame(++frame);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        List<FramePlayer> visible = new ArrayList<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            float ticks = ClientTransformationState.ticks(player.getUUID(), partial);
            if (ticks < 0 || ticks >= DURATION || player.isSpectator() || player.isInvisible()) continue;
            if (player.distanceToSqr(camera) > (double) Config.effectDistance * Config.effectDistance) continue;
            if (!event.getFrustum().isVisible(player.getBoundingBox().inflate(2.5))) continue;
            Vec3 position = player.getPosition(partial);
            float yaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);
            PoseStack pose = new PoseStack();
            pose.mulPose(event.getModelViewMatrix());
            pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
            pose.mulPose(Axis.YP.rotationDegrees(-yaw));
            Vec3 eye = camera.subtract(position).yRot(yaw * Mth.DEG_TO_RAD);
            boolean first = player == mc.player && mc.options.getCameraType().isFirstPerson();
            float brightness = first ? (float) Config.firstPersonIntensity : 1;
            visible.add(new FramePlayer(player, pose, eye, ticks, partial, brightness,
                    ClientTransformationState.seed(player.getUUID()), first));
        }
        if (visible.isEmpty()) return;
        MultiBufferSource.BufferSource source = buffers();
        try {
            for (FramePlayer player : visible) drones(player, source);
            source.endBatch();
            BloomPipeline.capture(source, () -> {
                for (FramePlayer player : visible) energy(player, source);
            });
        } finally {
            source.endBatch();
        }
    }

    private static void energy(FramePlayer f, MultiBufferSource.BufferSource source) {
        FxGeometry g = new FxGeometry(source.getBuffer(FxRenderTypes.energy()), f.pose.last().pose(), f.eye, f.brightness);
        FxPatterns.sigil(g, f.ticks);
        FxPatterns.vortex(g, f.ticks, f.seed);
        Vec3 chest = new Vec3(0, f.player.isCrouching() ? 1.08 : 1.25, 0.38);
        FxPatterns.finish(g, chest, f.ticks, f.seed);
        for (int i = 0; i < 5; i++) {
            Vec3 orbit = FxPatterns.orbit(i, f.ticks, f.seed);
            FxPatterns.droneSpawn(g, orbit, i, f.ticks, f.seed);
            if (f.ticks >= 104 && f.ticks < WingmanDocking.dockTick(i)) {
                // The five original marks are reused one at a time. Their trail is
                // the inverse of the mark motion, producing a mechanical after-image.
                Vec3 previous = FxPatterns.orbit(i, f.ticks - 7.0f, f.seed);
                g.path(new Vec3[]{previous, previous.lerp(orbit, .35), orbit},
                        0.008, i % 2 == 0 ? FxGeometry.GREEN : FxGeometry.MINT,
                        envelope(f.ticks, 101, 108, WingmanDocking.dockTick(i) - 8, WingmanDocking.dockTick(i)) * .8f, true);
                FxPatterns.mechanicalMark(g, orbit, (float) (f.ticks * .025 + i),
                        envelope(f.ticks, 101, 108, WingmanDocking.dockTick(i) - 8, WingmanDocking.dockTick(i)));
            }
            if (f.ticks < 45 || f.ticks >= WingmanDocking.dockTick(i)) continue;
            float fade = envelope(f.ticks, 42, 53, 104, WingmanDocking.dockTick(i));
            Vec3[] trail = new Vec3[13];
            for (int j = 0; j < trail.length; j++) trail[j] = FxPatterns.orbit(i, f.ticks - (12 - j) * 0.3f, f.seed);
            g.path(trail, 0.006, FxGeometry.GREEN, fade * 0.45f, true);
        }
    }

    private static void drones(FramePlayer f, MultiBufferSource.BufferSource source) {
        if (f.ticks < SPAWN_START) return;
        Matrix4f root = f.pose.last().pose();
        Matrix4f inverseRoot = new Matrix4f(root).invert();
        for (int i = 0; i < 5; i++) {
            if (f.ticks >= WingmanDocking.dockTick(i) + 10) continue;
            float opacity = ramp(f.ticks, SPAWN_START + i * 0.8f, 37 + i * 0.8f) * f.brightness;
            Vec3 orbit = FxPatterns.orbit(i, f.ticks, f.seed);
            if (f.firstPerson && orbit.distanceToSqr(f.eye) < 0.16) continue;
            Quaternionf freeRotation = new Quaternionf().rotationYXZ(
                    (float) (-orbitAngle(i, f.ticks / 20.0, f.seed) + Math.PI / 2),
                    (float) Math.sin(f.ticks * 0.026 + i) * 0.35f,
                    (float) Math.sin(f.ticks * 0.019 + i * 1.8) * 0.23f);
            Matrix4f dronePose = new Matrix4f(root).translate((float) orbit.x, (float) orbit.y, (float) orbit.z).rotate(freeRotation);
            var target = WingmanDocking.current(f.player.getUUID(), i, frame);
            float dock = docking(f.ticks, i);
            float meshBlend = target.isPresent() ? ramp(f.ticks, 100 + i * 2, 108 + i * 2) : 0;
            Matrix4f attachmentPose = dronePose;
            if (target.isPresent()) {
                Matrix4f localTarget = new Matrix4f(inverseRoot).mul(target.get().pose());
                Vector3f end = localTarget.getTranslation(new Vector3f());
                Vector3f p = new Vector3f((float) orbit.x, (float) orbit.y, (float) orbit.z).lerp(end, dock);
                p.y += Math.sin(dock * Math.PI) * 0.20f;
                Quaternionf endRotation = localTarget.getUnnormalizedRotation(new Quaternionf()).normalize();
                Quaternionf rotation = new Quaternionf(freeRotation).slerp(endRotation, dock);
                Vector3f scale = new Vector3f(1).lerp(localTarget.getScale(new Vector3f()), dock);
                attachmentPose = new Matrix4f(root).translate(p).rotate(rotation).scale(scale);
                dronePose = new Matrix4f(attachmentPose);
            }
            renderDroneMesh(f, source, dronePose, i, opacity * (1 - meshBlend), false);
            if (meshBlend > 0) renderDroneMesh(f, source, attachmentPose, i, opacity * meshBlend, true);
        }
    }

    private static void renderDroneMesh(FramePlayer f, MultiBufferSource.BufferSource source, Matrix4f matrix,
                                        int index, float alpha, boolean attachment) {
        if (alpha < 0.002f) return;
        PoseStack pose = new PoseStack();
        pose.mulPose(matrix);
        float green = ramp(f.ticks, 31, 50);
        int red = (int) lerp(240, 86, green);
        int blue = (int) lerp(244, 125, green);
        int color = ((int) (Math.min(1, alpha) * 255) << 24) | (red << 16) | 0x00ff00 | blue;
        if (attachment) WingmanRenderer.renderDetached(pose, source, LightTexture.FULL_BRIGHT, f.ticks, color, index);
        else WingmanRenderer.renderDrone(pose, source, LightTexture.FULL_BRIGHT, f.ticks, color);
        source.endBatch();
        var type = FxRenderTypes.hologram(attachment ? ARMOR_TEXTURE : DRONE_TEXTURE);
        FxShaders.draw(source, type, f.ticks / 20f, 0.70f, () -> {
            // GeckoLib uses NEW_ENTITY for both passes. Obtain the consumer inside the uniform scope.
            MultiBufferSource overlay = ignored -> source.getBuffer(type);
            int tint = ((int) (Math.min(1, alpha) * 255) << 24) | 0xb4ffda;
            if (attachment) WingmanRenderer.renderDetached(pose, overlay, LightTexture.FULL_BRIGHT, f.ticks, tint, index);
            else WingmanRenderer.renderDrone(pose, overlay, LightTexture.FULL_BRIGHT, f.ticks, tint);
        });
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        WingmanDocking.clear();
        if (buffers != null) {
            buffers.endBatch();
            storage.close();
            buffers = null;
            storage = null;
        }
        BloomPipeline.close();
    }

    private record FramePlayer(AbstractClientPlayer player, PoseStack pose, Vec3 eye, float ticks,
                               float partial, float brightness, long seed, boolean firstPerson) {}
}
