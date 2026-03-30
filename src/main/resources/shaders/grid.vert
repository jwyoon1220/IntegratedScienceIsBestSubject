#version 430 core

// ── Grid Background Vertex Shader ───────────────────────────
// Renders a full-screen quad.  gl_VertexID maps 0..5 to two triangles.

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
    v_uv        = uvs[gl_VertexID];
}
