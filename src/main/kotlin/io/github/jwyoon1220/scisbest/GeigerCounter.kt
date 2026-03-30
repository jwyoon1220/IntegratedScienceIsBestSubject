package io.github.jwyoon1220.scisbest

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.random.Random

/**
 * GeigerCounter — 핵분열 이벤트 발생 시 클릭 사운드를 재생하는 가이거 계수기.
 * javax.sound.sampled을 이용해 프로그래밍 방식으로 클릭 사운드를 합성합니다.
 * 별도의 오디오 파일 없이 JDK 기본 라이브러리만 사용합니다.
 */
class GeigerCounter {

    companion object {
        private const val SAMPLE_RATE          = 44100
        private const val CLICK_DURATION_MS    = 12          // 클릭 지속시간 (ms)
        private val CLICK_DURATION_SAMPLES     = SAMPLE_RATE * CLICK_DURATION_MS / 1000  // 529 samples
        private const val MAX_QUEUED_CLICKS    = 8
    }

    // 프리-렌더링된 클릭 파형 (Little-endian 16-bit PCM)
    private val clickSample: ByteArray = generateClickSample()

    private val clickQueue   = LinkedBlockingQueue<Unit>(MAX_QUEUED_CLICKS)
    private val active       = AtomicBoolean(true)
    private val audioLine: SourceDataLine?
    private val workerThread: Thread

    // 외부에서 읽을 수 있는 통계
    var totalClicks: Long = 0L
        private set

    init {
        var line: SourceDataLine? = null
        try {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val info   = DataLine.Info(SourceDataLine::class.java, format)
            if (AudioSystem.isLineSupported(info)) {
                line = (AudioSystem.getLine(info) as SourceDataLine).also { dl ->
                    dl.open(format, CLICK_DURATION_SAMPLES * 2 * 4)
                    dl.start()
                }
            }
        } catch (_: Exception) { /* 오디오 장치 없음 — 무음 동작 */ }
        audioLine = line

        workerThread = Thread({
            while (active.get() || clickQueue.isNotEmpty()) {
                try {
                    val pending = clickQueue.poll(100L, TimeUnit.MILLISECONDS)
                    if (pending != null) {
                        audioLine?.write(clickSample, 0, clickSample.size)
                        totalClicks++
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "geiger-counter")
        workerThread.isDaemon = true
        workerThread.start()
    }

    /**
     * 새 핵분열 이벤트 수에 따라 클릭 사운드를 예약합니다.
     * 핵분열이 많을수록 더 많은 클릭을 예약하지만 [MAX_QUEUED_CLICKS]를 초과하지 않습니다.
     */
    fun triggerFissions(newFissions: Long) {
        if (newFissions <= 0L) return
        val clickCount = when {
            newFissions >= 1000 -> 3
            newFissions >= 100  -> 2
            else                -> 1
        }
        repeat(clickCount) { clickQueue.offer(Unit) }
    }

    /** 오디오 시스템이 사용 가능한지 여부를 반환합니다. */
    val isAudioAvailable: Boolean get() = audioLine != null

    /** 백그라운드 스레드와 오디오 라인을 정리합니다. */
    fun cleanup() {
        active.set(false)
        workerThread.interrupt()
        workerThread.join(500L)
        audioLine?.drain()
        audioLine?.close()
    }

    // ── 클릭 파형 합성 ────────────────────────────────────────────
    private fun generateClickSample(): ByteArray {
        val rng   = Random.Default
        val bytes = ByteArray(CLICK_DURATION_SAMPLES * 2)
        for (i in 0 until CLICK_DURATION_SAMPLES) {
            // 지수 감쇠 엔벨로프 * 백색 잡음 (가이거 계수기 클릭 모사)
            val envelope = kotlin.math.exp(-i.toDouble() * 9.0 / CLICK_DURATION_SAMPLES).toFloat()
            val noise    = (rng.nextFloat() * 2f - 1f) * envelope * 0.75f
            val sample   = (noise * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            bytes[i * 2]     = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
