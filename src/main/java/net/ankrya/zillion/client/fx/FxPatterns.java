package net.ankrya.zillion.client.fx;

import net.ankrya.zillion.Config;
import net.minecraft.world.phys.Vec3;

import static net.ankrya.zillion.client.fx.FxGeometry.*;
import static net.ankrya.zillion.client.fx.FxMath.*;

/** Procedural motifs in player-relative coordinates, with +Z facing the player's front. */
public final class FxPatterns {
    private static final Vec3 X = new Vec3(1, 0, 0);
    private static final Vec3 Y = new Vec3(0, 1, 0);
    private static final Vec3 Z = new Vec3(0, 0, 1);
    private FxPatterns() {}

    public static void sigil(FxGeometry g, float ticks) {
        float visible = envelope(ticks, 0, 6, 25, SIGIL_END);
        if (visible <= 0) return;
        float rise = ramp(ticks, 0, 18);
        float sweep = ramp(ticks, 14, SIGIL_END);
        double radius = lerp(0.23, 1.20, rise);
        double pitch = lerp(0, Math.PI * 1.05, sweep);
        Vec3 axisY = new Vec3(0, Math.cos(pitch), Math.sin(pitch));
        Vec3 center = new Vec3(0, 1.30 + rise * 0.75 - sweep * 1.6, -0.45 + sweep * 0.45);
        for (int i = 0; i < 5; i++) {
            double angle = Math.PI / 2 + TAU * i / 5 + ticks * 0.006;
            Vec3 c = pointOnRing(center, X, axisY, radius, angle);
            double motifSize = radius * 0.39;
            pentagon(g, c, X, axisY, motifSize, angle - Math.PI / 2, visible);
        }
    }

    public static void mechanicalMark(FxGeometry g, Vec3 center, float rotation, float opacity) {
        pentagon(g, center, X, Y, 0.24, rotation, opacity);
    }

    private static void pentagon(FxGeometry g, Vec3 center, Vec3 x, Vec3 y,
                                 double radius, double rotation, float opacity) {
        Vec3[] outline = new Vec3[6];
        Vec3[] inner = new Vec3[6];
        for (int i = 0; i <= 5; i++) {
            double angle = Math.PI / 2 + rotation + TAU * (i % 5) / 5;
            outline[i] = pointOnRing(center, x, y, radius, angle);
            inner[i] = pointOnRing(center, x, y, radius * 0.80, angle);
        }
        g.path(outline, 0.019, GREEN, opacity, false);
        g.path(inner, 0.004, MINT, opacity * 0.6f, false);
        // The open angular insignia is intentionally not a pentagram. It has a central
        // split spine, inset chevrons and interrupted side branches as in the reference.
        // F-shaped inner circuitry: a vertical spine and two inward-slanting arms.
        Vec3 top = center.add(y.scale(radius * 0.43)).add(x.scale(-radius * 0.20));
        Vec3 spine = center.add(y.scale(-radius * 0.43)).add(x.scale(-radius * 0.20));
        Vec3 upper = center.add(y.scale(radius * 0.24)).add(x.scale(radius * 0.34));
        Vec3 middle = center.add(y.scale(-radius * 0.02)).add(x.scale(radius * 0.20));
        g.path(new Vec3[]{top, spine}, 0.012, MINT, opacity, false);
        g.path(new Vec3[]{top, upper}, 0.012, MINT, opacity, false);
        g.path(new Vec3[]{middle, middle.add(x.scale(radius * 0.22))}, 0.012, MINT, opacity, false);
        // Keep a small angular inset around the F, matching the five-sided module.
        g.line(inner[1], upper, 0.006, GREEN, opacity * 0.75f);
        g.line(inner[4], middle, 0.006, GREEN, opacity * 0.75f);
    }

