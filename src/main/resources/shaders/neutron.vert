#version 430 core

// ── Neutron Vertex Shader ────────────────────────────────────
// Uses gl_InstanceID to index directly into the NeutronBuffer SSBO,
// eliminating the need for a VBO entirely.

struct Neutron {
    vec2  position;
    vec2  velocity;
    float energy;
    int   isActive;
};

layout(std430, binding = 0) buffer NeutronBuffer {
    Neutron neutrons[];
};

uniform mat4  u_projection;      // orthographic projection matrix
uniform float u_point_size;      // base point size in pixels (e.g. 3.0)
uniform float u_world_w;
uniform float u_world_h;

out float v_energy;

void main() {
    Neutron n = neutrons[gl_InstanceID];

    if (n.isActive == 0) {
        // Discard inactive neutrons by placing them off-screen
        gl_Position  = vec4(-10.0, -10.0, 0.0, 1.0);
        gl_PointSize = 0.0;
        v_energy     = 0.0;
        return;
    }

    // Convert world position to NDC via orthographic projection
    gl_Position  = u_projection * vec4(n.position, 0.0, 1.0);
    gl_PointSize = u_point_size;
    v_energy     = n.energy;
}
