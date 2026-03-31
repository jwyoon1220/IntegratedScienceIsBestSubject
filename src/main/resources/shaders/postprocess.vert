#version 430 core

// Full-screen quad vertex for post-processing passes.
out vec2 v_uv;

void main() {
    vec2 corners[4] = vec2[](
        vec2(-1,-1), vec2(1,-1), vec2(-1,1), vec2(1,1)
    );
    gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0);
    v_uv = corners[gl_VertexID] * 0.5 + 0.5;
}
