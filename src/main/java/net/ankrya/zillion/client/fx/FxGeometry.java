package net.ankrya.zillion.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Camera-facing triangle ribbons (submitted as quads), not driver-dependent GL line primitives. */
public final class FxGeometry {
    public static final int GREEN = 0x64ff92;
    public static final int MINT = 0xb8ffca;
    public static final int RED = 0xff405b;
    public static final int WHITE = 0xf1fff4;
    private final VertexConsumer vertices;
    private final Matrix4f pose;
    private final Vec3 eye;
    private final float intensity;

    public FxGeometry(VertexConsumer vertices, Matrix4f pose, Vec3 localEye, float intensity) {
        this.vertices = vertices;
        this.pose = pose;
        this.eye = localEye;
        this.intensity = intensity;
    }

    public void line(Vec3 a, Vec3 b, double width, int color, float opacity) {
        if (opacity <= 0.001f || a.distanceToSqr(b) < 1.0e-10) return;
        Vec3 midpoint = a.add(b).scale(0.5);
        // Prevent near-camera ribbons from filling the first-person screen.
        if (midpoint.distanceToSqr(eye) < 0.075) return;
        Vec3 side = b.subtract(a).cross(eye.subtract(midpoint));
        if (side.lengthSqr() < 1.0e-10) side = b.subtract(a).cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0e-10) side = new Vec3(1, 0, 0);
        side = side.normalize();
        Vec3 core = side.scale(width * 0.36);
        Vec3 outer = side.scale(width * 2.6);
        float alpha = Math.min(1, opacity * intensity);
        quad(a.subtract(core), b.subtract(core), b.add(core), a.add(core), color, alpha, alpha, alpha, alpha);
        quad(a.add(core), b.add(core), b.add(outer), a.add(outer), color, alpha * 0.45f, alpha * 0.45f, 0, 0);
        quad(a.subtract(outer), b.subtract(outer), b.subtract(core), a.subtract(core), color, 0, 0, alpha * 0.45f, alpha * 0.45f);
    }

    public void path(Vec3[] points, double width, int color, float opacity, boolean taper) {
        for (int i = 1; i < points.length; i++) {
            float t = (i - 0.5f) / (points.length - 1);
            float fade = taper ? (float) Math.pow(Math.sin(Math.PI * t), 0.45) : 1;
            line(points[i - 1], points[i], width, color, opacity * fade);
        }
    }

    public void ring(Vec3 center, Vec3 axisX, Vec3 axisY, double radius, double start, double sweep,
                     int steps, double width, int color, float opacity) {
        Vec3 previous = pointOnRing(center, axisX, axisY, radius, start);
        for (int i = 1; i <= steps; i++) {
            Vec3 next = pointOnRing(center, axisX, axisY, radius, start + sweep * i / steps);
            line(previous, next, width, color, opacity);
            previous = next;
        }
    }

    public static Vec3 pointOnRing(Vec3 c, Vec3 x, Vec3 y, double radius, double angle) {
        return c.add(x.scale(Math.cos(angle) * radius)).add(y.scale(Math.sin(angle) * radius));
    }

    private void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, float aa, float ab, float ac, float ad) {
        vertex(a, color, aa); vertex(b, color, ab); vertex(c, color, ac); vertex(d, color, ad);
    }
    private void vertex(Vec3 p, int color, float alpha) {
        vertices.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor((color >> 16) & 255, (color >> 8) & 255, color & 255, (int) (alpha * 255));
    }
}
