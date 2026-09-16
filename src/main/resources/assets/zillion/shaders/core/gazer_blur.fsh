#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D SceneDepth;
uniform vec2 Direction;
uniform mat4 InverseProjection;
in vec2 texCoord;
out vec4 fragColor;

float viewDepth(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    vec4 view = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return abs(view.z / max(abs(view.w), 0.000001));
}

void main() {
    float centerDepth = viewDepth(texCoord);
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    // Nine fixed taps per axis. Reject scene-depth discontinuities to avoid halo leakage
    // across an opaque wall edge; source geometry was already tested against copied depth.
    for (int i = -4; i <= 4; ++i) {
        vec2 uv = clamp(texCoord + Direction * float(i), vec2(0.0), vec2(1.0));
        float weight = exp(-float(i * i) / 8.0);
        float difference = abs(viewDepth(uv) - centerDepth);
        weight *= 1.0 - smoothstep(max(0.15, centerDepth * 0.025), max(0.30, centerDepth * 0.05), difference);
        sum += texture(DiffuseSampler, uv).rgb * weight;
        weightSum += weight;
    }
    fragColor = vec4(sum / max(weightSum, 0.0001), 1.0);
}
