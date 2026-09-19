#version 150

#moj_import <fog.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GameTime;

in float vertexDistance;
in vec2 texCoord0;   // u = along the line (0..1), v = across (0..1)
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float t = GameTime * 1200.0;
    float d = abs(texCoord0.y - 0.5) * 2.0;           // 0 at the centre, 1 at the edges
    float core = exp(-d * d * 22.0);
    float glow = exp(-d * d * 3.0) * 0.45;
    // energy pulses travelling along the line + slow breathing
    float flow = 0.70 + 0.30 * sin(texCoord0.x * 34.0 - t * 2.1);
    float flow2 = 0.85 + 0.15 * sin(texCoord0.x * 9.0 + t * 0.7);
    // bright sparks racing along the core
    float spark = pow(max(0.0, sin(texCoord0.x * 60.0 - t * 4.0)), 24.0) * core;
    float end = smoothstep(0.0, 0.10, texCoord0.x) * (1.0 - smoothstep(0.78, 1.0, texCoord0.x));

    float a = (core + glow) * flow * flow2 * end * vertexColor.a;
    vec3 col = mix(vertexColor.rgb, vec3(1.0), core * 0.8 + spark);
    col *= ColorModulator.rgb;
    if (a < 0.004) {
        discard;
    }
    fragColor = vec4(col, a * ColorModulator.a) * linear_fog_fade(vertexDistance, FogStart, FogEnd);
}
