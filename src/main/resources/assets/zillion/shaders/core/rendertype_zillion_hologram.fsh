#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float GameTime;

// Params.x = hologram amount (0 = normal texture, 1 = full green hologram)
// Params.y = overall alpha
// Params.z = light-line glow strength (red stripes + cyan indicators)
// Params.w = sliding line pattern strength (finale)
// Params2.x = mode (0 = armor, 1 = drone)   Params2.y = white flash amount   Params2.z = rim strength
uniform vec4 Params;
uniform vec4 Params2;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;
in vec3 localPos;

out vec4 fragColor;

const vec3 HOLO_GREEN = vec3(0.30, 1.00, 0.55);
const vec3 HOLO_CORE  = vec3(0.75, 1.00, 0.85);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }

    float t = GameTime * 1200.0;
    float holo = clamp(Params.x, 0.0, 1.0);
    float alpha = clamp(Params.y, 0.0, 1.0);
    float redGlow = Params.z;
    float lines = Params.w;
    float mode = Params2.x;
    float flash = Params2.y;
    float rimStrength = Params2.z;

    // --- normal shaded colour (same as vanilla entity cutout) -------------------
    vec4 lit = tex * vertexColor * ColorModulator;
    lit.rgb = mix(overlayColor.rgb, lit.rgb, overlayColor.a);
    vec3 shaded = lit.rgb * lightMapColor.rgb;

    // light lines: red stripes + cyan indicator lights (the tech pattern, not the whole armor)
    float redness = clamp((tex.r - max(tex.g, tex.b) - 0.25) * 4.0, 0.0, 1.0);
    float cyan = clamp((min(tex.g, tex.b) - tex.r - 0.45) * 5.0, 0.0, 1.0) * step(0.75, tex.g);
    // texels with alpha 254 (instead of 255) are excluded from glowing (mouth + abdominal muscles)
    float glowAllowed = step(0.998, tex.a);
    float glowMask = max(redness, cyan) * glowAllowed;
    float pulse = 0.5 + 0.5 * sin(t * 0.35);
    float glowAmt = clamp(redGlow, 0.0, 2.5);
    vec3 emissive = tex.rgb * (0.8 + 0.6 * pulse) * glowMask * glowAmt;
    // lit texture -> unshaded texture -> additive emissive
    shaded = mix(shaded, tex.rgb, glowMask * min(glowAmt, 1.0));
    shaded += emissive;

    // --- hologram look -----------------------------------------------------------
    vec3 v = normalize(-viewPos);
    float ndv = abs(dot(normalize(viewNormal), v));
    float rim = pow(1.0 - ndv, 2.2);

    float lum = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
    // scan lines drifting upwards through the model + a fine grid
    float scan = 0.55 + 0.45 * sin(localPos.y * 90.0 - t * 1.8);
    float scan2 = smoothstep(0.90, 1.0, fract(localPos.y * 2.5 - t * 0.09)); // occasional bright sweep
    vec2 g = fract(localPos.xz * 24.0 + localPos.y * 3.0);
    float grid = (1.0 - smoothstep(0.0, 0.08, min(g.x, g.y))) * 0.6;
    float flick = 0.92 + 0.08 * sin(t * 7.0 + hash(floor(localPos.xy * 40.0)) * 6.28);

    vec3 holoCol = HOLO_GREEN * (0.35 + 0.9 * lum) * scan * flick;
    holoCol += HOLO_CORE * rim * (1.0 + rimStrength);
    holoCol += HOLO_GREEN * grid;
    holoCol += HOLO_CORE * scan2 * 0.8;
    float holoAlpha = clamp(0.35 + 0.5 * lum + rim * 0.8 + scan2 * 0.3, 0.0, 1.0);

    // --- sliding line pattern (finale) ---------------------------------------------
    float slide = fract(localPos.y * 6.0 + localPos.x * 2.0 - t * 0.28);
    float band = smoothstep(0.0, 0.06, slide) * (1.0 - smoothstep(0.06, 0.16, slide));
    float diag = fract((localPos.x + localPos.z) * 10.0 + t * 0.12);
    float diagBand = smoothstep(0.0, 0.04, diag) * (1.0 - smoothstep(0.04, 0.09, diag));
    vec3 lineCol = HOLO_CORE * (band + diagBand * 0.6) * lines * (0.6 + rim);

    vec3 col = mix(shaded, holoCol, holo);
    float a = mix(1.0, holoAlpha, holo) * alpha;

    if (mode > 0.5) {
        // drone: green tinted body with strong rim edge
        vec3 droneCol = tex.rgb * lightMapColor.rgb * vertexColor.rgb;
        droneCol = mix(droneCol, HOLO_GREEN * (0.4 + 0.8 * lum), 0.65);
        droneCol += HOLO_CORE * rim * (1.2 + rimStrength) + HOLO_GREEN * 0.35;
        col = mix(droneCol, holoCol, holo);
        a = alpha;
    }

    col += lineCol;
    col = mix(col, vec3(1.0), flash);
    a = max(a, flash * alpha);

    vec4 result = vec4(col, a);
    fragColor = linear_fog(result, vertexDistance, FogStart, FogEnd, FogColor);
}
