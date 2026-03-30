package io.github.jwyoon1220.scisbest

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil

/**
 * ShaderUtils — compiles and links GLSL programs.
 */
object ShaderUtils {

    /** Load a shader source from the classpath resources. */
    fun loadSource(resourcePath: String): String =
        ShaderUtils::class.java.classLoader
            .getResourceAsStream(resourcePath)
            ?.bufferedReader()
            ?.readText()
            ?: error("Shader resource not found: $resourcePath")

    /** Compile a single shader stage. Throws on compile error. */
    fun compileShader(source: String, type: Int): Int {
        val id = glCreateShader(type)
        glShaderSource(id, source)
        glCompileShader(id)
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            val log = glGetShaderInfoLog(id)
            glDeleteShader(id)
            error("Shader compile error:\n$log")
        }
        return id
    }

    /** Link a full render program from vertex + fragment shader IDs. */
    fun linkProgram(vararg shaderIds: Int): Int {
        val prog = glCreateProgram()
        shaderIds.forEach { glAttachShader(prog, it) }
        glLinkProgram(prog)
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            val log = glGetProgramInfoLog(prog)
            glDeleteProgram(prog)
            error("Program link error:\n$log")
        }
        shaderIds.forEach { glDeleteShader(it) }
        return prog
    }

    /** Convenience: compile + link a compute shader from a resource path. */
    fun computeProgram(resourcePath: String): Int {
        val src   = loadSource(resourcePath)
        val shade = compileShader(src, GL_COMPUTE_SHADER)
        return linkProgram(shade)
    }

    /** Convenience: compile + link vert/frag program from resource paths. */
    fun renderProgram(vertPath: String, fragPath: String): Int {
        val vert = compileShader(loadSource(vertPath), GL_VERTEX_SHADER)
        val frag = compileShader(loadSource(fragPath), GL_FRAGMENT_SHADER)
        return linkProgram(vert, frag)
    }
}
