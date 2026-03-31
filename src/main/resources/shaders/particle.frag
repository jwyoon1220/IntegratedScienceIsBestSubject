#version 430 core

// Particle fragment shader. Uses additive blending for glow.

in  vec3  v_color;
in  float v_life;
in  float v_type;

out vec4 fragColor;

void main() {
    int itype = int(v_type + 0.5);

    // Point sprite: gl_PointCoord is [0..1], center is 0.5,0.5
    vec2 pc  = gl_PointCoord - 0.5;
    float d  = length(pc) * 2.0;  // 0 = center, 1 = edge

    float alpha = 0.0;
    vec3  col   = v_color;

    if (itype == 0) {
        // Nebula gas: soft gaussian blob, semi-transparent
        alpha = exp(-d * d * 3.0) * v_life * 0.6;
        col   = v_color;
    } else if (itype == 1) {
        // Supernova ejecta: bright additive blob
        float core = exp(-d * d * 8.0);
        float halo = exp(-d * d * 1.5) * 0.4;
        alpha = (core + halo) * v_life;
        // Fade from white-yellow to red as life decreases
        col = mix(vec3(1.0, 0.4, 0.1), vec3(1.0, 0.95, 0.6), v_life) * v_color;
    } else {
        // Accretion jet: tight linear blob
        alpha = exp(-d * d * 5.0) * v_life * 0.8;
        col   = mix(vec3(0.1, 0.3, 1.0), vec3(0.8, 0.9, 1.0), v_life);
    }

    if (alpha < 0.005) discard;
    fragColor = vec4(col, alpha);
}
