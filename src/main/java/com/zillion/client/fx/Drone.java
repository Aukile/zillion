package com.zillion.client.fx;

import com.zillion.Zillion;
import com.zillion.henshin.HenshinTiming;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * One of the five effect drones.
 * <p>
 * Free flight is a deterministic smooth function of time (sum of sines around an assigned azimuth sector), evaluated
 * per frame in an axis-aligned frame centred on the entity - so it follows the player perfectly without jitter.
 * <p>
 * Docking: all drones fly to a hover spot a little in front of their armor slot, arrive at the same time, push
 * outwards slightly (anticipation) and then slam back into the slot together.
 * <p>
 * Output poses are in "dock space" (see {@link HenshinRenderer#dockSpace}), positions in blocks.
 */
public class Drone {
    public static final ResourceLocation GEO = Zillion.id("geo/gazerzero_effect_drone.geo.json");
    /** Drone model bones in the order wingman1..5 (chest, right arm, left arm, right boot, left boot). */
    public static final String[] BONES = {"bone3", "bone2", "bone4", "bone", "bone5"};
    /** Local "outward" direction of every slot in dock space (x is mirrored there: right arm = -x model = +x dock). */
    private static final Vector3f[] OUTWARD = {
            new Vector3f(0, 0.15f, -1).normalize(),      // chest: forward
            new Vector3f(1, 0.1f, -0.35f).normalize(),   // right arm (dock +x)
            new Vector3f(-1, 0.1f, -0.35f).normalize(),  // left arm
            new Vector3f(0.25f, 0.1f, -1).normalize(),   // right boot
            new Vector3f(-0.25f, 0.1f, -1).normalize(),  // left boot
    };

    public final int index;
    public final String boneName;
    public final float spawnTick;
    private final float sectorAngle;
    private final float[] phase = new float[6];
    private final float[] freq = new float[6];
    private final float radiusBase, heightBase, wanderAmp;

    /** Current pose (updated every frame by {@link #evaluate}). */
    public final Vector3f currentPos = new Vector3f();
    public final Quaternionf currentRot = new Quaternionf();
    public final Vector3f currentScale = new Vector3f(1, 1, 1);

    public Drone(int index, RandomSource r) {
        this.index = index;
        this.boneName = BONES[index];
        this.spawnTick = HenshinTiming.DRONE_SPAWN + index * HenshinTiming.DRONE_SPAWN_STAGGER;
        this.sectorAngle = Mth.TWO_PI * index / HenshinTiming.DRONE_COUNT + r.nextFloat() * 0.3f;
        for (int i = 0; i < 6; i++) {
            this.phase[i] = r.nextFloat() * Mth.TWO_PI;
            this.freq[i] = 0.035f + r.nextFloat() * 0.05f;
        }
        this.radiusBase = 1.15f + r.nextFloat() * 0.45f;
        this.heightBase = 0.9f + r.nextFloat() * 0.6f;
        this.wanderAmp = 0.45f + r.nextFloat() * 0.25f;
        buildWaypoints(r);
    }

    public boolean isVisible(float t) {
        return t >= this.spawnTick && t < HenshinTiming.DOCK_END;
    }

    /** 0..1 spawn scale-in factor. */
    public float spawnFactor(float t) {
        return HenshinInstance.easeOutBack(Mth.clamp((t - this.spawnTick) / 8f, 0f, 1f));
    }

    /** 1 = pure white spawn glow, 0 = fully green. */
    public float whiteFactor(float t) {
        return 1f - HenshinInstance.smooth((t - this.spawnTick - 4f) / HenshinTiming.DRONE_FLASH);
    }

    /** Free flight: linear hops between random waypoints with a short stop at each one (v1.0 behaviour). */
    private static final float HOP_MOVE = 5f, HOP_HOLD = 6f, HOP_LEN = HOP_MOVE + HOP_HOLD;
    private static final int WAYPOINTS = 40;
    private final Vector3f[] waypoints = new Vector3f[WAYPOINTS];

    private void buildWaypoints(RandomSource r) {
        // waypoint 0 = spawn point at waist height, close to the body; later ones are long, decisive hops around
        // this drone's sector (each hop at least ~1.1 blocks so the motion reads as a snap, not a shuffle)
        this.waypoints[0] = new Vector3f(Mth.cos(this.sectorAngle) * 0.7f, 0.78f, Mth.sin(this.sectorAngle) * 0.7f);
        for (int i = 1; i < WAYPOINTS; i++) {
            float spread = Mth.clamp(i / 2f, 0f, 1f);
            Vector3f best = null;
            for (int attempt = 0; attempt < 12; attempt++) {
                float ang = this.sectorAngle + (r.nextFloat() - 0.5f) * 2.6f * spread + i * 0.15f;
                float rad = Mth.lerp(spread, 1.1f, this.radiusBase + 0.4f + (r.nextFloat() - 0.5f) * 1.6f);
                float y = Mth.lerp(spread, 1.1f, this.heightBase + 0.2f + (r.nextFloat() - 0.5f) * 3.2f * this.wanderAmp);
                y = Mth.clamp(y, 0.35f, 2.6f);
                Vector3f c = new Vector3f(Mth.cos(ang) * rad, y, Mth.sin(ang) * rad);
                if (best == null || c.distance(this.waypoints[i - 1]) > best.distance(this.waypoints[i - 1]))
                    best = c;
                if (c.distance(this.waypoints[i - 1]) >= 1.1f)
                    break;
            }
            this.waypoints[i] = best;
        }
    }

    /** Free-flight position in the axis-aligned entity-local frame (blocks). */
    private void freePos(float t, Vector3f dest) {
        float ts = Math.max(0f, t - this.spawnTick);
        int leg = Math.min((int) (ts / HOP_LEN), WAYPOINTS - 2);
        float k = Mth.clamp((ts - leg * HOP_LEN) / HOP_MOVE, 0f, 1f);   // linear move, then hold at the end point
        dest.set(this.waypoints[leg]).lerp(this.waypoints[leg + 1], k);
        // drift slightly inwards when the whirlwind contracts
        float contract = HenshinInstance.smooth((t - HenshinTiming.CONTRACT_START) / 14f);
        if (contract > 0f) {
            float horiz = Mth.sqrt(dest.x * dest.x + dest.z * dest.z);
            if (horiz > 0.01f) {
                float nr = horiz + contract * 0.25f;
                dest.x *= nr / horiz;
                dest.z *= nr / horiz;
            }
        }
    }

    private void freeRot(float t, Vector3f pos, Quaternionf dest) {
        float ts = Math.max(0f, t - this.spawnTick);
        int leg = Math.min((int) (ts / HOP_LEN), WAYPOINTS - 2);
        Vector3f vel = this.waypoints[leg + 1].sub(this.waypoints[leg], new Vector3f());
        // the drone model's nose points to -z (opposite to its inward docking motion), so aim -z along the velocity
        float yaw = (float) Mth.atan2(vel.x, vel.z) + Mth.PI;
        float pitch = (float) Mth.atan2(vel.y, Math.max(0.001f, Mth.sqrt(vel.x * vel.x + vel.z * vel.z))) * 0.6f;
        float bank = Mth.sin(t * this.freq[5] * 3f + this.phase[5]) * 0.2f;
        dest.identity().rotateY(yaw).rotateX(pitch).rotateZ(bank);
    }

    /** The resting pose of this drone's bone (== the armor's wingman bone), in dock space. */
    private static void restPose(GeoBone bone, Vector3f pos, Quaternionf rot) {
        pos.set(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f);
        rot.identity().rotateZ(bone.getRotZ()).rotateY(bone.getRotY()).rotateX(bone.getRotX());
    }

    /** Distance along the outward axis from the slot at docking time (0 = docked). */
    private static float dockOffset(float dockT) {
        float a = HenshinTiming.DOCK_APPROACH, h = HenshinTiming.DOCK_HOLD, an = HenshinTiming.DOCK_ANTICIPATE, s = HenshinTiming.DOCK_SLAM;
        if (dockT < a + h)
            return HenshinTiming.DOCK_HOVER_DIST;
        if (dockT < a + h + an) {
            float k = HenshinInstance.smooth((dockT - a - h) / an);
            return HenshinTiming.DOCK_HOVER_DIST + HenshinTiming.DOCK_ANTICIPATE_DIST * k;
        }
        float k = HenshinInstance.easeInCubic((dockT - a - h - an) / s);
        return (HenshinTiming.DOCK_HOVER_DIST + HenshinTiming.DOCK_ANTICIPATE_DIST) * (1f - k);
    }

    /** 0..1: how far the slam has progressed (for impact flashes). */
    public static float slamProgress(float t) {
        float start = HenshinTiming.DOCK_START + HenshinTiming.DOCK_APPROACH + HenshinTiming.DOCK_HOLD + HenshinTiming.DOCK_ANTICIPATE;
        return Mth.clamp((t - start) / HenshinTiming.DOCK_SLAM, 0f, 1f);
    }

    /**
     * Evaluate the pose for time {@code t}. {@code bodyYawDeg} converts the axis-aligned free-flight frame into dock space.
     */
    public void evaluate(GeoBone bone, float t, float bodyYawDeg) {
        float dockT = t - HenshinTiming.DOCK_START;
        Vector3f fp = new Vector3f();
        Quaternionf fr = new Quaternionf();
        if (dockT < HenshinTiming.DOCK_APPROACH) {
            freePos(t, fp);
            freeRot(t, fp, fr);
            float a = (float) Math.toRadians(180f - bodyYawDeg);
            Quaternionf inv = new Quaternionf().rotationY(-a);
            inv.transform(fp);
            fp.y -= HenshinRenderer.MODEL_Y_OFFSET;
            fp.div(0.9375f);
            inv.mul(fr, fr);
        }
        if (dockT <= 0) {
            this.currentPos.set(fp);
            this.currentRot.set(fr);
            float s = spawnFactor(t);
            this.currentScale.set(s, s, s);
            return;
        }
        Vector3f rest = new Vector3f();
        Quaternionf restRot = new Quaternionf();
        restPose(bone, rest, restRot);
        Vector3f out = OUTWARD[this.index];
        this.currentScale.set(1, 1, 1);
        if (dockT < HenshinTiming.DOCK_APPROACH) {
            // fly to the hover spot; arrive together, with a slight arc so it doesn't look linear
            float k = HenshinInstance.smooth(dockT / HenshinTiming.DOCK_APPROACH);
            Vector3f hover = new Vector3f(out).mul(HenshinTiming.DOCK_HOVER_DIST).add(rest);
            float arc = Mth.sin(k * Mth.PI) * 0.18f;
            this.currentPos.set(fp).lerp(hover, k).add(0, arc, 0);
            fr.slerp(restRot, HenshinInstance.smooth(k * 1.3f), this.currentRot);
        } else {
            float off = dockOffset(dockT);
            this.currentPos.set(out).mul(off).add(rest);
            // gentle hover bob while waiting
            if (dockT < HenshinTiming.DOCK_APPROACH + HenshinTiming.DOCK_HOLD + HenshinTiming.DOCK_ANTICIPATE)
                this.currentPos.y += Mth.sin(t * 0.8f + this.index) * 0.006f;
            this.currentRot.set(restRot);
        }
    }
}
