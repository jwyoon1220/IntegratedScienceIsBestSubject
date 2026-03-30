#version 430 core

// ── Grid Background Vertex Shader ───────────────────────────
// Renders a full-screen quad.  gl_VertexID maps 0..3 to a triangle-strip.
// Camera uniforms allow pan & zoom by offsetting and scaling the UV.

uniform vec2  u_cam_uv_offset; // camera offset in normalised UV space [0..1]
uniform float u_cam_zoom;      // camera zoom factor (>1 = zoomed in)

out vec2 v_uv;

void main() {
    // Full-screen triangle-strip covering NDC [-1,1]
    const vec2 positions[4] = vec2[4](
        vec2(-1.0, -1.0),
        vec2( 1.0, -1.0),
        vec2(-1.0,  1.0),
        vec2( 1.0,  1.0)
    );
    const vec2 uvs[4] = vec2[4](
        vec2(0.0, 0.0),
        vec2(1.0, 0.0),
        vec2(0.0, 1.0),
        vec2(1.0, 1.0)
    );

    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
    // Apply camera: the visible UV window is [offset, offset + 1/zoom]
    v_uv = u_cam_uv_offset + uvs[gl_VertexID] / u_cam_zoom;
}
