package live.hogar.receiver.audio

/**
 * Decodificador ALAC (Apple Lossless) en Kotlin, solo PCM de 16 bits (mono o estereo).
 * Port del decodificador de referencia de Apple (macosforge/alac, Apache 2.0): ALACDecoder.cpp,
 * ag_dec.c (Golomb adaptativo), dp_dec.c (prediccion) y matrix_dec.c (mezcla estereo).
 */
class AlacDecoder(
    private val frameLength: Int = 352,
    private val bitDepth: Int = 16,
    private val pb: Int = 40,
    private val mb: Int = 10,
    private val kb: Int = 14,
    private val channels: Int = 2,
    private val maxRun: Int = 255,
) {
    private var mixU = IntArray(frameLength)
    private var mixV = IntArray(frameLength)
    private var predictor = IntArray(frameLength)

    class BitReader(val buf: ByteArray, val len: Int) {
        var pos = 0 // en bits
        fun byteAt(i: Int): Int = if (i < len) buf[i].toInt() and 0xFF else 0
        fun peek32(bitPos: Int): Int {
            val b = bitPos ushr 3
            val w = (byteAt(b) shl 24) or (byteAt(b + 1) shl 16) or (byteAt(b + 2) shl 8) or byteAt(b + 3)
            return w
        }

        fun read(n: Int): Int {
            if (n == 0) return 0
            var v = 0L
            var left = n
            while (left > 0) {
                val byte = byteAt(pos ushr 3)
                val avail = 8 - (pos and 7)
                val take = minOf(avail, left)
                val bits = (byte ushr (avail - take)) and ((1 shl take) - 1)
                v = (v shl take) or bits.toLong()
                pos += take
                left -= take
            }
            return v.toInt()
        }
    }

    private fun lead(x: Int) = Integer.numberOfLeadingZeros(x)

    private fun getStreamBits(br: BitReader, bitOffset: Int, numBits: Int): Int {
        val save = br.pos
        br.pos = bitOffset
        val r = br.read(numBits)
        br.pos = save
        return r
    }

    private fun dynGet(br: BitReader, bitPos: IntArray, m: Int, k: Int): Int {
        var temp = bitPos[0]
        var stream = br.peek32(temp) shl (temp and 7)
        var pre = lead(stream.inv())
        val result: Int
        if (pre >= 9) {
            pre = 9
            temp += pre
            stream = stream shl pre
            result = stream ushr (32 - 16)
            temp += 16
        } else {
            temp += pre + 1
            stream = stream shl (pre + 1)
            val v = if (k == 0) stream else stream ushr (32 - k)
            temp += k
            var r = pre * m + v - 1
            if (Integer.compareUnsigned(v, 2) < 0) {
                r -= (v - 1)
                temp -= 1
            }
            result = r
        }
        bitPos[0] = temp
        return result
    }

    private fun dynGet32(br: BitReader, bitPos: IntArray, m: Int, k: Int, maxBits: Int): Int {
        var temp = bitPos[0]
        var stream = br.peek32(temp) shl (temp and 7)
        var result = lead(stream.inv())
        if (result >= 9) {
            result = getStreamBits(br, temp + 9, maxBits)
            temp += 9 + maxBits
        } else {
            temp += result + 1
            if (k != 1) {
                stream = stream shl (result + 1)
                val v = stream ushr (32 - k)
                temp += k
                temp -= 1
                result *= m
                if (Integer.compareUnsigned(v, 2) >= 0) {
                    result += v - 1
                    temp += 1
                }
            }
        }
        bitPos[0] = temp
        return result
    }

    private fun lg3a(x: Int): Int = 31 - lead(x + 3)

    private fun dynDecomp(br: BitReader, pbLocal: Int, out: IntArray, numSamples: Int, maxSize: Int) {
        val bitPos = intArrayOf(br.pos)
        val maxPos = br.len * 8
        val wb = (1 shl kb) - 1
        var mbl = mb
        var zmode = 0
        var c = 0
        var o = 0
        while (c < numSamples) {
            require(bitPos[0] < maxPos) { "ALAC: fin de datos" }
            var m = mbl ushr 9
            var k = lg3a(m)
            k = minOf(k, kb)
            m = (1 shl k) - 1
            var n = dynGet32(br, bitPos, m, k, maxSize)
            val ndecode = n + zmode
            val multiplier = (-(ndecode and 1)) or 1
            out[o++] = ((ndecode + 1) ushr 1) * multiplier
            c++
            mbl = pbLocal * (n + zmode) + mbl - ((pbLocal * mbl) ushr 9)
            if (Integer.compareUnsigned(n, 0xffff) > 0) mbl = 0xffff
            zmode = 0
            if (Integer.compareUnsigned(mbl shl 2, 512) < 0 && c < numSamples) {
                zmode = 1
                k = lead(mbl) - 24 + ((mbl + 16) ushr 6)
                val mz = ((1 shl k) - 1) and wb
                n = dynGet(br, bitPos, mz, k)
                require(c + n <= numSamples) { "ALAC: racha de ceros invalida" }
                for (j in 0 until n) { out[o++] = 0; c++ }
                if (n >= 65535) zmode = 0
                mbl = 0
            }
        }
        br.pos = bitPos[0]
        require(br.pos <= br.len * 8) { "ALAC: lectura fuera de rango" }
    }

    private fun signOf(i: Int): Int = ((-i) ushr 31) or (i shr 31)

    private fun unpcBlock(pc1: IntArray, out: IntArray, num: Int, coefs: ShortArray?, numActive: Int, chanBits: Int, denShift: Int) {
        val chanShift = 32 - chanBits
        val denHalf = 1 shl (denShift - 1)
        out[0] = pc1[0]
        if (numActive == 0) {
            if (num > 1 && pc1 !== out) System.arraycopy(pc1, 1, out, 1, num - 1)
            return
        }
        if (numActive == 31) {
            var prev = out[0]
            for (j in 1 until num) {
                val del = pc1[j] + prev
                prev = (del shl chanShift) shr chanShift
                out[j] = prev
            }
            return
        }
        val cf = coefs!!
        for (j in 1..numActive) {
            val del = pc1[j] + out[j - 1]
            out[j] = (del shl chanShift) shr chanShift
        }
        val lim = numActive + 1
        for (j in lim until num) {
            var sum1 = 0
            val p = j - 1
            val top = out[j - lim]
            for (k in 0 until numActive) sum1 += cf[k] * (out[p - k] - top)
            var del = pc1[j]
            var del0 = del
            val sg = signOf(del)
            del += top + ((sum1 + denHalf) shr denShift)
            out[j] = (del shl chanShift) shr chanShift
            if (sg > 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[p - k]
                    val sgn = signOf(dd)
                    cf[k] = (cf[k] - sgn).toShort()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sg < 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[p - k]
                    val sgn = signOf(dd)
                    cf[k] = (cf[k] + sgn).toShort()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    /** Decodifica un frame ALAC. Devuelve PCM 16 bit intercalado (little endian en ShortArray). */
    fun decode(data: ByteArray, length: Int = data.size): ShortArray {
        require(bitDepth == 16) { "solo 16 bits" }
        val br = BitReader(data, length)
        var numSamples = frameLength
        val out = ShortArray(frameLength * channels)
        var channelIndex = 0
        var outSamples = numSamples
        while (channelIndex < channels) {
            require(br.pos < length * 8) { "ALAC: fin de datos" }
            val tag = br.read(3)
            when (tag) {
                0, 3 -> { // SCE, LFE
                    br.read(4)
                    require(br.read(12) == 0) { "ALAC: cabecera" }
                    val headerByte = br.read(4)
                    val partial = headerByte ushr 3
                    var bytesShifted = (headerByte ushr 1) and 3
                    require(bytesShifted != 3)
                    val escape = headerByte and 1
                    var chanBits = bitDepth - bytesShifted * 8
                    if (partial != 0) numSamples = (br.read(16) shl 16) or br.read(16)
                    ensure(numSamples)
                    if (escape == 0) {
                        br.read(8); br.read(8) // mixBits, mixRes (sin uso en canal unico)
                        var hb = br.read(8)
                        val modeU = hb ushr 4
                        val denShiftU = hb and 0xf
                        hb = br.read(8)
                        val pbFactorU = hb ushr 5
                        val numU = hb and 0x1f
                        val coefsU = ShortArray(32) { 0 }
                        for (i in 0 until numU) coefsU[i] = br.read(16).toShort()
                        require(bytesShifted == 0) { "ALAC: bytes desplazados no soportado" }
                        dynDecomp(br, (pb * pbFactorU) / 4, predictor, numSamples, chanBits)
                        if (modeU == 0) {
                            unpcBlock(predictor, mixU, numSamples, coefsU, numU, chanBits, denShiftU)
                        } else {
                            unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0)
                            unpcBlock(predictor, mixU, numSamples, coefsU, numU, chanBits, denShiftU)
                        }
                    } else {
                        val shift = 32 - chanBits
                        for (i in 0 until numSamples) mixU[i] = (br.read(chanBits) shl shift) shr shift
                        bytesShifted = 0
                    }
                    var j = channelIndex
                    for (i in 0 until numSamples) { out[j] = mixU[i].toShort(); j += channels }
                    channelIndex += 1
                    outSamples = numSamples
                }
                1 -> { // CPE
                    if (channelIndex + 2 > channels) { channelIndex = channels; continue }
                    br.read(4)
                    require(br.read(12) == 0) { "ALAC: cabecera" }
                    val headerByte = br.read(4)
                    val partial = headerByte ushr 3
                    val bytesShifted = (headerByte ushr 1) and 3
                    require(bytesShifted != 3)
                    val escape = headerByte and 1
                    var chanBits = bitDepth - bytesShifted * 8 + 1
                    if (partial != 0) numSamples = (br.read(16) shl 16) or br.read(16)
                    ensure(numSamples)
                    var mixBits = 0
                    var mixRes = 0
                    if (escape == 0) {
                        mixBits = br.read(8)
                        mixRes = br.read(8).toByte().toInt()
                        var hb = br.read(8)
                        val modeU = hb ushr 4
                        val denShiftU = hb and 0xf
                        hb = br.read(8)
                        val pbFactorU = hb ushr 5
                        val numU = hb and 0x1f
                        val coefsU = ShortArray(32)
                        for (i in 0 until numU) coefsU[i] = br.read(16).toShort()
                        hb = br.read(8)
                        val modeV = hb ushr 4
                        val denShiftV = hb and 0xf
                        hb = br.read(8)
                        val pbFactorV = hb ushr 5
                        val numV = hb and 0x1f
                        val coefsV = ShortArray(32)
                        for (i in 0 until numV) coefsV[i] = br.read(16).toShort()
                        require(bytesShifted == 0) { "ALAC: bytes desplazados no soportado" }
                        dynDecomp(br, (pb * pbFactorU) / 4, predictor, numSamples, chanBits)
                        if (modeU == 0) {
                            unpcBlock(predictor, mixU, numSamples, coefsU, numU, chanBits, denShiftU)
                        } else {
                            unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0)
                            unpcBlock(predictor, mixU, numSamples, coefsU, numU, chanBits, denShiftU)
                        }
                        dynDecomp(br, (pb * pbFactorV) / 4, predictor, numSamples, chanBits)
                        if (modeV == 0) {
                            unpcBlock(predictor, mixV, numSamples, coefsV, numV, chanBits, denShiftV)
                        } else {
                            unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0)
                            unpcBlock(predictor, mixV, numSamples, coefsV, numV, chanBits, denShiftV)
                        }
                    } else {
                        chanBits = bitDepth
                        val shift = 32 - chanBits
                        for (i in 0 until numSamples) {
                            mixU[i] = (br.read(chanBits) shl shift) shr shift
                            mixV[i] = (br.read(chanBits) shl shift) shr shift
                        }
                    }
                    var j = channelIndex
                    for (i in 0 until numSamples) {
                        val u = mixU[i]
                        val v = mixV[i]
                        if (mixRes != 0) {
                            val l = u + v - ((mixRes * v) shr mixBits)
                            val r = l - v
                            out[j] = l.toShort(); out[j + 1] = r.toShort()
                        } else {
                            out[j] = u.toShort(); out[j + 1] = v.toShort()
                        }
                        j += channels
                    }
                    channelIndex += 2
                    outSamples = numSamples
                }
                4 -> { // DSE
                    br.read(4)
                    val align = br.read(1)
                    var count = br.read(8)
                    if (count == 255) count += br.read(8)
                    if (align != 0) br.pos = (br.pos + 7) and 7.inv()
                    br.pos += count * 8
                }
                6 -> { // FIL
                    var count = br.read(4)
                    if (count == 15) count += br.read(8) - 1
                    br.pos += count * 8
                }
                7 -> break // END
                else -> throw IllegalArgumentException("ALAC: elemento no soportado $tag")
            }
        }
        return if (outSamples == frameLength) out else out.copyOf(outSamples * channels)
    }

    private fun ensure(n: Int) {
        require(n in 1..(1 shl 20))
        if (mixU.size < n) { mixU = IntArray(n); mixV = IntArray(n); predictor = IntArray(n) }
    }
}
