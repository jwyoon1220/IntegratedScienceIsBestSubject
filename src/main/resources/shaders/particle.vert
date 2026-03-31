#version 430 core

// Particle vertex shader (point sprites) for nebula gas and supernova ejecta.
// Particle data is fetched from StellarParticleSSBO (binding 6) by gl_VertexID.

// 12 floats per particle:
//  [0..2]  position xyz
//  [3]     life  [0..1]
//  [4..6]  velocity xyz (unused in vertex, used in CPU update)
//  [7]     size (world units)
//  [8..10] color rgb
//  [11]    type (0=nebula, 1=supernova, 2=jet)
layout(std430, binding = 6) buffer ParticleBuffer { float pdata[]; };

uniform mat4  u_view;
uniform mat4  u_proj;
uniform vec2  u_resolution;  // viewport (width, height)

out vec3  v_color;
out float v_life;
out float v_type;

void main() {
    int base = gl_VertexID * 12;

    vec3  pos  = vec3(pdata[base], pdata[base+1], pdata[base+2]);
    float life = pdata[base+3];
    float size = pdata[base+7];
    v_color    = vec3(pdata[base+8], pdata[base+9], pdata[base+10]);
    v_life     = life;
    v_type     = pdata[base+11];

    vec4 clip = u_proj * u_view * vec4(pos, 1.0);
    gl_Position = clip;

    // Scale point size by projected size
    float dist = length((u_view * vec4(pos, 1.0)).xyz);
    float projSize = size / max(dist, 0.01);
    // Convert from world-space fraction to pixels
    float fovFactor = u_proj[1][1];  // cot(fov/2)
    gl_PointSize = clamp(projSize * u_resolution.y * fovFactor * 0.5, 1.0, 64.0);
}
