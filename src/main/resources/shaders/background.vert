#version 430 core

// Full-screen quad vertex shader for background starfield + nebula tint.
// Emits a clip-space quad using gl_VertexID (no VBO required).

out vec2 v_uv;   // [0..1] screen UV

void main() {
    // Two-triangle strip
    vec2 corners[4] = vec2[](
        vec2(-1.0, -1.0),
        vec2( 1.0, -1.0),
        vec2(-1.0,  1.0),
        vec2( 1.0,  1.0)
    );
    gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0);
    v_uv = corners[gl_VertexID] * 0.5 + 0.5;
}