    public static Vec3 orbit(int index, float ticks, long seed) {
        // Each drone follows a deterministic straight flight segment. The quintic
        // easing gives acceleration/deceleration, while the middle hold creates the
        // characteristic autonomous-drone pause at each endpoint.
        double seconds = ticks / 20.0;
        double cycle = seconds * (0.52 + random(seed, index + 19) * 0.08) + random(seed, index + 29);
        double phase = cycle - Math.floor(cycle);
        double segment = Math.floor(cycle);
        double u;
        if (phase < 0.12) u = 0;
        else if (phase < 0.42) u = smooth((float) ((phase - 0.12) / 0.30));
        else if (phase < 0.58) u = 1;
        else if (phase < 0.88) u = 1 - smooth((float) ((phase - 0.58) / 0.30));
        else u = 0;
        double angle = TAU * index / 5 + random(seed, index + 41) * 0.35 + segment * 1.73;
        double direction = random(seed, index + 51) * TAU + segment * 0.91;
        double baseRadius = 1.12 + random(seed, index + 61) * 0.15;
        double amplitude = 0.34 + random(seed, index + 71) * 0.12;
        double baseY = 1.02 + Math.sin(TAU * index / 5 + seconds * 0.35) * 0.24;
        double baseX = Math.cos(angle) * baseRadius;
        double baseZ = Math.sin(angle) * baseRadius;
        double dx = Math.cos(direction) * amplitude * (u * 2 - 1);
        double dz = Math.sin(direction) * amplitude * (u * 2 - 1);
        return new Vec3(baseX + dx, baseY + (random(seed, index + 81) - .5) * .24, baseZ + dz);
    }

    public static void droneSpawn(FxGeometry g, Vec3 center, int index, float ticks, long seed) {
        float local = ticks - index * 0.8f;
        float flash = envelope(local, SPAWN_START, SPAWN_START + 5, 33, SPAWN_END);
        if (flash <= 0) return;
        double radius = 0.20 + ramp(local, SPAWN_START, SPAWN_END) * 0.17;
        g.ring(center, X, Y, radius, 0, TAU, 36, 0.028, WHITE, flash);
        g.ring(center, X, Z, radius * 1.18, local * 0.10, TAU, 36, 0.015, GREEN, flash);
        // A luminous cross/scan column instead of an opaque camera-filling white billboard.
        g.line(center.add(-radius, 0, 0), center.add(radius, 0, 0), 0.10, WHITE, flash);
        for (int j = 0; j < (Config.quality == 0 ? 9 : 21); j++) {
            double x = (random(seed + index, j + 21) - 0.5) * 0.8;
            double z = (random(seed + index, j + 47) - 0.5) * 0.5;
            double height = 0.5 + random(seed + index, j + 73) * 0.6;
            Vec3 p = center.add(x, -height / 2, z);
            g.line(p, p.add(0, height, 0), 0.003, MINT, flash * 0.52f);
            double size = 0.018 + random(seed, j + 100) * 0.025;
            Vec3 q = center.add(x * 1.9, Math.sin(local * 0.08 + j) * 0.48, z);
            g.path(new Vec3[]{q, q.add(size, 0, 0), q.add(size, size, 0), q.add(0, size, 0), q},
                    0.002, GREEN, flash * 0.8f, false);
        }
    }

    public static void vortex(FxGeometry g, float ticks, long seed) {
        float visible = envelope(ticks, VORTEX_START, 51, 97, 112);
        if (visible <= 0) return;
        float density = ramp(ticks, 40, 86);
        float contraction = ramp(ticks, ARMOR_START, 109);
        int maximum = Config.quality == 0 ? Math.min(12, Config.vortexCurves) : Config.vortexCurves;
        int segments = Config.quality == 0 ? 28 : Config.quality == 1 ? 44 : 64;
        double time = ticks / 20.0;
        for (int i = 0; i < maximum; i++) {
            float weight = clamp(density * maximum - i + 3);
            if (weight <= 0) continue;
            boolean red = i % 5 == 1;
            double phase = random(seed, i + 151) * TAU;
            double radius = (1.03 + random(seed, i + 201) * 0.61) * (1 - contraction * 0.82);
            double sweep = Math.PI * (0.9 + random(seed, i + 251) * 2.0);
            Vec3[] curve = new Vec3[segments + 1];
            for (int j = 0; j <= segments; j++) {
                double u = j / (double) segments;
                double theta = phase + time * (red ? -1.6 : 1.35) + u * sweep;
                double bend = 1 + Math.sin(u * TAU + phase + time) * 0.10;
                double y = -0.18 + u * 2.65 + Math.sin(theta + phase) * 0.31;
                curve[j] = new Vec3(Math.cos(theta) * radius * bend,
                        lerp(y, 0.15 + u * 1.70, contraction), Math.sin(theta) * radius * bend);
            }
            g.path(curve, red ? 0.009 : 0.012, red ? RED : GREEN, visible * weight * (red ? 0.90f : 0.65f), true);
        }
        circuitShell(g, ticks, seed, visible * density, 1.46 - contraction * 1.17);
    }

