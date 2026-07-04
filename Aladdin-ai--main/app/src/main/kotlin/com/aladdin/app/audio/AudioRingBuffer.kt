package com.aladdin.app.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AudioRingBuffer — lock-safe circular buffer for continuous raw PCM audio.
 *
 * The background mic writes frames in; the wake-word detector reads the last
 * [capacityFrames] frames at any time without blocking the writer.
 *
 * @param capacityFrames  number of PCM short frames the buffer holds (default 3 s at 16 kHz)
 */
class AudioRingBuffer(private val capacityFrames: Int = 48_000) {

    private val buffer = ShortArray(capacityFrames)
    private var writePos = 0
    private var totalWritten = 0L
    private val lock = ReentrantLock()

    /** Write [data] into the ring buffer, overwriting oldest data if full. */
    fun write(data: ShortArray, offset: Int = 0, length: Int = data.size - offset) {
        lock.withLock {
            var remaining = length
            var src = offset
            while (remaining > 0) {
                val chunk = minOf(remaining, capacityFrames - writePos)
                System.arraycopy(data, src, buffer, writePos, chunk)
                writePos = (writePos + chunk) % capacityFrames
                totalWritten += chunk
                src += chunk
                remaining -= chunk
            }
        }
    }

    /**
     * Copy the most recent [frameCount] frames into [out].
     * @return actual number of frames copied (may be less if buffer not yet full)
     */
    fun readLatest(out: ShortArray, frameCount: Int = minOf(out.size, capacityFrames)): Int {
        lock.withLock {
            val available = minOf(totalWritten, capacityFrames.toLong()).toInt()
            val toCopy = minOf(frameCount, available)
            if (toCopy == 0) return 0

            val readStart = ((writePos - toCopy) + capacityFrames) % capacityFrames
            val firstChunk = minOf(toCopy, capacityFrames - readStart)
            System.arraycopy(buffer, readStart, out, 0, firstChunk)
            if (firstChunk < toCopy) {
                System.arraycopy(buffer, 0, out, firstChunk, toCopy - firstChunk)
            }
            return toCopy
        }
    }

    /** Total frames written since creation (monotonically increasing). */
    fun totalFramesWritten(): Long = lock.withLock { totalWritten }

    /** Reset the buffer to empty. */
    fun clear() {
        lock.withLock {
            buffer.fill(0)
            writePos = 0
            totalWritten = 0L
        }
    }

    val size: Int get() = capacityFrames
}
