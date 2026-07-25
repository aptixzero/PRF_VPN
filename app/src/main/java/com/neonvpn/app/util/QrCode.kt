package com.neonvpn.app.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * v6.3 — a tiny, dependency-free QR Code (model 2) encoder.
 *
 * The Download-Links page needs to render a QR code that a nearby phone can scan
 * to receive the APK over Bluetooth. Pulling in ZXing just for that would add
 * ~500 KB to every APK, so this is a complete, self-contained encoder written
 * from the ISO/IEC 18004 spec:
 *
 *   • **Byte mode only** — the payload is always a URL / share token, so the
 *     alphanumeric and kanji modes are simply not needed.
 *   • **Error-correction level chosen per call** (L/M/Q/H). The share page uses
 *     M, which tolerates ~15 % damage — plenty for a screen-to-camera scan while
 *     keeping the module count (and therefore the visual density) low.
 *   • **Automatic version selection** (1 … 40): it picks the smallest version
 *     whose data capacity fits the payload, so short URLs produce big, chunky,
 *     easy-to-scan modules.
 *   • **All 8 mask patterns are evaluated** with the spec's four penalty rules
 *     and the best one is used — this is what makes the code scan reliably on
 *     cheap cameras in poor light.
 *
 * Everything is pure Kotlin arithmetic over `ByteArray`s: no allocations in the
 * hot loops beyond the matrices themselves, so encoding a URL takes well under a
 * millisecond and can safely be done on the main thread.
 */
object QrCode {

    /** Error-correction level. Higher = more redundancy = denser code. */
    enum class Ecc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    // ───────────────────────────────────────────────────────────── public ──

