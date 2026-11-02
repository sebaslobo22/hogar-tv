package live.hogar.receiver.fairplay

/**
 * FairPlay SAP v2.5 (solo la parte que descifra la clave AES de la sesion).
 *
 * Port a Kotlin de playfair (omg_hax.c, sap_hash.c, modified_md5.c, playfair.c) de UxPlay / RPiPlay,
 * cuyo origen es "playfair" de Jean-Francois Pelletier / hax. GPL-3.0. Ver NOTICE.
 * Las tablas vienen de [FairPlayTables]; la parte "garble" esta transpilada en Garble.kt.
 */
object PlayFair {
    private val zKey = FairPlayTables.bytes("z_key")
    private val xKey = FairPlayTables.bytes("x_key")
    private val tKey = FairPlayTables.bytes("t_key")
    private val s1 = FairPlayTables.bytes("table_s1")
    private val s2 = FairPlayTables.bytes("table_s2")
    private val s3 = FairPlayTables.bytes("table_s3")
    private val s4 = FairPlayTables.bytes("table_s4")
    private val s5 = FairPlayTables.ints("table_s5")
    private val s6 = FairPlayTables.ints("table_s6")
    private val s7 = FairPlayTables.ints("table_s7")
    private val s8 = FairPlayTables.ints("table_s8")
    private val s9 = FairPlayTables.ints("table_s9")
    private val s10 = FairPlayTables.bytes("table_s10")
    private val indexMangle = FairPlayTables.bytes("index_mangle")
    private val initialSessionKey = FairPlayTables.bytes("initial_session_key")
    private val messageKey = FairPlayTables.bytes("message_key") // [4][144]
    private val messageIv = FairPlayTables.bytes("message_iv") // [4][16]
    private val staticSource1 = FairPlayTables.bytes("static_source_1")
    private val staticSource2 = FairPlayTables.bytes("static_source_2")
    private val defaultSap = FairPlayTables.bytes("default_sap")
    private val i0Index = intArrayOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30)
    private val shift = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val md5K = IntArray(64) { (4294967296.0 * Math.abs(StrictMath.sin(it + 1.0))).toLong().toInt() }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun getW(b: ByteArray, off: Int): Int =
        u(b[off]) or (u(b[off + 1]) shl 8) or (u(b[off + 2]) shl 16) or (u(b[off + 3]) shl 24)

    private fun putW(b: ByteArray, off: Int, w: Int) {
        b[off] = w.toByte(); b[off + 1] = (w ushr 8).toByte(); b[off + 2] = (w ushr 16).toByte(); b[off + 3] = (w ushr 24).toByte()
    }

    private fun byteOf(w: Int, i: Int) = (w ushr (8 * i)) and 0xFF

    private fun xorKey(inp: ByteArray, key: ByteArray): ByteArray = ByteArray(16) { (inp[it].toInt() xor key[it].toInt()).toByte() }

    // ---- key schedule (parecido a Rijndael con otras S-boxes) ----
    private fun generateKeySchedule(keyMaterial: ByteArray): Array<IntArray> {
        val ks = Array(11) { IntArray(4) }
        val buf = xorKey(keyMaterial, tKey)
        var ti = 0
        for (round in 0 until 11) {
            ks[round][0] = getW(buf, 0)
            val t1 = ((31 * ti) % 0x28) shl 8
            val t2 = ((31 * (ti + 1)) % 0x28) shl 8
            val t3 = ((31 * (ti + 2)) % 0x28) shl 8
            val t4 = ((31 * (ti + 3)) % 0x28) shl 8
            ti += 4
            buf[0] = (u(buf[0]) xor u(s1[t1 + u(buf[0x0d])]) xor u(indexMangle[round])).toByte()
            buf[1] = (u(buf[1]) xor u(s1[t2 + u(buf[0x0e])])).toByte()
            buf[2] = (u(buf[2]) xor u(s1[t3 + u(buf[0x0f])])).toByte()
            buf[3] = (u(buf[3]) xor u(s1[t4 + u(buf[0x0c])])).toByte()
            ks[round][1] = getW(buf, 4)
            putW(buf, 4, getW(buf, 4) xor getW(buf, 0))
            ks[round][2] = getW(buf, 8)
            putW(buf, 8, getW(buf, 8) xor getW(buf, 4))
            ks[round][3] = getW(buf, 12)
            putW(buf, 12, getW(buf, 12) xor getW(buf, 8))
        }
        return ks
    }

    private fun permuteBlock1(b: ByteArray) {
        b[0] = s3[u(b[0])]
        b[4] = s3[0x400 + u(b[4])]
        b[8] = s3[0x800 + u(b[8])]
        b[12] = s3[0xc00 + u(b[12])]
        var tmp = b[13]
        b[13] = s3[0x100 + u(b[9])]
        b[9] = s3[0xd00 + u(b[5])]
        b[5] = s3[0x900 + u(b[1])]
        b[1] = s3[0x500 + u(tmp)]
        tmp = b[2]
        b[2] = s3[0xa00 + u(b[10])]
        b[10] = s3[0x200 + u(tmp)]
        tmp = b[6]
        b[6] = s3[0xe00 + u(b[14])]
        b[14] = s3[0x600 + u(tmp)]
        tmp = b[3]
        b[3] = s3[0xf00 + u(b[7])]
        b[7] = s3[0x300 + u(b[11])]
        b[11] = s3[0x700 + u(b[15])]
        b[15] = s3[0xb00 + u(tmp)]
    }

    private fun t2(i: Int, v: Byte): Byte = s4[(((71 * i) % 144) shl 8) + u(v)]

    private fun permuteBlock2(b: ByteArray, round: Int) {
        val r = round * 16
        b[0] = t2(r + 0, b[0])
        b[4] = t2(r + 4, b[4])
        b[8] = t2(r + 8, b[8])
        b[12] = t2(r + 12, b[12])
        var tmp = b[13]
        b[13] = t2(r + 13, b[9])
        b[9] = t2(r + 9, b[5])
        b[5] = t2(r + 5, b[1])
        b[1] = t2(r + 1, tmp)
        tmp = b[2]
        b[2] = t2(r + 2, b[10])
        b[10] = t2(r + 10, tmp)
        tmp = b[6]
        b[6] = t2(r + 6, b[14])
        b[14] = t2(r + 14, tmp)
        tmp = b[3]
        b[3] = t2(r + 3, b[7])
        b[7] = t2(r + 7, b[11])
        b[11] = t2(r + 11, b[15])
        b[15] = t2(r + 15, tmp)
    }

    private fun cycle(block: ByteArray, ks: Array<IntArray>) {
        for (i in 0 until 4) putW(block, i * 4, getW(block, i * 4) xor ks[10][i])
        permuteBlock1(block)
        for (round in 0 until 9) {
            val k = ks[9 - round]
            val w0 = s5[u(block[3]) xor byteOf(k[0], 3)] xor s6[u(block[2]) xor byteOf(k[0], 2)] xor
                s8[u(block[0]) xor byteOf(k[0], 0)] xor s7[u(block[1]) xor byteOf(k[0], 1)]
            putW(block, 0, w0)
            val w1 = s5[u(block[7]) xor byteOf(k[1], 3)] xor s6[u(block[6]) xor byteOf(k[1], 2)] xor
                s7[u(block[5]) xor byteOf(k[1], 1)] xor s8[u(block[4]) xor byteOf(k[1], 0)]
            putW(block, 4, w1)
            val w2 = s5[u(block[11]) xor byteOf(k[2], 3)] xor s6[u(block[10]) xor byteOf(k[2], 2)] xor
                s7[u(block[9]) xor byteOf(k[2], 1)] xor s8[u(block[8]) xor byteOf(k[2], 0)]
            val w3 = s5[u(block[15]) xor byteOf(k[3], 3)] xor s6[u(block[14]) xor byteOf(k[3], 2)] xor
                s7[u(block[13]) xor byteOf(k[3], 1)] xor s8[u(block[12]) xor byteOf(k[3], 0)]
            putW(block, 8, w2)
            putW(block, 12, w3)
            permuteBlock2(block, 8 - round)
        }
        for (i in 0 until 4) putW(block, i * 4, getW(block, i * 4) xor ks[0][i])
    }

    // ---- desencriptado del mensaje de 128 bytes (message3) ----
    private fun mti(i: Int) = ((97 * i) % 144) shl 8

    private fun decryptMessage(msg: ByteArray): ByteArray {
        val out = ByteArray(128)
        val mode = u(msg[12])
        require(mode in 0..3) { "modo FairPlay invalido: $mode" }
        val mk = mode * 144
        val buf = ByteArray(16)
        for (i in 0 until 8) {
            for (j in 0 until 16) {
                buf[j] = if (mode == 3) msg[(0x80 - 0x10 * i) + j] else msg[(0x10 * (i + 1)) + j]
            }
            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j
                fun f(n: Int, v: Byte): Byte = (u(s2[mti(base + n) + u(v)]) xor u(messageKey[mk + base + n])).toByte()
                buf[0x0] = f(0x0, buf[0x0])
                buf[0x4] = f(0x4, buf[0x4])
                buf[0x8] = f(0x8, buf[0x8])
                buf[0xc] = f(0xc, buf[0xc])
                var tmp = buf[0x0d]
                buf[0xd] = f(0xd, buf[0x9])
                buf[0x9] = f(0x9, buf[0x5])
                buf[0x5] = f(0x5, buf[0x1])
                buf[0x1] = f(0x1, tmp)
                tmp = buf[0x02]
                buf[0x2] = f(0x2, buf[0xa])
                buf[0xa] = f(0xa, tmp)
                tmp = buf[0x06]
                buf[0x6] = f(0x6, buf[0xe])
                buf[0xe] = f(0xe, tmp)
                tmp = buf[0x3]
                buf[0x3] = f(0x3, buf[0x7])
                buf[0x7] = f(0x7, buf[0xb])
                buf[0xb] = f(0xb, buf[0xf])
                buf[0xf] = f(0xf, tmp)
                val w0 = s9[u(buf[0])] xor s9[0x100 + u(buf[1])] xor s9[0x200 + u(buf[2])] xor s9[0x300 + u(buf[3])]
                val w1 = s9[u(buf[4])] xor s9[0x100 + u(buf[5])] xor s9[0x200 + u(buf[6])] xor s9[0x300 + u(buf[7])]
                val w2 = s9[u(buf[8])] xor s9[0x100 + u(buf[9])] xor s9[0x200 + u(buf[10])] xor s9[0x300 + u(buf[11])]
                val w3 = s9[u(buf[12])] xor s9[0x100 + u(buf[13])] xor s9[0x200 + u(buf[14])] xor s9[0x300 + u(buf[15])]
                putW(buf, 0, w0); putW(buf, 4, w1); putW(buf, 8, w2); putW(buf, 12, w3)
            }
            fun g(n: Int, v: Byte): Byte = s10[(n shl 8) + u(v)]
            buf[0x0] = g(0x0, buf[0x0])
            buf[0x4] = g(0x4, buf[0x4])
            buf[0x8] = g(0x8, buf[0x8])
            buf[0xc] = g(0xc, buf[0xc])
            var tmp = buf[0x0d]
            buf[0xd] = g(0xd, buf[0x9])
            buf[0x9] = g(0x9, buf[0x5])
            buf[0x5] = g(0x5, buf[0x1])
            buf[0x1] = g(0x1, tmp)
            tmp = buf[0x02]
            buf[0x2] = g(0x2, buf[0xa])
            buf[0xa] = g(0xa, tmp)
            tmp = buf[0x06]
            buf[0x6] = g(0x6, buf[0xe])
            buf[0xe] = g(0xe, tmp)
            tmp = buf[0x3]
            buf[0x3] = g(0x3, buf[0x7])
            buf[0x7] = g(0x7, buf[0xb])
            buf[0xb] = g(0xb, buf[0xf])
            buf[0xf] = g(0xf, tmp)

            if (mode <= 2) {
                for (k in 0 until 16) {
                    val prev = if (i > 0) msg[0x10 * i + k] else messageIv[mode * 16 + k]
                    out[0x10 * i + k] = (buf[k].toInt() xor prev.toInt()).toByte()
                }
            } else {
                for (k in 0 until 16) {
                    val prev = if (i < 7) msg[0x70 - 0x10 * i + k] else messageIv[mode * 16 + k]
                    out[0x70 - 0x10 * i + k] = (buf[k].toInt() xor prev.toInt()).toByte()
                }
            }
        }
        return out
    }

    // ---- MD5 modificado ----
    private fun rol(x: Int, c: Int) = Integer.rotateLeft(x, c)

    private fun modifiedMd5(origBlock: ByteArray, blockOff: Int, key: ByteArray): ByteArray {
        val block = origBlock.copyOfRange(blockOff, blockOff + 64)
        val kw = IntArray(4) { getW(key, it * 4) }
        var a = kw[0]; var b = kw[1]; var c = kw[2]; var d = kw[3]
        fun swapW(i: Int, j: Int) {
            val wi = getW(block, i * 4); val wj = getW(block, j * 4)
            putW(block, i * 4, wj); putW(block, j * 4, wi)
        }
        for (i in 0 until 64) {
            val j = when {
                i < 16 -> i
                i < 32 -> (5 * i + 1) % 16
                i < 48 -> (3 * i + 5) % 16
                else -> 7 * i % 16
            }
            val input = (u(block[4 * j]) shl 24) or (u(block[4 * j + 1]) shl 16) or (u(block[4 * j + 2]) shl 8) or u(block[4 * j + 3])
            var z = a + input + md5K[i]
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (b and d) or (c and d.inv())
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            z = rol(z + f, shift[i])
            z += b
            val tmp = d
            d = c; c = b; b = z; a = tmp
            if (i == 31) {
                swapW(a and 15, b and 15)
                swapW(c and 15, d and 15)
                swapW((a and (15 shl 4)) ushr 4, (b and (15 shl 4)) ushr 4)
                swapW((a and (15 shl 8)) ushr 8, (b and (15 shl 8)) ushr 8)
                swapW((a and (15 shl 12)) ushr 12, (b and (15 shl 12)) ushr 12)
            }
        }
        val out = ByteArray(16)
        putW(out, 0, kw[0] + a); putW(out, 4, kw[1] + b); putW(out, 8, kw[2] + c); putW(out, 12, kw[3] + d)
        return out
    }

    // ---- hash SAP ----
    private fun rol8b(x: Int, c: Int) = rol8(x, c)

    private fun sapHash(blockIn: ByteArray, blockOff: Int, keyOut: ByteArray) {
        val buffer0 = FairPlayTables.bytes("buffer0")
        val buffer1 = ByteArray(210)
        val buffer2 = FairPlayTables.bytes("buffer2")
        val buffer3 = ByteArray(132)
        val buffer4 = FairPlayTables.bytes("buffer4")
        for (i in 0 until 210) {
            val m = i % 64
            buffer1[i] = blockIn[blockOff + (m and 3.inv()) + (3 - (m and 3))]
        }
        for (i in 0 until 840) {
            val x = u(buffer1[Integer.remainderUnsigned(i - 155, 210)])
            val y = u(buffer1[Integer.remainderUnsigned(i - 57, 210)])
            val z = u(buffer1[Integer.remainderUnsigned(i - 13, 210)])
            val w = u(buffer1[i % 210])
            buffer1[i % 210] = ((rol8b(y, 5) + (rol8b(z, 3) xor w) - rol8b(x, 7)) and 0xFF).toByte()
        }
        garble(buffer0, buffer1, buffer2, buffer3, buffer4)
        for (i in 0 until 16) keyOut[i] = 0xE1.toByte()
        for (i in 0 until 11) {
            keyOut[i] = if (i == 3) 0x3d else ((u(keyOut[i]) + u(buffer3[i0Index[i] * 4])) and 0xFF).toByte()
        }
        for (i in 0 until 20) keyOut[i % 16] = (u(keyOut[i % 16]) xor u(buffer0[i])).toByte()
        for (i in 0 until 35) keyOut[i % 16] = (u(keyOut[i % 16]) xor u(buffer2[i])).toByte()
        for (i in 0 until 210) keyOut[i % 16] = (u(keyOut[i % 16]) xor u(buffer1[i])).toByte()
        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val x = u(keyOut[(i - 7) and 15])
                val y = u(keyOut[i % 16])
                val z = u(keyOut[(i - 37) and 15])
                val w = u(keyOut[(i - 177) and 15])
                keyOut[i] = ((rol8b(x, 1) xor y xor rol8b(z, 6) xor rol8b(w, 5)) and 0xFF).toByte()
            }
        }
    }

    private fun generateSessionKey(message: ByteArray): ByteArray {
        val dec = decryptMessage(message)
        val newSap = ByteArray(320)
        System.arraycopy(staticSource1, 0, newSap, 0x000, 0x11)
        System.arraycopy(dec, 0, newSap, 0x011, 0x80)
        System.arraycopy(defaultSap, 0x80, newSap, 0x091, 0x80)
        System.arraycopy(staticSource2, 0, newSap, 0x111, 0x2f)
        val sessionKey = initialSessionKey.copyOf()
        for (round in 0 until 5) {
            val base = round * 64
            val md5 = modifiedMd5(newSap, base, sessionKey)
            sapHash(newSap, base, sessionKey)
            for (i in 0 until 4) putW(sessionKey, i * 4, getW(sessionKey, i * 4) + getW(md5, i * 4))
        }
        var i = 0
        while (i < 16) {
            var t = sessionKey[i]; sessionKey[i] = sessionKey[i + 3]; sessionKey[i + 3] = t
            t = sessionKey[i + 1]; sessionKey[i + 1] = sessionKey[i + 2]; sessionKey[i + 2] = t
            i += 4
        }
        for (k in 0 until 16) sessionKey[k] = (sessionKey[k].toInt() xor 121).toByte()
        return sessionKey
    }

    /**
     * Descifra la clave AES de la sesion (ekey de 72 bytes en SETUP).
     * [message3]: los 164 bytes que el iPhone manda en la 2a fase de /fp-setup.
     */
    fun decrypt(message3: ByteArray, cipherText: ByteArray): ByteArray {
        require(message3.size == 164 && cipherText.size >= 72)
        val sapKey = generateSessionKey(message3)
        val ks = generateKeySchedule(sapKey)
        val blockIn = xorKey(cipherText.copyOfRange(56, 72), zKey)
        cycle(blockIn, ks)
        val keyOut = ByteArray(16) { (blockIn[it].toInt() xor cipherText[16 + it].toInt()).toByte() }
        val x = xorKey(keyOut, xKey)
        return xorKey(x, zKey)
    }
}
