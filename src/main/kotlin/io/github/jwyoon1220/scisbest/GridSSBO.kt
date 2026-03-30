package io.github.jwyoon1220.scisbest

import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * GridSSBO — manages the 512×512 Cell grid on the GPU.
 *
 * std430 Cell layout (48 bytes):
 *   float u235_density        offset  0
 *   float u238_density        offset  4
 *   float pu239_density       offset  8
 *   float u233_density        offset 12
 *   float th232_density       offset 16
 *   float xe135_density       offset 20
 *   int   structure_type      offset 24
 *   float radiation_dose      offset 28
 *   float i135_density        offset 32
 *   float temperature         offset 36
 *   float thermal_conductivity offset 40
 *   float _pad                offset 44
 * Total: 48 bytes
 */
class GridSSBO(val gridWidth: Int = 512, val gridHeight: Int = 512) {

    companion object {
        const val BINDING      = 1
        const val STRIDE_BYTES = 48
        const val FLOATS       = STRIDE_BYTES / 4  // = 12
    }

    private val ssbo: Int = glGenBuffers()
    val cellCount: Int get() = gridWidth * gridHeight

    init {
        val sizeBytes = cellCount.toLong() * STRIDE_BYTES
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
        glBufferData(GL_SHADER_STORAGE_BUFFER, sizeBytes, GL_DYNAMIC_COPY)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
    }

    fun bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING, ssbo)
    }

    /**
     * Update a rectangular region of cells efficiently with glBufferSubData.
     * [cx0,cy0] to [cx1,cy1] inclusive, all cells given the same structure.
     */
    fun fillRegion(
        cx0: Int, cy0: Int, cx1: Int, cy1: Int,
        structureType: Int = 0,
        u235: Float = 0f, u238: Float = 0f, pu239: Float = 0f,
        u233: Float = 0f, th232: Float = 0f,
        temperature: Float = 20f
    ) {
        val w = (cx1 - cx0 + 1).coerceAtLeast(1)
        val h = (cy1 - cy0 + 1).coerceAtLeast(1)
        val floatBuf = MemoryUtil.memAllocFloat(w * h * FLOATS)

        for (dy in 0 until h) {
            for (dx in 0 until w) {
                val base = (dy * w + dx) * FLOATS
                // 대괄호 대신 .put(index, value) 사용
                floatBuf.put(base + 0, u235)
                floatBuf.put(base + 1, u238)
                floatBuf.put(base + 2, pu239)
                floatBuf.put(base + 3, u233)
                floatBuf.put(base + 4, th232)
                floatBuf.put(base + 5, 0f)
                floatBuf.put(base + 6, java.lang.Float.intBitsToFloat(structureType))
                floatBuf.put(base + 7, 0f)
                floatBuf.put(base + 8, 0f)
                floatBuf.put(base + 9, temperature)
                floatBuf.put(base + 10, 0.1f)
                floatBuf.put(base + 11, 0f)
            }
        }
        floatBuf.rewind()

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        for (dy in 0 until h) {
            val cy = (cy0 + dy).coerceIn(0, gridHeight - 1)
            val cx = cx0.coerceIn(0, gridWidth - 1)
            val startCell = cy * gridWidth + cx

            floatBuf.position(dy * w * FLOATS)
            floatBuf.limit((dy + 1) * w * FLOATS)

            // slice()를 사용하여 해당 행의 데이터만 전송
            glBufferSubData(
                GL_SHADER_STORAGE_BUFFER,
                startCell.toLong() * STRIDE_BYTES,
                floatBuf.slice()
            )
        }
        MemoryUtil.memFree(floatBuf)
    }

    /**
     * Read a single cell back to the CPU for tooltip display.
     */
    data class CellData(
        val u235: Float, val u238: Float, val pu239: Float,
        val u233: Float, val th232: Float, val xe135: Float,
        val structureType: Int, val radiationDose: Float,
        val i135: Float, val temperature: Float
    )

    fun readCell(cx: Int, cy: Int): CellData {
        val idx = cy.coerceIn(0, gridHeight - 1) * gridWidth + cx.coerceIn(0, gridWidth - 1)
        val buf = MemoryUtil.memAllocFloat(FLOATS)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, idx.toLong() * STRIDE_BYTES, buf)

        // 읽기 시에도 buf[index] 대신 buf.get(index) 사용
        val data = CellData(
            u235          = buf.get(0),
            u238          = buf.get(1),
            pu239         = buf.get(2),
            u233          = buf.get(3),
            th232         = buf.get(4),
            xe135         = buf.get(5),
            structureType = java.lang.Float.floatToRawIntBits(buf.get(6)),
            radiationDose = buf.get(7),
            i135          = buf.get(8),
            temperature   = buf.get(9)
        )
        MemoryUtil.memFree(buf)
        return data
    }

    /** Clear entire grid back to zero (uses GPU-side clear — no CPU upload). */
    fun clearAll() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        glClearBufferData(GL_SHADER_STORAGE_BUFFER, GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT, null as ByteBuffer?)
    }

    fun delete() {
        glDeleteBuffers(ssbo)
    }
}
