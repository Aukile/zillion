#version 150
uniform sampler2D EmissionSampler;
uniform sampler2D BloomSampler;
uniform float Strength;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec3 sharp = texture(EmissionSampler, texCoord).rgb;
    vec3 bloom = texture(BloomSampler, texCoord).rgb;
    fragColor = vec4(sharp + bloom * Strength, 0.0);
}
