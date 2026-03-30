#version 430 core

// ── Neutron Fragment Shader ──────────────────────────────────
// Draws a mathematically perfect circle via gl_PointCoord.
// Colour is interpolated by energy:
//   fast (>=15)  → blue/white
//   thermal (<15) → red/yellow

in  float v_energy;
out vec4  fragColor;

void main() {
    // Discard pixels outside the unit circle
    vec2 coord = gl_PointCoord * 2.0 - 1.0;
    if (dot(coord, coord) > 1.0) discard;

    // Soft edge for anti-aliasing
    float d     = length(coord);
    float alpha = 1.0 - smoothstep(0.7, 1.0, d);

    // Energy colour palette
    // Thermal: red (1,0.2,0) → yellow (1,1,0)
    // Fast:    orange (1,0.5,0) → white (1,1,1)
    vec3 colour;
    if (v_energy < 15.0) {
        float t  = clamp(v_energy / 15.0, 0.0, 1.0);
        colour   = mix(vec3(1.0, 0.1, 0.0), vec3(1.0, 1.0, 0.0), t);
    } else {
        float t  = clamp((v_energy - 15.0) / 15.0, 0.0, 1.0);
        colour   = mix(vec3(0.2, 0.5, 1.0), vec3(1.0, 1.0, 1.0), t);
    }

    fragColor = vec4(colour, alpha);
}
