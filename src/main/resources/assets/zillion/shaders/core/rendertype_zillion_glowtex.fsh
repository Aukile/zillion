#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GameTime;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float t = GameTime * 1200.0;
    vec4 tex = texture(Sampler0, texCoord0);
    // cheap bloom: average of neighbouring samples
    vec2 px = vec2(1.0 / 256.0);
    float halo = 0.0;
    halo += texture(Sampler0, texCoord0 + vec2(px.x, 0.0)).a;
    halo += texture(Sampler0, texCoord0 - vec2(px.x, 0.0)).a;
    halo += texture(Sampler0, texCoord0 + vec2(0.0, px.y)).a;
    halo += texture(Sampler0, texCoord0 - vec2(0.0, px.y)).a;
    halo += texture(Sampler0, texCoord0 + px * 2.0).a;
    halo += texture(Sampler0, texCoord0 - px * 2.0).a;
    halo += texture(Sampler0, texCoord0 + vec2(px.x, -px.y) * 2.0).a;
    halo += texture(Sampler0, texCoord0 - vec2(px.x, -px.y) * 2.0).a;
    halo /= 8.0;

    float shimmer = 0.85 + 0.15 * sin(texCoord0.y * 40.0 + t * 1.3);
    float a = (tex.a + halo * 0.55) * shimmer * vertexColor.a;
    vec3 col = mix(vertexColor.rgb, vec3(1.0), tex.a * 0.35) ;
    col = mix(col, tex.rgb * 1.2, tex.a * 0.5);
    if (a < 0.004) {
        discard;
    }
    fragColor = vec4(col * ColorModulator.rgb, min(a, 1.0) * ColorModulator.a) * linear_fog_fade(vertexDistance, FogStart, FogEnd);
}
