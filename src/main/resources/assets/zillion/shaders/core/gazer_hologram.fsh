#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FxTime;
uniform float FxIntensity;

in vec4 vertexColor;
in vec2 texCoord;
in vec3 viewPosition;
in vec3 viewNormal;
out vec4 fragColor;

float line(float distanceToLine, float width) {
    return 1.0 - smoothstep(width, width + max(fwidth(distanceToLine), 0.025), distanceToLine);
}

void main() {
    vec4 base = texture(Sampler0, texCoord);
    if (base.a < 0.02) discard;
    vec2 uv = texCoord * vec2(96.0, 96.0);
    vec2 cell = floor(uv);
    vec2 p = fract(uv);
    float seed = fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
    // Connected right-angle traces with sparse terminals, rather than a plain checker grid.
    float horizontal = line(abs(p.y - 0.5), 0.028) * step(p.x, 0.78);
    float vertical = line(abs(p.x - 0.75), 0.028) * step(0.48, p.y);
    float trace = max(horizontal, vertical) * step(0.28, seed);
    float terminal = (1.0 - smoothstep(0.065, 0.11, length(p - vec2(0.22, 0.5)))) * step(0.65, seed);
    float pulse = 0.45 + 0.55 * pow(0.5 + 0.5 * sin(FxTime * 3.5 - cell.x * 0.47 - cell.y * 0.31), 4.0);
    float scanPhase = fract(texCoord.y * 5.0 - FxTime * 0.32);
    float scan = 1.0 - smoothstep(0.0, 0.09, abs(scanPhase - 0.5));
    float fineScan = 0.85 + 0.15 * sin(texCoord.y * 1600.0 - FxTime * 9.0);
    vec3 normal = normalize(viewNormal + vec3(0.00001));
    vec3 eye = normalize(-viewPosition + vec3(0.00001));
    float edge = pow(1.0 - abs(dot(normal, eye)), 2.5);
    float luminance = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 cyan = vec3(0.10, 0.78, 1.0);
    vec3 rgb = cyan * (0.09 + luminance * 0.12 + trace * pulse * 0.80 + terminal * pulse + scan * 0.35 + edge * 0.65);
    rgb += base.rgb * 0.10;
    float alpha = base.a * vertexColor.a * ColorModulator.a * fineScan;
    fragColor = vec4(rgb * vertexColor.rgb * ColorModulator.rgb * clamp(FxIntensity, 0.0, 4.0), alpha);
}
