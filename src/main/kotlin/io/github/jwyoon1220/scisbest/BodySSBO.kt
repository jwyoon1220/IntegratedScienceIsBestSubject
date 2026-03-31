package io.github.jwyoon1220.scisbest

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil

/**
 * BodySSBO — GPU buffer for StellarEngine body rendering data.
 *
 * std430 layout per entry (64 bytes):
 *   vec4  position_r   [x, y, z, visual_radius]   offset  0  (16 bytes)
 *   vec4  color_type   [r, g, b, type_float]       offset 16  (16 bytes)
 *   vec4  state_temp   [state, temperature, glow, flags]  offset 32  (16 bytes)
 *   vec4  padding      [0, 0, 0, 0]                offset 48  (16 bytes)
 * Total: 64 bytes
 *
 * type: 0=star, 1=planet, 2=planetesimal, 3=nebula_particle
 * state: StellarState ordinal
 * flags: bit 0 = isEarth
 */
class BodySSBO(val maxBodies: Int = 100_000) {

    companion object {
        const val BINDING      = 5
        const val STRIDE_BYTES = 64
        const val FLOATS       = STRIDE_BYTES / 4  // 16 floats per body
    }

    private val ssbo: Int = glGenBuffers()

    init {
        val sizeBytes = maxBodies.toLong() * STRIDE_BYTES
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
        glBufferData(GL_SHADER_STORAGE_BUFFER, sizeBytes, GL_DYNAMIC_DRAW)
    }

    fun bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
    }

    /**
     * Upload rendering data for [count] bodies from the CPU list.
     * [data] must be a packed float array:
     *   for each body 16 floats: px, py, pz, radius, cr, cg, cb, type,
     *                             state, temperature, glow, flags, 0,0,0,0
     */
    fun upload(data: FloatArray, count: Int) {
        if (count <= 0) return
        val buf = MemoryUtil.memAllocFloat(count * FLOATS)
        buf.put(data, 0, count * FLOATS)
        buf.flip()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, buf)
        MemoryUtil.memFree(buf)
    }

    fun delete() {
        glDeleteBuffers(ssbo)
    }
}
