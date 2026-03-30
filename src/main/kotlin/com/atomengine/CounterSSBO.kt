package com.atomengine

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * CounterSSBO — 3-slot atomic counter buffer.
 *
 * std430 layout (12 bytes, 3 × uint):
 *   uint active_neutron_count  offset 0
 *   uint total_fission_events  offset 4
 *   uint frame_seed            offset 8
 */
class CounterSSBO {

    companion object {
        const val BINDING      = 2
        const val STRIDE_BYTES = 12
    }

    private val ssbo: Int = glGenBuffers()

    init {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
        glBufferData(GL_SHADER_STORAGE_BUFFER, STRIDE_BYTES.toLong(), GL_DYNAMIC_COPY)
        resetCounters()
    }

    fun bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
    }

    /** Read all three counters back to the CPU in one call. */
    data class Counters(
        val activeNeutrons: Long,
        val totalFissions: Long,
        val frameSeed: Long
    )

    fun read(): Counters {
        val buf = MemoryUtil.memAllocInt(3)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, buf)
        val c = Counters(
            activeNeutrons = Integer.toUnsignedLong(buf[0]),
            totalFissions  = Integer.toUnsignedLong(buf[1]),
            frameSeed      = Integer.toUnsignedLong(buf[2])
        )
        MemoryUtil.memFree(buf)
        return c
    }

    /** Update frame_seed uniform every frame (written via glBufferSubData offset 8). */
    fun updateSeed(seed: Int) {
        val buf = MemoryUtil.memAllocInt(1)
        buf.put(0, seed)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 8L, buf)
        MemoryUtil.memFree(buf)
    }

    /** Zero out active_neutron_count and total_fission_events. */
    fun resetCounters() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
    }

    fun delete() {
        glDeleteBuffers(ssbo)
    }
}
