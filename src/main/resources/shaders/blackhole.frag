#version 430 core

// ════════════════════════════════════════════════════════════════
//  blackhole.frag — Interstellar-quality gravitational lensing
//
//  Algorithm:
//   1. For each screen pixel, check proximity to each black hole.
//   2. Apply Schwarzschild deflection angle to the ray direction.
//   3. Re-sample the scene texture at the deflected UV.
//   4. Add a physically-motivated accretion disk with Doppler shift.
//   5. Add a photon ring at the Schwarzschild radius (inner bright ring).
// ════════════════════════════════════════════════════════════════

in  vec2 v_uv;      // [0..1] screen UV
out vec4 fragColor;

uniform sampler2D u_scene;        // rendered scene (before lensing)
uniform vec2  u_resolution;       // viewport size (px)
uniform float u_time;             // seconds

// Up to 8 black holes
uniform int   u_num_bh;           // number of active black holes
uniform vec2  u_bh_screen[8];     // screen-space position [0..1]
uniform float u_bh_rs[8];         // Schwarzschild radius in screen UV fraction
uniform float u_bh_mass[8];       // mass in solar masses (for disk brightness)

// ── Noise helper ─────────────────────────────────────────────
float hash(float n) { return fract(sin(n) * 43758.5453); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

// ── Accretion disk ───────────────────────────────────────────
// Renders a thin glowing torus in screen-space around the BH.
// dir  : normalised direction from screen pixel to BH centre
// d    : distance in UV space from pixel to BH centre
// rs   : Schwarzschild radius (UV units)
// mass : solar masses (controls brightness)
vec4 accretionDisk(vec2 dir, float d, float rs, float mass) {
    // Disk spans  [1.5 rs .. 4.0 rs]  (inner edge = ISCO at 3rs for Schwarzschild)
    float inner = rs * 1.5;
    float outer = rs * 4.5;

    if (d < inner || d > outer) return vec4(0.0);

    // Radial profile: peak at ~2rs
    float t = (d - inner) / (outer - inner);   // 0 at inner, 1 at outer
    float radial = smoothstep(0.0, 0.3, t) * smoothstep(1.0, 0.5, t);
    radial = pow(radial, 1.5);

    // Doppler effect: approaching side (left of BH in our convention) is blue-shifted,
    // receding side is red-shifted.  Use the horizontal component of dir as proxy.
    float doppler = dir.x;  // -1 = left (blue), +1 = right (red)

    // Temperature profile (hotter = bluer near inner edge)
    float temp = 1.0 - t;   // hot at inner edge
    vec3 diskCol;
    if (doppler > 0.0) {
        // Receding: red-shifted — orange/red tones
        diskCol = mix(vec3(1.0, 0.5, 0.1), vec3(0.8, 0.2, 0.0), temp);
        diskCol *= (1.0 + doppler * 0.5);   // slight brightness asymmetry
    } else {
        // Approaching: blue-shifted — blue/white tones
        diskCol = mix(vec3(0.6, 0.8, 1.0), vec3(0.9, 0.95, 1.0), temp);
        diskCol *= (1.0 - doppler * 0.5);
    }

    // Disk brightness scales with mass
    float brightness = clamp(log(mass + 1.0) * 0.25, 0.1, 1.0);

    // Add some turbulent brightness variation for realism
    float noise = hash2(vec2(atan(dir.y, dir.x) * 3.0 + u_time * 0.8,
                             d * 40.0 + mass));
    brightness *= (0.7 + 0.3 * noise);

    return vec4(diskCol * brightness * radial, radial * brightness * 0.9);
}

// ── Photon ring ──────────────────────────────────────────────
// Bright thin ring right at the photon sphere (1.5 rs)
vec4 photonRing(float d, float rs) {
    float ringR = rs * 1.5;
    float width = rs * 0.12;
    float ring  = exp(-pow((d - ringR) / width, 2.0) * 4.0);
    return vec4(vec3(1.0, 0.95, 0.8) * ring * 1.5, ring);
}

// ── Gravitational lensing ────────────────────────────────────
// Deflects the UV sample point based on the BH's gravitational field.
// Uses an approximation of the weak-field deflection angle:
//   alpha = 2 * rs / b   (weak lensing, b = impact parameter)
// For strong lensing (b ~ rs), we use a stronger formula.
vec2 lensDeflect(vec2 uv, vec2 bhUV, float rs) {
    vec2  diff    = uv - bhUV;
    float aspect  = u_resolution.x / u_resolution.y;
    diff.x       *= aspect;                      // aspect-correct distance
    float b       = length(diff);                // impact parameter (UV units)

    if (b < rs * 0.5) return bhUV;              // inside event horizon: collapse to centre

    vec2  dir     = diff / b;

    // Schwarzschild deflection approximation
    // Full formula: alpha = 2*rs/b  (but clamped for numerical stability)
    float alpha   = clamp(2.0 * rs / (b + rs * 0.1), 0.0, 2.5);

    // For strong-field: also add a secondary lensing term
    float alpha2  = clamp(6.0 * rs * rs / (b * b + rs * rs), 0.0, 1.0);
    float totalAlpha = alpha + alpha2 * 0.3;

    // Deflect: move the sampling UV towards the BH
    vec2 deflected = uv - dir * totalAlpha * rs;
    // Undo aspect correction for x
    deflected.x = uv.x - (dir.x / aspect) * totalAlpha * rs;
    deflected.y = uv.y - dir.y * totalAlpha * rs;

    return deflected;
}

// ── Main ─────────────────────────────────────────────────────
void main() {
    if (u_num_bh == 0) {
        fragColor = texture(u_scene, v_uv);
        return;
    }

    vec2 sampledUV = v_uv;
    vec4 diskAccum = vec4(0.0);
    vec4 ringAccum = vec4(0.0);
    bool inHorizon = false;

    for (int i = 0; i < u_num_bh && i < 8; i++) {
        vec2  bhUV = u_bh_screen[i];
        float rs   = u_bh_rs[i];
        float mass = u_bh_mass[i];

        vec2  diff    = v_uv - bhUV;
        float aspect  = u_resolution.x / u_resolution.y;
        float dAspect = length(vec2(diff.x * aspect, diff.y));

        // Inside event horizon → pure black
        if (dAspect < rs * 0.5) {
            inHorizon = true;
            break;
        }

        // Apply lensing
        sampledUV = lensDeflect(sampledUV, bhUV, rs);

        // Accretion disk
        vec2 dir2d   = diff / max(length(diff), 0.0001);
        vec4 disk    = accretionDisk(dir2d, dAspect, rs, mass);
        diskAccum   += disk;

        // Photon ring
        ringAccum   += photonRing(dAspect, rs);
    }

    // Clamp UV to screen (out-of-screen wraps dark)
    bool outOfScreen = any(lessThan(sampledUV, vec2(0.0))) ||
                       any(greaterThan(sampledUV, vec2(1.0)));
    vec4 sceneCol = outOfScreen ? vec4(0.0, 0.0, 0.0, 1.0)
                                : texture(u_scene, sampledUV);

    if (inHorizon) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Composite: scene + disk (additive) + ring (additive)
    vec4 result = sceneCol;
    result.rgb += diskAccum.rgb * diskAccum.a;
    result.rgb += ringAccum.rgb * ringAccum.a;

    fragColor = vec4(result.rgb, 1.0);
}