    /**
     * Encode [text] and render it into a square [Bitmap] of roughly [sizePx]
     * pixels (the true size is rounded down to a whole number of modules so the
     * code is always pixel-crisp — a scaled/blurry QR is a scan failure).
     *
     * @param quietZone modules of white margin around the code. The spec
     *                  mandates 4; anything less can defeat a scanner.
     */
    fun bitmap(
        text: String,
        sizePx: Int = 720,
        ecc: Ecc = Ecc.MEDIUM,
        dark: Int = Color.BLACK,
        light: Int = Color.WHITE,
        quietZone: Int = 4
    ): Bitmap {
        val matrix = encode(text, ecc)
        val n = matrix.size
        val total = n + quietZone * 2
        // Integer scale keeps every module an exact block of pixels.
        val scale = (sizePx / total).coerceAtLeast(1)
        val dim = total * scale

        val pixels = IntArray(dim * dim) { light }
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!matrix[y][x]) continue
                val px = (x + quietZone) * scale
                val py = (y + quietZone) * scale
                for (dy in 0 until scale) {
                    val row = (py + dy) * dim
                    for (dx in 0 until scale) pixels[row + px + dx] = dark
                }
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
    }

    /**
     * Encode [text] into a boolean module matrix (`true` == dark module).
     * Exposed separately so callers can render it however they like.
     */
    fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = smallestVersionFor(data.size, ecc)
            ?: throw IllegalArgumentException("payload too large for a QR code")

        val bits = buildDataBits(data, version, ecc)
        val codewords = interleave(bits, version, ecc)
        return renderMatrix(codewords, version, ecc)
    }

    // ────────────────────────────────────────────────── data bit assembly ──

    /**
     * Build the padded data bit-stream: mode indicator, char count, payload,
     * terminator, byte alignment, then the alternating 0xEC/0x11 pad bytes the
     * spec prescribes.
     */
    private fun buildDataBits(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val capacityBits = dataCodewords(version, ecc) * 8
        val bb = BitBuffer(capacityBits)

        bb.append(0b0100, 4)                                  // byte mode
        bb.append(data.size, charCountBits(version))          // length
        for (b in data) bb.append(b.toInt() and 0xFF, 8)      // payload

        // terminator (up to 4 zero bits) + pad to a byte boundary
        val remaining = capacityBits - bb.length
        bb.append(0, minOf(4, remaining))
        bb.append(0, (8 - bb.length % 8) % 8)

        // alternating pad bytes until the capacity is filled
        var pad = 0xEC
        while (bb.length < capacityBits) {
            bb.append(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        return bb.toBytes()
    }

    /** Byte-mode character-count indicator width for a given version. */
    private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    // ──────────────────────────────────────────────── EC + block interleave ──

    /**
     * Split the data codewords into the version's error-correction blocks,
     * compute each block's Reed–Solomon parity, then interleave data and parity
     * exactly as the spec requires.
     */
    private fun interleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val numBlocks = numErrorBlocks(version, ecc)
        val totalEcc = eccCodewordsPerBlock(version, ecc) * numBlocks
        val rawCount = totalCodewords(version)
        val shortBlockLen = (rawCount - totalEcc) / numBlocks
        val numShortBlocks = numBlocks - rawCount % numBlocks
        val eccLen = eccCodewordsPerBlock(version, ecc)

        val blocks = ArrayList<ByteArray>(numBlocks)
        val eccBlocks = ArrayList<ByteArray>(numBlocks)
        val generator = rsGenerator(eccLen)

        var k = 0
        for (i in 0 until numBlocks) {
            val len = shortBlockLen - eccLen + (if (i < numShortBlocks) 0 else 1)
            val block = data.copyOfRange(k, k + len)
            k += len
            blocks.add(block)
            eccBlocks.add(rsRemainder(block, generator))
        }

        val result = ByteArray(rawCount)
        var idx = 0
        // interleave data codewords
        val maxData = blocks.maxOf { it.size }
        for (i in 0 until maxData) {
            for (b in blocks) if (i < b.size) result[idx++] = b[i]
        }
        // interleave ecc codewords
        for (i in 0 until eccLen) {
            for (b in eccBlocks) result[idx++] = b[i]
        }
        return result
    }

    /** Reed–Solomon divisor polynomial for [degree] parity codewords. */
    private fun rsGenerator(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    /** Remainder of [data] divided by the generator — i.e. the parity bytes. */
    private fun rsRemainder(data: ByteArray, generator: ByteArray): ByteArray {
        val result = ByteArray(generator.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor
                    gfMul(generator[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    /** Multiply in GF(2^8) modulo the QR primitive polynomial 0x11D. */
    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ───────────────────────────────────────────────────── matrix rendering ──

    private fun renderMatrix(codewords: ByteArray, version: Int, ecc: Ecc): Array<BooleanArray> {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(modules, reserved, version)
        drawCodewords(modules, reserved, codewords, size)

        // Evaluate all 8 masks and keep the least-penalised one — this is what
        // makes the code robust for real cameras.
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(modules, reserved, mask, size)
            drawFormatBits(modules, reserved, ecc, mask, size)
            val p = penalty(modules, size)
            if (p < bestPenalty) { bestPenalty = p; bestMask = mask }
            applyMask(modules, reserved, mask, size)   // XOR again to undo
        }
        applyMask(modules, reserved, bestMask, size)
        drawFormatBits(modules, reserved, ecc, bestMask, size)
        return modules
    }

    private fun drawFunctionPatterns(
        m: Array<BooleanArray>, r: Array<BooleanArray>, version: Int
    ) {
        val size = m.size

        // timing patterns
        for (i in 0 until size) {
            setFn(m, r, 6, i, i % 2 == 0)
            setFn(m, r, i, 6, i % 2 == 0)
        }

        // three finder patterns + their separators
        drawFinder(m, r, 3, 3)
        drawFinder(m, r, size - 4, 3)
        drawFinder(m, r, 3, size - 4)

        // alignment patterns
        val align = alignmentPositions(version)
        for (i in align.indices) {
            for (j in align.indices) {
                // skip the three corners occupied by finder patterns
                if ((i == 0 && j == 0) ||
                    (i == 0 && j == align.size - 1) ||
                    (i == align.size - 1 && j == 0)
                ) continue
                drawAlignment(m, r, align[i], align[j])
            }
        }

        // version information (version >= 7)
        if (version >= 7) drawVersionBits(m, r, version, size)

        // reserve the format-information areas
        for (i in 0..8) {
            if (i != 6) { reserve(r, i, 8); reserve(r, 8, i) }
        }
        reserve(r, 8, 8)
        for (i in 0..7) reserve(r, size - 1 - i, 8)
        for (i in 0..7) reserve(r, 8, size - 1 - i)
        // the permanent dark module
        setFn(m, r, 8, size - 8, true)
    }

    private fun drawFinder(m: Array<BooleanArray>, r: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in m.indices || y !in m.indices) continue
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFn(m, r, x, y, d != 2 && d != 4)
            }
        }
    }

    private fun drawAlignment(m: Array<BooleanArray>, r: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val x = cx + dx
                val y = cy + dy
                if (x !in m.indices || y !in m.indices) continue
                setFn(m, r, x, y, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }
    }

    private fun drawVersionBits(
        m: Array<BooleanArray>, r: Array<BooleanArray>, version: Int, size: Int
    ) {
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val bit = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFn(m, r, a, b, bit)
            setFn(m, r, b, a, bit)
        }
    }

    private fun drawFormatBits(
        m: Array<BooleanArray>, r: Array<BooleanArray>, ecc: Ecc, mask: Int, size: Int
    ) {
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412

        for (i in 0..5) setFn(m, r, 8, i, getBit(bits, i))
        setFn(m, r, 8, 7, getBit(bits, 6))
        setFn(m, r, 8, 8, getBit(bits, 7))
        setFn(m, r, 7, 8, getBit(bits, 8))
        for (i in 9..14) setFn(m, r, 14 - i, 8, getBit(bits, i))

        for (i in 0..7) setFn(m, r, size - 1 - i, 8, getBit(bits, i))
        for (i in 8..14) setFn(m, r, 8, size - 15 + i, getBit(bits, i))
        setFn(m, r, 8, size - 8, true)
    }

    /** Zig-zag place the codeword bits into every non-function module. */
    private fun drawCodewords(
        m: Array<BooleanArray>, r: Array<BooleanArray>, data: ByteArray, size: Int
    ) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (r[y][x]) continue
                    if (i < data.size * 8) {
                        m[y][x] = getBit(data[i ushr 3].toInt(), 7 - (i and 7))
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(
        m: Array<BooleanArray>, r: Array<BooleanArray>, mask: Int, size: Int
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (r[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) m[y][x] = !m[y][x]
            }
        }
    }

    /** The spec's four penalty rules — lower is a more scannable code. */
    private fun penalty(m: Array<BooleanArray>, size: Int): Int {
        var result = 0

        // Rule 1: runs of 5+ same-coloured modules in a row/column
        for (y in 0 until size) {
            var runColor = m[y][0]; var runLen = 1
            for (x in 1 until size) {
                if (m[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) result += 3 else if (runLen > 5) result++
                } else { runColor = m[y][x]; runLen = 1 }
            }
        }
        for (x in 0 until size) {
            var runColor = m[0][x]; var runLen = 1
            for (y in 1 until size) {
                if (m[y][x] == runColor) {
                    runLen++
                    if (runLen == 5) result += 3 else if (runLen > 5) result++
                } else { runColor = m[y][x]; runLen = 1 }
            }
        }

        // Rule 2: 2x2 blocks of the same colour
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
            }
        }

        // Rule 3: finder-like 1:1:3:1:1 patterns
        for (y in 0 until size) {
            for (x in 0 until size - 6) {
                if (matchesFinderRun(m, y, x, true, size)) result += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0 until size - 6) {
                if (matchesFinderRun(m, y, x, false, size)) result += 40
            }
        }

        // Rule 4: deviation from a 50 % dark ratio
        var dark = 0
        for (row in m) for (c in row) if (c) dark++
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total
        result += k * 10
        return result
    }

    private fun matchesFinderRun(
        m: Array<BooleanArray>, y: Int, x: Int, horizontal: Boolean, size: Int
    ): Boolean {
        val pat = booleanArrayOf(true, false, true, true, true, false, true)
        for (i in 0..6) {
            val v = if (horizontal) m[y][x + i] else m[y + i][x]
            if (v != pat[i]) return false
        }
        // require 4 light modules on one side
        var light = 0
        for (i in 1..4) {
            val a = if (horizontal) x - i else y - i
            if (a < 0) { light++; continue }
            val v = if (horizontal) m[y][a] else m[a][x]
            if (!v) light++ else break
        }
        if (light >= 4) return true
        light = 0
        for (i in 7..10) {
            val a = if (horizontal) x + i else y + i
            if (a >= size) { light++; continue }
            val v = if (horizontal) m[y][a] else m[a][x]
            if (!v) light++ else break
        }
        return light >= 4
    }

    // ───────────────────────────────────────────────────────────── helpers ──

    private fun setFn(
        m: Array<BooleanArray>, r: Array<BooleanArray>, x: Int, y: Int, dark: Boolean
    ) {
        if (x !in m.indices || y !in m.indices) return
        m[y][x] = dark
        r[y][x] = true
    }

    private fun reserve(r: Array<BooleanArray>, x: Int, y: Int) {
        if (x in r.indices && y in r.indices) r[y][x] = true
    }

    private fun getBit(v: Int, i: Int): Boolean = ((v ushr i) and 1) != 0

    private fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val n = version / 7 + 2
        val step = if (version == 32) 26
        else (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2
        val result = IntArray(n)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in n - 1 downTo 1) { result[i] = pos; pos -= step }
        return result
    }

    /** Total codewords (data + ecc) available in a version. */
    private fun totalCodewords(version: Int): Int {
        val size = version * 4 + 17
        var modules = size * size
        modules -= 8 * 8 * 3                                   // finders + separators
        modules -= 15 * 2 + 1                                  // format info
        modules -= (size - 16) * 2                             // timing
        if (version >= 2) {
            val n = version / 7 + 2
            modules -= (n - 1) * (n - 1) * 25                  // alignment patterns
            modules -= (n - 2) * 2 * 20                        // minus timing overlap
            if (version >= 7) modules -= 6 * 3 * 2             // version info
        }
        return modules / 8
    }

    private fun dataCodewords(version: Int, ecc: Ecc): Int =
        totalCodewords(version) -
            eccCodewordsPerBlock(version, ecc) * numErrorBlocks(version, ecc)

    private fun smallestVersionFor(byteLen: Int, ecc: Ecc): Int? {
        for (v in 1..40) {
            val cap = dataCodewords(v, ecc)
            val header = 4 + charCountBits(v)
            if (cap * 8 >= header + byteLen * 8) return v
        }
        return null
    }

    private fun eccCodewordsPerBlock(version: Int, ecc: Ecc): Int =
        ECC_PER_BLOCK[ecc.ordinal][version]

    private fun numErrorBlocks(version: Int, ecc: Ecc): Int =
        NUM_BLOCKS[ecc.ordinal][version]

    // Index [ecc][version]; index 0 unused so `version` maps directly.
    // Order matches Ecc.ordinal: LOW, MEDIUM, QUARTILE, HIGH.
    private val ECC_PER_BLOCK = arrayOf(
        intArrayOf(0, 7,10,15,20,26,18,20,24,30,18,20,24,26,30,22,24,28,30,28,28,28,28,30,30,26,28,30,30,30,30,30,30,30,30,30,30,30,30,30,30),
        intArrayOf(0,10,16,26,18,24,16,18,22,22,26,30,22,22,24,24,28,28,26,26,26,26,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28,28),
        intArrayOf(0,13,22,18,26,18,24,18,22,20,24,28,26,24,20,30,24,28,28,26,30,28,30,30,30,30,28,30,30,30,30,30,30,30,30,30,30,30,30,30,30),
        intArrayOf(0,17,28,22,16,22,28,26,26,24,28,24,28,22,24,24,30,28,28,26,28,30,24,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30)
    )

    private val NUM_BLOCKS = arrayOf(
        intArrayOf(0,1,1,1,1,1,2,2,2,2,4,4,4,4,4,6,6,6,6,7,8,8,9,9,10,12,12,12,13,14,15,16,17,18,19,19,20,21,22,24,25),
        intArrayOf(0,1,1,1,2,2,4,4,4,5,5,5,8,9,9,10,10,11,13,14,16,17,17,18,20,21,23,25,26,28,29,31,33,35,37,38,40,43,45,47,49),
        intArrayOf(0,1,1,2,2,4,4,6,6,8,8,8,10,12,16,12,17,16,18,21,20,23,23,25,27,29,34,34,35,38,40,43,45,48,51,53,56,59,62,65,68),
        intArrayOf(0,1,1,2,4,4,4,5,6,8,8,11,11,16,16,18,16,19,21,25,25,25,34,30,32,35,37,40,42,45,48,51,54,57,60,63,66,70,74,77,81)
    )

    /** Minimal MSB-first bit accumulator. */
    private class BitBuffer(capacityBits: Int) {
        private val data = ByteArray((capacityBits + 7) / 8 + 4)
        var length = 0
            private set

        fun append(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                val bit = (value ushr i) and 1
                if (bit != 0) {
                    val idx = length ushr 3
                    if (idx < data.size) {
                        data[idx] = (data[idx].toInt() or (0x80 ushr (length and 7))).toByte()
                    }
                }
                length++
            }
        }

        fun toBytes(): ByteArray = data.copyOf((length + 7) / 8)
    }
}
