package com.atomengine

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * SelectionSSBO — 1-slot buffer used by the GPU hover-picking algorithm.
 *
 * std430 layout (8 bytes):
 *   int   hovered_neutron_index   offset 0  (-1 means none)
 *   float min_distance_bits       offset 4  (float stored as int bits for atomicMin)
 */
class SelectionSSBO {

    companion object {
        const val BINDING      = 3
        const val STRIDE_BYTES = 8
        // Large sentinel distance so any real neutron distance wins atomicMin.
        // 9999 world units exceeds the maximum diagonal of the 1024×1024 world.
        private val INIT_DIST_BITS = java.lang.Float.floatToRawIntBits(9999f)
    }

    private val ssbo: Int = glGenBuffers()

    init {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
        glBufferData(GL_SHADER_STORAGE_BUFFER, STRIDE_BYTES.toLong(), GL_DYNAMIC_COPY)
        reset()
    }

    fun bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
    }

    /** Reset selection each frame before compute dispatch. */
    fun reset() {
        val buf = MemoryUtil.memAllocInt(2)
        buf.put(0, -1)
        buf.put(1, INIT_DIST_BITS)
        buf.rewind()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, buf)
        MemoryUtil.memFree(buf)
    }

    /** Read back the single hovered neutron index (-1 if none). */
    fun readHoveredIndex(): Int {
        val buf = MemoryUtil.memAllocInt(2)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, buf)
        val idx = buf[0]
        MemoryUtil.memFree(buf)
        return idx
    }

    fun delete() {
        glDeleteBuffers(ssbo)
    }
}
