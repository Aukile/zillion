package com.zillion.client.fx;

import com.zillion.henshin.HenshinTiming;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side state of one transformation. Owns the five drones, the wind lines and all timing helpers.
 * Everything is simulated in ticks with per-frame interpolation, so it stays smooth while the player moves.
 */
public class HenshinInstance {
    public final LivingEntity entity;
    public final RandomSource random;
    public final long seed;

    /** Sequence time in ticks (integer part). */
    public int tick;
    private boolean armorEquipped;
    private boolean finished;
    private int postFinish = -1;

    public final Drone[] drones = new Drone[(int) HenshinTiming.DRONE_COUNT];
    public final List<WindLine> windLines = new ArrayList<>();
    /** Small square "data" particles that rain around the drones. */
    public final List<DataBit> dataBits = new ArrayList<>();
    /** Fast-darting small emblems during the chest burst (mirrored pairs). */
    public final Dart[] darts = new Dart[4];

    public HenshinInstance(LivingEntity entity, long seed) {
        this.entity = entity;
        this.seed = seed;
        this.random = RandomSource.create(seed);
        for (int i = 0; i < this.drones.length; i++)
            this.drones[i] = new Drone(i, this.random);
        // pre-generate the wind lines (they get activated over time)
        for (int i = 0; i < 46; i++)
            this.windLines.add(new WindLine(i, this.random));
        for (int i = 0; i < this.darts.length; i += 2) {
            this.darts[i] = new Dart(i / 2, this.random);
            this.darts[i + 1] = new Dart(this.darts[i]);
        }
    }

    public void onArmorEquipped() {
        this.armorEquipped = true;
        if (this.tick < HenshinTiming.ARMOR_TICK) // network jitter: snap the timeline to the server
            this.tick = HenshinTiming.ARMOR_TICK;
    }

    public void onFinished() {
        this.finished = true;
        if (this.tick < HenshinTiming.END_TICK)
            this.tick = HenshinTiming.END_TICK;
    }

    public boolean isDone() {
        return this.tick > HenshinTiming.CLIENT_END + 5;
    }

    public boolean isArmorEquipped() {
        return this.armorEquipped;
    }

    /** Continuous sequence time in ticks. */
    public float time(float partialTick) {
        return this.tick + partialTick;
    }

    public void tick() {
        this.tick++;
        float t = this.tick;
        if (t > HenshinTiming.DRONE_SPAWN && t < HenshinTiming.DOCK_START) {
            for (Drone d : this.drones) {
                if (d.isVisible(t) && this.random.nextFloat() < 0.6f)
                    this.dataBits.add(new DataBit(d, this.random));
            }
        }
        this.dataBits.removeIf(DataBit::tick);
        if (this.finished && this.postFinish < 0)
            this.postFinish = 0;
        else if (this.postFinish >= 0)
            this.postFinish++;
    }

    /** Interpolated entity origin (feet). */
    public Vec3 origin(float partialTick) {
        return this.entity.getPosition(partialTick);
    }

    public float bodyYaw(float partialTick) {
        return Mth.rotLerp(partialTick, this.entity.yBodyRotO, this.entity.yBodyRot);
    }

    // ---------------------------------------------------------------- easing helpers
    public static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    public static float easeOutCubic(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    public static float easeInCubic(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * t;
    }

    public static float easeInOutSine(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return -(Mth.cos(Mth.PI * t) - 1f) / 2f;
    }

    public static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.70158f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }

    /** 0..1 window with smooth fade-in and fade-out. */
    public static float window(float t, float start, float end, float fadeIn, float fadeOut) {
        if (t < start || t > end)
            return 0f;
        float in = fadeIn <= 0 ? 1f : Mth.clamp((t - start) / fadeIn, 0f, 1f);
        float out = fadeOut <= 0 ? 1f : Mth.clamp((end - t) / fadeOut, 0f, 1f);
        return smooth(in) * smooth(out);
    }

    public static float frac(float t, float start, float end) {
        return Mth.clamp((t - start) / (end - start), 0f, 1f);
    }

    // ================================================================ inner types

    /**
     * One wind line: a curved ribbon that orbits the player. Defined in entity-local space (yaw independent),
     * so it follows the player when they walk without jitter.
     */
    public static class WindLine {
        public final int index;
        public final float phase;          // starting angle
        public final float height0;        // base height
        public final float heightSpan;     // how much it rises along its length
        public final float radius0;        // base radius
        public final float radiusWobble;
        public final float length;         // angular length (radians)
        public final float speed;          // angular speed (rad / tick)
        public final float width;
        public final float activateAt;     // sequence tick this line appears at
        public final boolean red;
        public final float freq, tilt;

        public WindLine(int index, RandomSource r) {
            this.index = index;
            this.phase = r.nextFloat() * Mth.TWO_PI;
            this.height0 = r.nextFloat() * 1.1f - 0.1f;
            this.heightSpan = (r.nextFloat() - 0.4f) * 1.4f;
            this.radius0 = 0.75f + r.nextFloat() * 0.9f;
            this.radiusWobble = 0.1f + r.nextFloat() * 0.35f;
            this.length = 1.6f + r.nextFloat() * 3.0f;
            this.speed = (0.05f + r.nextFloat() * 0.09f) * (r.nextBoolean() ? 1 : -1);
            this.width = 0.035f + r.nextFloat() * 0.06f;
            this.activateAt = HenshinTiming.WIND_START + HenshinInstance.easeInCubic(index / 46f) * (HenshinTiming.WIND_FULL - HenshinTiming.WIND_START) * 0.9f;
            this.red = r.nextFloat() < 0.32f;
            this.freq = 1.5f + r.nextFloat() * 3.5f;
            this.tilt = (r.nextFloat() - 0.5f) * 0.8f;
        }

