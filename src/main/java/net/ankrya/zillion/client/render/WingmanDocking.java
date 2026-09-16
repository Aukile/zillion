package net.ankrya.zillion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Exact, current-pose attachment targets. All methods are render-thread only.
 * Matrices are in the SAME render coordinate system as the armor's incoming PoseStack,
 * not absolute world coordinates. They include wearer pose, scale, bone pivot and rotation.
 * Use setListener for immediate, same-render consumption; it never needs a frame cache.
 * To consume later (e.g. AFTER_ENTITIES), call beginFrame once BEFORE entity rendering,
 * then current(playerUUID, index, frameId). Old frames can never be returned.
 */
public final class WingmanDocking {
    private static final Map<UUID, Map<Integer, Target>> TARGETS = new HashMap<>();
    private static long frame = Long.MIN_VALUE;
    private static Listener listener;

    private WingmanDocking() {}

    /** Indices 0..4 correspond exactly to wingman1..wingman5. */
    public static int dockTick(int index) {
        checkIndex(index);
        return 114 + index * 6; // approach starts at 104; final attachment completes at 138
    }

    public static EquipmentSlot slot(int index) {
        checkIndex(index);
        return index < 3 ? EquipmentSlot.CHEST : EquipmentSlot.FEET;
    }

    public static void beginFrame(long frameId) {
        if (frameId != frame) {
            TARGETS.clear();
            frame = frameId;
        }
    }

    public static void clear() {
        TARGETS.clear();
        frame = Long.MIN_VALUE;
    }

    public static Optional<Target> current(UUID wearer, int index, long frameId) {
        checkIndex(index);
        if (frameId == Long.MIN_VALUE || frameId != frame) return Optional.empty();
        Map<Integer, Target> targets = TARGETS.get(wearer);
        return targets == null ? Optional.empty() : Optional.ofNullable(targets.get(index));
    }

    public static void setListener(Listener callback) {
        listener = callback;
    }

    static void publish(Player player, int index, PoseStack pivotPose, MultiBufferSource buffers,
            int light, float partialTick, float transformationTick) {
        Target target = new Target(player.getUUID(), index, frame, player.tickCount, partialTick,
                transformationTick, pivotPose.last().pose(), pivotPose.last().normal());
        if (frame != Long.MIN_VALUE) {
            TARGETS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()).put(index, target);
        }
        if (listener != null) listener.onTarget(player, target, buffers, light);
    }

    static int index(String name) {
        if (name.length() == 8 && name.startsWith("wingman")) {
            int index = name.charAt(7) - '1';
            if (index >= 0 && index < 5) return index;
        }
        return -1;
    }

    static void checkIndex(int index) {
        if (index < 0 || index >= 5) throw new IllegalArgumentException("Wingman index must be 0..4");
    }

    @FunctionalInterface
    public interface Listener {
        /** Called once per relevant armor slot's base pass, even while wingman is hidden. */
        void onTarget(Player wearer, Target target, MultiBufferSource buffers, int light);
    }

    public static final class Target {
        private final UUID wearer;
        private final int index;
        private final long frameId;
        private final int wearerTick;
        private final float partialTick;
        private final float transformationTick;
        private final Matrix4f pose;
        private final Matrix3f normal;

        private Target(UUID wearer, int index, long frameId, int wearerTick, float partialTick,
                float transformationTick, Matrix4f pose, Matrix3f normal) {
            this.wearer = wearer;
            this.index = index;
            this.frameId = frameId;
            this.wearerTick = wearerTick;
            this.partialTick = partialTick;
            this.transformationTick = transformationTick;
            this.pose = new Matrix4f(pose);
            this.normal = new Matrix3f(normal);
        }

        public UUID wearer() { return this.wearer; }
        public int index() { return this.index; }
        public long frameId() { return this.frameId; }
        public int wearerTick() { return this.wearerTick; }
        public float partialTick() { return this.partialTick; }
        public float transformationTick() { return this.transformationTick; }
        public Matrix4f pose() { return new Matrix4f(this.pose); }
        public Matrix3f normal() { return new Matrix3f(this.normal); }

        /** Pass the inverse of your world-render matrix (including camera translation). */
        public Vector3f worldPosition(Matrix4f renderToWorld) {
            return new Matrix4f(renderToWorld).mul(this.pose).getTranslation(new Vector3f());
        }

        /** Replaces (does not multiply) the top pose. Caller must push/pop its own stack. */
        public void applyTo(PoseStack stack) {
            stack.last().pose().set(this.pose);
            stack.last().normal().set(this.normal);
        }
    }
}
