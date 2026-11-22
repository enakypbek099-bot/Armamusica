package com.armamus.app

import java.io.File
import java.io.RandomAccessFile

/**
 * Index of a raw AC-3 / E-AC-3 (Dolby Digital / Digital Plus / Atmos JOC) file: .ac3 .eac3 .ec3
 *
 * ExoPlayer cannot seek in such "naked" streams (it restarts the song from the beginning).
 * We walk through the frames once, remember where every frame starts, and the bridge then
 * starts playback from the frame that matches the time the person asked for.
 */
class RawAudioIndex(private val offsets: LongArray, private val unitUs: Long) {

    val durationMs: Long get() = offsets.size.toLong() * unitUs / 1000L

    /** index of the frame that contains the given time */
    fun indexFor(ms: Long): Int {
        val i = (ms.coerceAtLeast(0L) * 1000L / unitUs).toInt()
        return i.coerceIn(0, offsets.size - 1)
    }

    fun timeMs(index: Int): Long = index.toLong() * unitUs / 1000L

    fun offsetOf(index: Int): Long = offsets[index]

    private class Frame(val size: Int, val startsUnit: Boolean, val samples: Int, val rate: Int)

    companion object {
        private val AC3_KBPS = intArrayOf(
            32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640
        )
        private val AC3_RATE = intArrayOf(48000, 44100, 32000)
        private val EAC3_RATE2 = intArrayOf(24000, 22050, 16000)
        private val EAC3_BLOCKS = intArrayOf(1, 2, 3, 6)

        private fun parse(h: ByteArray): Frame? {
            if (h[0] != 0x0B.toByte() || h[1] != 0x77.toByte()) return null
            val b2 = h[2].toInt() and 0xFF
            val b3 = h[3].toInt() and 0xFF
            val b4 = h[4].toInt() and 0xFF
            val b5 = h[5].toInt() and 0xFF
            val bsid = b5 shr 3
            if (bsid <= 10) {
                // AC-3
                val fscod = b4 shr 6
                val frmsizecod = b4 and 0x3F
                if (fscod == 3 || frmsizecod >= 38) return null
                val kbps = AC3_KBPS[frmsizecod shr 1]
                val words = when (fscod) {
                    0 -> kbps * 2
                    2 -> kbps * 3
                    else -> (kbps * 1000L * 1536L / 705600L).toInt() + (frmsizecod and 1)
                }
                return Frame(words * 2, true, 1536, AC3_RATE[fscod])
            }
            if (bsid in 11..16) {
                // E-AC-3
                val strmtyp = b2 shr 6
                val substream = (b2 shr 3) and 7
                val frmsiz = ((b2 and 7) shl 8) or b3
                val size = (frmsiz + 1) * 2
                if (size < 16) return null
                val fscod = b4 shr 6
                val second = (b4 shr 4) and 3
                val rate: Int
                val blocks: Int
                if (fscod == 3) {
                    if (second == 3) return null
                    rate = EAC3_RATE2[second]
                    blocks = 6
                } else {
                    rate = AC3_RATE[fscod]
                    blocks = EAC3_BLOCKS[second]
                }
                // a dependent substream (strmtyp 1) belongs to the previous frame, it does not add time
                return Frame(size, strmtyp != 1 && substream == 0, blocks * 256, rate)
            }
            return null
        }

        /** position of the next valid frame at or after [from], or -1 */
        private fun findSync(raf: RandomAccessFile, from: Long, len: Long): Long {
            val buf = ByteArray(16384)
            var pos = from
            val hdr = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), len - pos).toInt())
                if (n < 8) return -1L
                var i = 0
                while (i + 1 < n) {
                    if (buf[i] == 0x0B.toByte() && buf[i + 1] == 0x77.toByte()) {
                        raf.seek(pos + i)
                        if (raf.read(hdr, 0, 8) == 8 && parse(hdr) != null) return pos + i
                    }
                    i++
                }
                pos += (n - 1).toLong()
            }
            return -1L
        }

        fun build(file: File): RawAudioIndex? {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val len = raf.length()
                    val hdr = ByteArray(8)
                    val list = ArrayList<Long>()
                    var pos = 0L
                    var samples = 0
                    var rate = 0
                    var lost = 0
                    while (pos + 8 <= len) {
                        raf.seek(pos)
                        if (raf.read(hdr, 0, 8) < 8) break
                        val f = parse(hdr)
                        if (f == null) {
                            if (++lost > 50) break
                            val next = findSync(raf, pos + 1, len)
                            if (next < 0L) break
                            if (list.isEmpty() && next > 65536L) return null
                            pos = next
                            continue
                        }
                        if (f.startsUnit) {
                            if (list.isEmpty()) {
                                samples = f.samples
                                rate = f.rate
                            }
                            list.add(pos)
                        }
                        pos += f.size.toLong()
                    }
                    if (list.size < 2 || rate <= 0) {
                        null
                    } else {
                        RawAudioIndex(list.toLongArray(), samples * 1_000_000L / rate)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
