package net.ankrya.zillion.client.fx;

/** Pure, deterministic choreography math; seconds are derived from synchronized game ticks. */
public final class FxMath {
    public static final double TAU = Math.PI * 2;
    public static final float DURATION = 160;
    public static final float SIGIL_END = 34;
    public static final float SPAWN_START = 24;
    public static final float SPAWN_END = 50;
    public static final float VORTEX_START = 40;
    public static final float ARMOR_START = 84;
    public static final float DOCK_START = 104;
    public static final float DOCK_END = 138;
    public static final float FINISH_START = 130;

    private FxMath() {}

    public static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    public static float progress(float time, float start, float end) {
        return clamp((time - start) / (end - start));
    }
    /** Quintic easing has zero velocity and acceleration at both ends. */
    public static float smooth(float value) {
        float t = clamp(value);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
    public static float ramp(float time, float start, float end) {
        return smooth(progress(time, start, end));
    }
    public static float envelope(float time, float inStart, float inEnd, float outStart, float outEnd) {
        return ramp(time, inStart, inEnd) * (1 - ramp(time, outStart, outEnd));
    }
    public static double lerp(double start, double end, double t) { return start + (end - start) * t; }
    /** Stable per-session variation; never sample random values inside a frame. */
    public static double random(long seed, int index) {
        long x = seed + 0x9e3779b97f4a7c15L * (index + 1L);
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return ((x ^ (x >>> 31)) >>> 11) * 0x1.0p-53;
    }
    public static double orbitAngle(int index, double seconds, long seed) {
        // Five disjoint sectors: offsets never exceed 0.24rad, so neighbors cannot bunch up.
        return TAU * index / 5 + seconds * 0.56 + random(seed, 0) * TAU
                + Math.sin(seconds * 1.39 + random(seed, index + 1) * TAU) * 0.24;
    }
    public static double orbitRadius(int index, double seconds, long seed) {
        return 1.35 + Math.sin(seconds * 1.23 + random(seed, index + 6) * TAU) * 0.20;
    }
    public static double orbitHeight(int index, double seconds, long seed) {
        return 1.20 + Math.sin(TAU * index / 5 + seconds * 0.83) * 0.69
                + Math.sin(seconds * 1.71 + random(seed, index + 11) * TAU) * 0.12;
    }
    public static float docking(float ticks, int index) {
        return ramp(ticks, DOCK_START + index * 2, 114 + index * 6);
    }
}
