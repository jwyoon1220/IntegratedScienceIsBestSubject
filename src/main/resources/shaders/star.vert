#version 430 core

// Instanced billboard vertex shader for stellar bodies.
// One quad (6 vertices = 2 triangles) is drawn per body via gl_InstanceID.
// Body data is read from BodySSBO (binding 5).

// ── BodySSBO ─────────────────────────────────────────────────
// 16 floats per body:
//  [0..2]  position xyz
//  [3]     visual_radius (world units)
//  [4..6]  color rgb
//  [7]     type  (float bits: 0=star,1=planet,2=planetesimal,3=nebula)
//  [8]     state (float bits: StellarState ordinal)
//  [9]     temperature (Kelvin)
//  [10]    glow factor [0..1]
//  [11]    flags (float bits: bit0=isEarth)
//  [12..15] padding
layout(std430, binding = 5) buffer BodyBuffer { float bodyData[]; };

uniform mat4  u_view;
uniform mat4  u_proj;
uniform float u_time;

out vec2  v_uv;        // [-1..1] within billboard
out vec3  v_color;
out float v_glow;
out float v_radius;    // world-space radius (for size-based lod)
out float v_type;      // body type float
out float v_state;     // stellar state float
out float v_flags;

// Quad corners (two triangles, CCW)
const vec2 CORNERS[6] = vec2[6](
    vec2(-1,-1), vec2( 1,-1), vec2(-1, 1),
    vec2(-1, 1), vec2( 1,-1), vec2( 1, 1)
);

void main() {
    int   base = gl_InstanceID * 16;
    vec3  wpos = vec3(bodyData[base], bodyData[base+1], bodyData[base+2]);
    float rad  = bodyData[base+3];
    v_color    = vec3(bodyData[base+4], bodyData[base+5], bodyData[base+6]);
    v_type     = bodyData[base+7];
    v_state    = bodyData[base+8];
    // bodyData[base+9] = temperature (unused here, used in frag)
    v_glow     = bodyData[base+10];
    v_flags    = bodyData[base+11];
    v_radius   = rad;

    // Billboard: offset in camera right/up directions
    vec3 camRight = vec3(u_view[0][0], u_view[1][0], u_view[2][0]);
    vec3 camUp    = vec3(u_view[0][1], u_view[1][1], u_view[2][1]);

    vec2 corner = CORNERS[gl_VertexID % 6];
    v_uv = corner;

    vec3 worldPos = wpos
        + camRight * corner.x * rad
        + camUp    * corner.y * rad;

    gl_Position = u_proj * u_view * vec4(worldPos, 1.0);
}