        /** Overall visibility 0..1 at time t. */
        public float alpha(float t) {
            float appear = HenshinInstance.smooth((t - this.activateAt) / 10f);
            float gone = 1f - HenshinInstance.smooth((t - HenshinTiming.CONTRACT_END - 2f) / 10f);
            return Mth.clamp(appear, 0f, 1f) * Mth.clamp(gone, 0f, 1f);
        }

        /** Contraction factor: 1 = full radius, ~0.14 = hugging the body. */
        public float contraction(float t) {
            float c = HenshinInstance.easeInCubic((t - HenshinTiming.CONTRACT_START) / (HenshinTiming.CONTRACT_END - HenshinTiming.CONTRACT_START));
            return 1f - c * 0.86f;
        }

        /** Per-line thickness multiplier (fatter lines in the middle of the swarm, thin ones outside). */
        public float thickness() {
            return 0.75f + 0.5f * (this.index % 4) / 3f;
        }

        /** Point on the line at parameter s (0..1) in entity-local space (x right, y up, z forward).
         *  Each line is a chaotic, mostly-vertical curve: it rises along the body while bending irregularly
         *  in several directions (per-line random harmonics), so lines lean, cross and wrap over each other. */
        public Vector3f point(float s, float t, Vector3f dest) {
            float contraction = contraction(t);
            float dirSign = this.speed > 0 ? 1f : -1f;
            float a = this.phase + this.speed * 0.5f * t
                    + (s - 0.5f) * this.length * 0.45f * dirSign * this.tilt * 2.5f
                    + Mth.sin(s * this.freq * 2.3f + this.phase * 1.7f + t * 0.08f) * 0.55f
                    + Mth.sin(s * this.freq * 5.1f + this.phase * 0.6f - t * 0.13f) * 0.22f;
            float rad = this.radius0
                    + Mth.sin(s * this.freq * 3.7f + this.phase + t * 0.17f) * this.radiusWobble * 1.6f
                    + Mth.sin(s * 11f + this.phase * 2.3f) * 0.08f;
            rad = Math.max(0.25f, rad) * contraction;
            float span = 2.3f + Math.abs(this.heightSpan) * 1.2f;
            float y = this.height0 - 0.9f + s * span
                    + Mth.sin(s * 6.28f * 0.75f + this.phase) * this.tilt * 0.9f
                    + Mth.sin(s * this.freq * 4.4f + this.phase * 3f + t * 0.1f) * 0.14f;
            float c = 1f - contraction;
            y = Mth.lerp(c * 0.5f, y, 0.95f + this.tilt * 0.4f);
            return dest.set(Mth.cos(a) * rad, y, Mth.sin(a) * rad);
        }
    }

    /** Tiny glowing squares that fall around the drones. */
    public static class DataBit {
        public float x, y, z, px, py, pz, vy, size;
        public int age, life;

        DataBit(Drone d, RandomSource r) {
            Vector3f p = d.currentPos;
            this.x = this.px = p.x + (r.nextFloat() - 0.5f) * 0.9f;
            this.y = this.py = p.y + (r.nextFloat() - 0.5f) * 0.9f;
            this.z = this.pz = p.z + (r.nextFloat() - 0.5f) * 0.9f;
            this.vy = -0.02f - r.nextFloat() * 0.05f;
            this.size = 0.02f + r.nextFloat() * 0.035f;
            this.life = 10 + r.nextInt(14);
        }

        boolean tick() {
            this.px = this.x; this.py = this.y; this.pz = this.z;
            this.y += this.vy;
            return ++this.age >= this.life;
        }
    }

    /** A small emblem that darts around the rider in straight diagonal hops (trail line first, then the emblem).
     *  Darts come in left/right mirrored pairs: points are in body space (x = player's left, z = forward) and
     *  the odd dart of each pair is the exact x-mirror of the even one, moving at the same time. */
    public static class Dart {
        public static final int HOPS = 6;
        public static final float LINE_TIME = 2f, MOVE_TIME = 2f, HOLD_TIME = 4f;
        public static final float HOP_LEN = LINE_TIME + MOVE_TIME + HOLD_TIME;
        public final float delay;
        public final float spin;
        public final Vector3f[] points = new Vector3f[HOPS + 1];

        /** Creates the "master" dart of a pair. */
        public Dart(int pair, RandomSource r) {
            this.delay = pair * 5f + r.nextFloat() * 2f;
            this.spin = r.nextFloat() * 360f;
            float ang = 0.35f + r.nextFloat() * 1.0f;
            float y = 0.4f + r.nextFloat() * 1.4f;
            boolean up = r.nextBoolean();
            for (int i = 0; i <= HOPS; i++) {
                float rad = 0.9f + r.nextFloat() * 1.0f;
                this.points[i] = new Vector3f(Mth.sin(ang) * rad, y, -Mth.cos(ang) * rad);
                float da = (0.6f + r.nextFloat() * 0.9f) * (r.nextFloat() < 0.5f ? 1 : -1);
                ang = Mth.clamp(ang + da, 0.2f, 2.9f);
                float dy = 0.7f + r.nextFloat() * 0.7f;
                if (up && y + dy > 2.4f) up = false;
                if (!up && y - dy < 0.25f) up = true;
                y += up ? dy : -dy;
                if (r.nextFloat() < 0.3f) up = !up;
            }
        }

        /** Mirrored twin of {@code master} (x negated, same timing). */
        public Dart(Dart master) {
            this.delay = master.delay;
            this.spin = -master.spin;
            for (int i = 0; i <= HOPS; i++)
                this.points[i] = new Vector3f(-master.points[i].x, master.points[i].y, master.points[i].z);
        }
    }
}
