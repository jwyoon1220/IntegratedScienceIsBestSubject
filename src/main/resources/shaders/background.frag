#version 430 core

// Procedural starfield background for the stellar simulation.

in  vec2 v_uv;
out vec4 fragColor;

uniform float u_time;          // seconds, for twinkle animation
uniform vec2  u_resolution;    // viewport size in pixels
uniform vec3  u_nebula_color;  // tint for nebula cloud (set to 0 when none)
uniform float u_nebula_alpha;  // [0..1] nebula overlay strength

// ── Hash / noise ─────────────────────────────────────────────

float hash(vec2 p) {
    p = fract(p * vec2(234.34, 435.345));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

float hash3(vec3 p) {
    p = fract(p * vec3(234.34, 435.345, 127.1));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y + p.z);
}

// Smooth noise
float smoothNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i + vec2(0,0)), hash(i + vec2(1,0)), u.x),
        mix(hash(i + vec2(0,1)), hash(i + vec2(1,1)), u.x),
        u.y
    );
}

float fbm(vec2 p, int octaves) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < octaves; i++) {
        v += a * smoothNoise(p);
        p *= 2.03; a *= 0.5;
    }
    return v;
}

// ── Star layer ───────────────────────────────────────────────

// Renders a layer of procedural stars on an infinite tiled grid.
// scale controls star density.
vec3 starLayer(vec2 uv, float scale, float brightness, float twinkleAmt) {
    vec2 grid = floor(uv * scale);
    vec2 cell = fract(uv * scale) - 0.5;

    // Jitter star position inside cell
    float h1 = hash(grid);
    float h2 = hash(grid + 73.13);
    float h3 = hash(grid + 157.47);
    vec2  jitter = vec2(h1, h2) - 0.5;

    float dist = length(cell - jitter * 0.7);
    float starR = 0.01 + h3 * 0.015;   // star radius in cell-UV space

    // Twinkle: subtle brightness flicker
    float twinkle = 1.0 + twinkleAmt * sin(u_time * (2.0 + h1 * 5.0) + h2 * 6.28);

    float glow  = exp(-dist * dist / (starR * starR * 4.0));
    float core  = smoothstep(starR, 0.0, dist);

    float val = (glow * 0.5 + core) * brightness * twinkle;

    // Star color temperature: blue-white to reddish
    vec3  col = mix(vec3(1.0, 0.85, 0.7), vec3(0.7, 0.85, 1.0), h1);
    return col * val;
}

// ── Main ─────────────────────────────────────────────────────

void main() {
    vec2 uv = v_uv;
    // Aspect-correct UV for star layers
    vec2 aUV = uv * vec2(u_resolution.x / u_resolution.y, 1.0);

    // Deep space base gradient (very dark blue-black)
    vec3 bg = mix(vec3(0.0, 0.0, 0.01), vec3(0.01, 0.02, 0.05), uv.y);

    // Three star layers at different densities / sizes
    vec3 stars  = starLayer(aUV, 60.0,  0.9, 0.15)
                + starLayer(aUV, 120.0, 0.6, 0.10)
                + starLayer(aUV, 240.0, 0.4, 0.05);

    // Milky Way haze: two overlapping FBM noise bands
    float band = smoothstep(0.0, 0.5, fbm(uv * 3.0 + vec2(0.0, 1.0), 5));
    float band2= smoothstep(0.1, 0.6, fbm(uv * 4.0 + vec2(1.7, 0.3), 4));
    float mw   = (band * 0.06 + band2 * 0.04);
    vec3  milkyWay = vec3(0.5, 0.6, 0.9) * mw;

    vec3 color = bg + stars + milkyWay;

    // Nebula overlay (used when simulation has a nebula)
    if (u_nebula_alpha > 0.0) {
        float neb = fbm(uv * 2.5 + vec2(0.3, 0.7), 6);
        neb = pow(max(0.0, neb - 0.3), 1.5);
        color += u_nebula_color * neb * u_nebula_alpha;
    }

    fragColor = vec4(color, 1.0);
}
