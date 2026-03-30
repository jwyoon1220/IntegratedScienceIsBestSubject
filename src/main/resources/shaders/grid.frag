#version 430 core

// ── Grid Background Fragment Shader ─────────────────────────
// Reads Grid SSBO and draws:
//   mode 0 — Material view (colour by structure type)
//   mode 1 — Radiation heatmap (green→red overlay)
//   mode 2 — Temperature heatmap (black→blue→red→yellow→white)

#define GRID_WIDTH  512
#define GRID_HEIGHT 512

struct Cell {
    float u235_density;
    float u238_density;
    float pu239_density;
    float u233_density;
    float th232_density;
    float xe135_density;
    int   structure_type;
    float radiation_dose;
    float i135_density;
    float temperature;
    float thermal_conductivity;
    float _pad;
};

layout(std430, binding = 1) buffer GridBuffer {
    Cell cells[];
};

uniform int u_render_mode; // 0=material, 1=radiation, 2=temperature

in  vec2 v_uv;
out vec4 fragColor;

// ── Temperature colour palette ───────────────────────────────
vec3 tempPalette(float t) {
    // t in [0,1]: 0=black, 0.25=blue, 0.5=red, 0.75=yellow, 1=white
    vec3 c;
    if (t < 0.25) {
        c = mix(vec3(0.0, 0.0, 0.0), vec3(0.0, 0.0, 1.0), t / 0.25);
    } else if (t < 0.5) {
        c = mix(vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), (t - 0.25) / 0.25);
    } else if (t < 0.75) {
        c = mix(vec3(1.0, 0.0, 0.0), vec3(1.0, 1.0, 0.0), (t - 0.5) / 0.25);
    } else {
        c = mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 1.0, 1.0), (t - 0.75) / 0.25);
    }
    return c;
}

// ── Material base colour ─────────────────────────────────────
vec3 materialColour(Cell c) {
    // Fuel density visualisation
    float fuelTotal = c.u235_density + c.pu239_density + c.u238_density
                    + c.u233_density + c.th232_density;
    vec3 base = vec3(0.05, 0.05, 0.08); // dark background

    if (c.structure_type == 1) base = vec3(0.1, 0.3, 0.7);   // light water — blue
    if (c.structure_type == 2) base = vec3(0.1, 0.5, 0.8);   // heavy water — lighter blue
    if (c.structure_type == 3) base = vec3(0.3, 0.3, 0.3);   // graphite — grey
    if (c.structure_type == 4) base = vec3(0.1, 0.6, 0.1);   // control rod — green
    if (c.structure_type == 5) base = vec3(0.6, 0.6, 0.6);   // reflector — silver
    if (c.structure_type == 6) base = vec3(0.4, 0.35, 0.3);  // wall — brown

    // Blend in fuel tint (yellow-orange for enriched fuel)
    if (fuelTotal > 0.0) {
        float t  = clamp(fuelTotal / 50.0, 0.0, 1.0);
        vec3 fuelColour = mix(vec3(0.8, 0.6, 0.1), vec3(1.0, 0.9, 0.0), t);
        base = mix(base, fuelColour, t * 0.8);
    }

    // Xe-135 darkening overlay
    if (c.xe135_density > 0.0) {
        float xeT = clamp(c.xe135_density * 0.5, 0.0, 0.6);
        base = mix(base, vec3(0.1, 0.0, 0.15), xeT);
    }

    return base;
}

void main() {
    // Map UV to grid cell
    int cx = clamp(int(v_uv.x * float(GRID_WIDTH)),  0, GRID_WIDTH  - 1);
    int cy = clamp(int(v_uv.y * float(GRID_HEIGHT)), 0, GRID_HEIGHT - 1);
    int i  = cy * GRID_WIDTH + cx;
    Cell c = cells[i];

    vec3 colour = vec3(0.0);

    if (u_render_mode == 0) {
        // ── Material View ────────────────────────────────────
        colour = materialColour(c);

    } else if (u_render_mode == 1) {
        // ── Radiation Heatmap ────────────────────────────────
        float t    = clamp(c.radiation_dose / 1000.0, 0.0, 1.0);
        vec3  base = materialColour(c) * 0.3; // dim background
        vec3  heat = mix(vec3(0.0, 0.5, 0.0), vec3(1.0, 0.0, 0.0), t);
        colour     = mix(base, heat, t);

    } else {
        // ── Temperature Heatmap (mode 2) ─────────────────────
        float t    = clamp(c.temperature / 3000.0, 0.0, 1.0);
        colour     = tempPalette(t);
    }

    fragColor = vec4(colour, 1.0);
}
