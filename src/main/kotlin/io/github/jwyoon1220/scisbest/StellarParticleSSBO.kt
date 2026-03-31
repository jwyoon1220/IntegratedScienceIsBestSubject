package io.github.jwyoon1220.scisbest

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil

/**
 * StellarParticleSSBO — GPU buffer for nebula / supernova particles.
 *
 * std430 layout per particle (48 bytes):
 *   vec4  pos_life  [px, py, pz, life]          offset  0  (16 bytes)
 *   vec4  vel_size  [vx, vy, vz, size]          offset 16  (16 bytes)
 *   vec4  color     [r, g, b, type_float]        offset 32  (16 bytes)
 * Total: 48 bytes
 *
 * life:  [0..1] fraction of max life remaining; particle dies when <= 0
 * type:  0=nebula_gas, 1=supernova_ejecta, 2=accretion_jet
 */
class StellarParticleSSBO(val maxParticles: Int = 500_000) {

    companion object {
        const val BINDING      = 6
        const val STRIDE_BYTES = 48
        const val FLOATS       = STRIDE_BYTES / 4  // 12 floats per particle
    }

    private val ssbo: Int = glGenBuffers()

    init {
        val sizeBytes = maxParticles.toLong() * STRIDE_BYTES
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
        glBufferData(GL_SHADER_STORAGE_BUFFER, sizeBytes, GL_DYNAMIC_DRAW)
    }

    fun bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
    }

    /**
     * Upload particle data from a packed float array.
     * [count] particles, each represented by [FLOATS] floats.
     */
    fun upload(data: FloatArray, count: Int) {
        if (count <= 0) return
        val upload = count.coerceAtMost(maxParticles)
        val buf = MemoryUtil.memAllocFloat(upload * FLOATS)
        buf.put(data, 0, upload * FLOATS)
        buf.flip()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, buf)
        MemoryUtil.memFree(buf)
    }

    fun delete() {
        glDeleteBuffers(ssbo)
    }
}
