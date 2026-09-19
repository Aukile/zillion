#version 150

#moj_import <fog.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GameTime;
// Params.x = style (0 = tech double ring, 1 = soft flare, 2 = ground ring / shock wave)
// Params.y = spin speed multiplier, Params.z = inner detail amount, Params.w = radius scale (grows)
uniform vec4 Params;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

#define PI 3.14159265

float ring(float r, float r0, float w) {
    return exp(-pow((r - r0) / w, 2.0));
}

void main() {
    float t = GameTime * 1200.0;
    vec2 p = (texCoord0 - 0.5) * 2.0;
    float r = length(p) / max(Params.w, 0.001);
    float ang = atan(p.y, p.x);
    float style = Params.x;
    float a = 0.0;
    vec3 col = vertexColor.rgb;

    if (style < 0.5) {
        // --- two tech rings with rotating detail between/inside them ------------
        float spin = t * 0.06 * Params.y;
        float outer = ring(r, 0.94, 0.035) * 1.2;
        float inner = ring(r, 0.66, 0.03);
        float ticks = step(0.55, fract((ang + spin) / (2.0 * PI) * 48.0));
        float tickBand = smoothstep(0.70, 0.73, r) * (1.0 - smoothstep(0.86, 0.89, r)) * ticks * 0.55;
        float seg = step(0.30, fract((ang - spin * 1.7) / (2.0 * PI) * 6.0 + 0.1));
        float segBand = smoothstep(0.885, 0.9, r) * (1.0 - smoothstep(0.92, 0.935, r)) * seg * 0.9;
        float dashes = step(0.5, fract((ang + spin * 2.3) / (2.0 * PI) * 18.0));
        float dashBand = smoothstep(0.50, 0.52, r) * (1.0 - smoothstep(0.58, 0.60, r)) * dashes * 0.6;
        float hex = 0.0;
        {
            vec2 q = p / max(Params.w, 0.001) * 5.0;
            vec2 h = abs(fract(q) - 0.5);
            hex = (1.0 - smoothstep(0.0, 0.08, min(h.x, h.y))) * (1.0 - smoothstep(0.35, 0.48, r)) * 0.25 * Params.z;
        }
        float centre = exp(-r * r * 9.0) * 0.55 * Params.z;
        float crossHair = (1.0 - smoothstep(0.0, 0.012, abs(p.x))) + (1.0 - smoothstep(0.0, 0.012, abs(p.y)));
        crossHair *= (1.0 - smoothstep(0.55, 0.62, r)) * 0.35 * Params.z;
        a = outer + inner + tickBand + segBand + dashBand + hex + centre + crossHair;
        a *= 1.0 - smoothstep(0.98, 1.0, r);
        col = mix(col, vec3(1.0), clamp(outer * 0.6 + inner * 0.4, 0.0, 1.0));
    }
    else if (style < 1.5) {
        // --- soft radial flare with a bright core --------------------------------------
        float core = exp(-r * r * 18.0);
        float halo = exp(-r * r * 3.0) * 0.5;
        float rays = pow(abs(sin(ang * 3.0 + t * 0.2)), 8.0) * exp(-r * r * 4.0) * 0.5;
        a = (core * 1.6 + halo + rays) * (1.0 - smoothstep(0.85, 1.0, r));
        col = mix(col, vec3(1.0), clamp(core * 1.5, 0.0, 1.0));
    }
    else {
        // --- ground ring / expanding shock wave --------------------------------------
        float main = ring(r, 0.90, 0.05) * 1.3;
        float second = ring(r, 0.72, 0.02) * 0.7;
        float ticks = step(0.5, fract((ang + t * 0.03) / (2.0 * PI) * 64.0));
        float tickBand = smoothstep(0.78, 0.80, r) * (1.0 - smoothstep(0.86, 0.88, r)) * ticks * 0.6;
        float fill = exp(-pow((r - 0.85) / 0.25, 2.0)) * 0.12;
        a = (main + second + tickBand + fill) * (1.0 - smoothstep(0.97, 1.0, r));
        col = mix(col, vec3(1.0), clamp(main * 0.5, 0.0, 1.0));
    }

    a *= vertexColor.a;
    if (a < 0.004) {
        discard;
    }
    col *= ColorModulator.rgb;
    fragColor = vec4(col, min(a, 1.0) * ColorModulator.a) * linear_fog_fade(vertexDistance, FogStart, FogEnd);
}
