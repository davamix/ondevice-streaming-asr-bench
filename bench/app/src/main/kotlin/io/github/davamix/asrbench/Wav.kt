package io.github.davamix.asrbench

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader for exactly the format the corpus is built in:
 * 16 kHz mono PCM16.
 *
 * It refuses anything else rather than resampling. A silent format mismatch
 * between arms would show up as a latency or accuracy difference and would be
 * almost impossible to trace back, so the corpus builder normalises everything
 * up front (`scripts/build_corpus.py`) and this reader just enforces that.
 */
object Wav {

    const val REQUIRED_SAMPLE_RATE = 16000

    class Audio(
        val samples: ShortArray,
        val sampleRate: Int,
    ) {
        val durationMs: Long get() = samples.size * 1000L / sampleRate
    }

    fun read(file: File): Audio {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "${file.name}: too short to be a WAV" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(tag(bb, 0) == "RIFF") { "${file.name}: not RIFF" }
        require(tag(bb, 8) == "WAVE") { "${file.name}: not WAVE" }

        var pos = 12
        var sampleRate = -1
        var channels = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataLen = -1

        while (pos + 8 <= bytes.size) {
            val id = tag(bb, pos)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val audioFormat = bb.getShort(body).toInt()
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    bitsPerSample = bb.getShort(body + 14).toInt()
                    require(audioFormat == 1) {
                        "${file.name}: audio format $audioFormat, expected 1 (PCM)"
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataLen = size
                }
            }
            // Chunks are word-aligned: an odd size is followed by a pad byte.
            pos = body + size + (size and 1)
        }

        require(dataOffset >= 0) { "${file.name}: no data chunk" }
        require(sampleRate == REQUIRED_SAMPLE_RATE) {
            "${file.name}: $sampleRate Hz, expected $REQUIRED_SAMPLE_RATE"
        }
        require(channels == 1) { "${file.name}: $channels channels, expected mono" }
        require(bitsPerSample == 16) { "${file.name}: $bitsPerSample-bit, expected 16" }

        val usable = minOf(dataLen, bytes.size - dataOffset)
        val n = usable / 2
        val out = ShortArray(n)
        val sb = ByteBuffer.wrap(bytes, dataOffset, usable)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        sb.get(out)
        return Audio(out, sampleRate)
    }

    private fun tag(bb: ByteBuffer, at: Int): String =
        String(ByteArray(4) { bb.get(at + it) }, Charsets.US_ASCII)
}
