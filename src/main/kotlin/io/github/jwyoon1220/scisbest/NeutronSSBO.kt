package io.github.jwyoon1220.scisbest

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.Random

/**
 * NeutronSSBO — manages the GPU buffer for 500,000 Neutron structs.
 *
 * std430 layout per Neutron (32 bytes):
 *   vec2  position        offset  0  (8 bytes)
 *   vec2  velocity        offset  8  (8 bytes)
 *   float energy          offset 16  (4 bytes)
 *   int   isActive        offset 20  (4 bytes)
 *   padding               offset 24  (8 bytes — vec2 alignment)
 * Total: 32 bytes
 */
class NeutronSSBO(val maxNeutrons: Int = 500_000) {

    companion object {
        const val BINDING           = 0
        const val STRIDE_BYTES      = 32  // 2*vec2 + float + int + 8 pad
        const val MAX_NEUTRONS_CONST = 500_000
    }

    private val ssbo: Int = glGenBuffers()

    init {
        val sizeBytes = maxNeutrons.toLong() * STRIDE_BYTES
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
        glBufferData(GL_SHADER_STORAGE_BUFFER, sizeBytes, GL_DYNAMIC_COPY)
        // Initialise all slots to zero (isActive = 0)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
    }

    /** Bind this SSBO to its fixed binding point. */
    fun bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
    }

    /**
     * CPU-side activation of [count] neutrons starting at [offset],
     * placed at [worldX], [worldY] with random velocities.
     * Uses glBufferSubData for a minimal, targeted upload.
     */
    fun activateNeutrons(offset: Int, count: Int, worldX: Float, worldY: Float, energy: Float = 25f) {
        val buf = MemoryUtil.memAllocFloat(count * (STRIDE_BYTES / 4))
        val rng = Random()
        for (i in 0 until count) {
            val base = i * (STRIDE_BYTES / 4)
            val angle = (rng.nextFloat() * 2f * Math.PI).toFloat()
            val speed = 15f + rng.nextFloat() * 10f
            buf.put(base + 0, worldX)                        // position.x
            buf.put(base + 1, worldY)                        // position.y
            buf.put(base + 2, Math.cos(angle.toDouble()).toFloat() * speed)  // velocity.x
            buf.put(base + 3, Math.sin(angle.toDouble()).toFloat() * speed)  // velocity.y
            buf.put(base + 4, energy)                        // energy
            // isActive = 1 (int stored as float bits in float buffer)
            buf.put(base + 5, java.lang.Float.intBitsToFloat(1))
            buf.put(base + 6, 0f)                            // pad
            buf.put(base + 7, 0f)                            // pad
        }
        buf.rewind()
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glBufferSubData(
            GL_SHADER_STORAGE_BUFFER,
            offset.toLong() * STRIDE_BYTES,
            buf
        )
        MemoryUtil.memFree(buf)
    }

    /**
     * Read a single neutron's data back to the CPU for tooltip display.
     * Returns null if index is out of range or neutron is inactive.
     */
    data class NeutronData(
        val posX: Float, val posY: Float,
        val velX: Float, val velY: Float,
        val energy: Float, val isActive: Boolean
    )

    fun readNeutron(index: Int): NeutronData? {
        if (index < 0 || index >= maxNeutrons) return null
        val buf = MemoryUtil.memAllocFloat(STRIDE_BYTES / 4)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glGetBufferSubData(
            GL_SHADER_STORAGE_BUFFER,
            index.toLong() * STRIDE_BYTES,
            buf
        )
        val data = NeutronData(
            posX     = buf[0],
            posY     = buf[1],
            velX     = buf[2],
            velY     = buf[3],
            energy   = buf[4],
            isActive = java.lang.Float.floatToRawIntBits(buf[5]) == 1
        )
        MemoryUtil.memFree(buf)
        return if (data.isActive) data else null
    }

    fun delete() {
        glDeleteBuffers(ssbo)
    }
}
