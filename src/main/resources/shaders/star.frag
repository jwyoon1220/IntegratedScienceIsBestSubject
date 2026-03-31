#version 430 core

// Star / planet billboard fragment shader.
// Produces a glowing circle. Stars get multi-layered glow matching their
// blackbody color; planets get a solid disc with optional atmosphere rim.

in  vec2  v_uv;
in  vec3  v_color;
in  float v_glow;
in  float v_radius;
in  float v_type;
in  float v_state;
in  float v_flags;

out vec4  fragColor;

uniform float u_time;

// ── Helpers ──────────────────────────────────────────────────

// Soft circle SDF: returns 0 inside core, 1 at edge
float circleSDF(vec2 uv, float r) {
    return length(uv) / r;
}

// Simplex-like hash for sparkle
float hash(float n) { return fract(sin(n) * 43758.5453); }

// ── Glow profile ─────────────────────────────────────────────
// Multi-layer glow simulating the stellar corona / scattering
float glowProfile(float d, float glowAmt) {
    float inner = exp(-d * d * 3.0);           // tight core bloom
    float mid   = exp(-d * d * 0.6) * 0.35;   // mid corona
    float outer = exp(-d * 1.2)     * 0.15 * glowAmt; // diffuse outer halo
    return inner + mid + outer;
}

// ── Main ─────────────────────────────────────────────────────
void main() {
    int itype  = int(v_type  + 0.5);
    int istate = int(v_state + 0.5);
    int iflags = int(v_flags + 0.5);
    bool isEarth = (iflags & 1) != 0;

    float d = length(v_uv);  // 0 = center, 1 = billboard edge

    // ── NEBULA PARTICLE (type 3) ──────────────────────────────
    if (itype == 3) {
        float alpha = smoothstep(1.0, 0.0, d) * 0.25;
        fragColor = vec4(v_color, alpha);
        return;
    }

    // ── PLANETESIMAL (type 2) ─────────────────────────────────
    if (itype == 2) {
        float circle = smoothstep(0.5, 0.45, d);
        if (circle < 0.01) discard;
        fragColor = vec4(v_color * 0.8, circle);
        return;
    }

    // ── PLANET (type 1) ──────────────────────────────────────
    if (itype == 1) {
        float circle = smoothstep(0.55, 0.45, d);
        if (circle < 0.01) discard;

        vec3  col = v_color;

        // Earth: blue with cloud swirl
        if (isEarth) {
            // Simple cloud pattern using trig
            vec2 p = v_uv;
            float clouds = 0.5 + 0.5 * sin(p.x * 8.0 + u_time * 0.05)
                               * cos(p.y * 6.0 + u_time * 0.03);
            clouds = smoothstep(0.4, 0.8, clouds) * 0.3;
            col = mix(col, vec3(1.0), clouds);
        }

        // Thin atmosphere rim
        float rim = pow(1.0 - max(0.0, d), 3.0);
        col = mix(col, v_color * 1.5, rim * 0.4);

        // Specular highlight (simple Blinn-Phong with fixed light)
        vec2 lightDir = normalize(vec2(0.4, 0.6));
        float spec = pow(max(0.0, dot(normalize(lightDir - v_uv), vec2(0.0, 1.0))), 16.0);
        col += spec * 0.2;

        fragColor = vec4(col, circle);
        return;
    }

    // ── STAR ─────────────────────────────────────────────────
    // state 8 = WHITE_DWARF, 10 = BLACK_DWARF, 11 = BLACK_HOLE handled specially
    // state 9 = SUPERNOVA

    if (istate == 11) {
        // Black hole: event horizon disk (rendered via blackhole shader, but
        // also show a dark disc here for solidity)
        float disc = smoothstep(0.4, 0.38, d);
        fragColor = vec4(0.0, 0.0, 0.0, disc);
        return;
    }

    if (istate == 9) {
        // Supernova: bright expanding disc with color bands
        float r = v_uv.x; float s = v_uv.y;
        float angle = atan(s, r);
        float band  = 0.5 + 0.5 * sin(angle * 6.0 + u_time * 3.0);
        vec3 snCol  = mix(vec3(1.0, 0.8, 0.2), vec3(1.0, 0.3, 0.1), band);
        float disc  = smoothstep(1.0, 0.6, d);
        float glow  = glowProfile(d, 2.0);
        fragColor   = vec4(snCol * glow + snCol * disc * 0.5, min(1.0, glow + disc));
        return;
    }

    // Main star rendering
    float g = glowProfile(d, v_glow);

    // Core disc (hard circle, clipped at 0.15 world UV)
    float core = smoothstep(0.18, 0.14, d);

    // Diffraction spikes for bright stars (star state 5 = main sequence and up)
    float spike = 0.0;
    if (v_glow > 0.4) {
        // 4-point diffraction spike
        float sx = abs(v_uv.x);
        float sy = abs(v_uv.y);
        spike  = exp(-sy * 80.0) * exp(-sx * 3.0) * 0.5;
        spike += exp(-sx * 80.0) * exp(-sy * 3.0) * 0.5;
        spike += exp(-(sx + sy) * 40.0) * 0.15;  // diagonal
        spike *= v_glow * smoothstep(1.0, 0.2, d);
    }

    // Chromatic aberration on corona (slight color fringing)
    float dR = length(v_uv * 1.00);
    float dB = length(v_uv * 0.97);
    vec3  chromaCol = vec3(
        exp(-dR * dR * 0.6) * 0.2,
        0.0,
        exp(-dB * dB * 0.6) * 0.2
    );

    vec3  finalCol = v_color * (g + core * 2.0 + spike) + chromaCol;
    float alpha    = clamp(g * 2.0 + core + spike * 0.8, 0.0, 1.0);

    if (alpha < 0.005) discard;
    fragColor = vec4(finalCol, alpha);
}