    private static void circuitShell(FxGeometry g, float ticks, long seed, float opacity, double radius) {
        int count = Config.quality == 0 ? 24 : Config.quality == 1 ? 48 : 76;
        for (int i = 0; i < count; i++) {
            double theta = random(seed, i + 351) * TAU + ticks * 0.002;
            double y = ((random(seed, i + 451) * 2.9 + ticks * 0.012) % 2.9) - 0.2;
            double length = 0.07 + random(seed, i + 551) * 0.32;
            double turn = 0.035 + random(seed, i + 651) * 0.11;
            Vec3 a = new Vec3(Math.cos(theta) * radius, y, Math.sin(theta) * radius);
            Vec3 b = a.add(0, length, 0);
            Vec3 c = new Vec3(Math.cos(theta + turn) * radius, y + length, Math.sin(theta + turn) * radius);
            g.path(new Vec3[]{a, b, c, c.add(0, length * 0.5, 0)}, 0.0027, MINT, opacity * 0.47f, false);
        }
    }

    public static void finish(FxGeometry g, Vec3 chest, float ticks, long seed) {
        float visible = envelope(ticks, FINISH_START, 137, 147, DURATION);
        if (visible <= 0) return;
        float expand = ramp(ticks, FINISH_START, 157);
        double radius = lerp(0.39, 1.05, expand);
        for (int layer = 0; layer < 2; layer++) {
            double r = radius * (layer == 0 ? 1 : 0.74);
            double angle = ticks * (layer == 0 ? 0.023 : -0.032);
            Vec3 center = chest.add(0, 0, 0.14 + layer * 0.06);
            g.ring(center, X, Y, r, 0, TAU, 80, 0.022, MINT, visible);
            g.ring(center, X, Y, r * 0.94, 0, TAU, 80, 0.005, GREEN, visible * 0.85f);
            for (int j = 0; j < 36; j++) {
                double a = angle + j * TAU / 36;
                Vec3 p = pointOnRing(center, X, Y, r * 0.82, a);
                Vec3 q = pointOnRing(center, X, Y, r * (j % 3 == 0 ? 0.93 : 0.88), a);
                g.line(p, q, 0.003, GREEN, visible * 0.8f);
            }
            for (int j = 0; j < 7; j++) {
                double a = angle + j * TAU / 7;
                g.ring(center, X, Y, r * 0.65, a, 0.48, 10, 0.004, GREEN, visible * 0.65f);
            }
            // Clipped, interrupted right-angle circuitry inside both discs.
            for (int row = -3; row <= 3; row++) {
                double y = row * r * 0.16;
                double limit = Math.sqrt(Math.max(0, r * r * 0.34 - y * y));
                double drift = Math.sin(ticks * 0.06 + row) * r * 0.1;
                Vec3 p = center.add(-limit, y, 0);
                Vec3 q = center.add(drift, y, 0);
                Vec3 s = q.add(0, r * 0.075, 0);
                g.path(new Vec3[]{p, q, s, center.add(limit, y + r * 0.075, 0)},
                        0.0025, GREEN, visible * 0.48f, false);
            }
        }
        circuitShell(g, ticks * 2, seed, visible * 0.75f, 0.43);
        // Red neck-strip energy rises and flutters before the physical red strip is revealed.
        float scarf = envelope(ticks, 136, 145, 149, 160);
        for (int i = 0; i < 5; i++) {
            double x = (i - 2) * 0.075;
            Vec3 a = chest.add(x, 0.54 + Math.sin(ticks * .08 + i) * .035, .10);
            Vec3 b = a.add(Math.sin(ticks * .11 + i) * .11, .18 + i * .025, .05);
            g.line(a, b, .009, RED, scarf * .8f);
        }
        for (int i = 0; i < 12; i++) {
            double theta = i * TAU / 12;
            double y = 0.25 + random(seed, i + 901) * 1.5;
            Vec3 start = new Vec3(Math.cos(theta) * 0.32, y, Math.sin(theta) * 0.32);
            Vec3 end = start.add(Math.cos(theta) * expand * 0.7, expand * 0.35, Math.sin(theta) * expand * 0.7);
            g.line(start, end, 0.004, i % 4 == 0 ? RED : MINT, visible * 0.75f);
        }
    }
}
