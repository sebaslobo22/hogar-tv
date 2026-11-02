// GENERADO por transpilación de UxPlay playfair/hand_garble.c (GPL-3.0). Ver NOTICE. No editar a mano.
package live.hogar.receiver.fairplay

internal class U8(val a: ByteArray) {
    operator fun get(i: Int): Int = a[i].toInt() and 0xFF
    operator fun set(i: Int, v: Int) { a[i] = v.toByte() }
}

private fun udiv(a: Int, b: Int): Int = Integer.divideUnsigned(a, b)
private fun umod(a: Int, b: Int): Int = Integer.remainderUnsigned(a, b)

internal fun rol8(input: Int, count: Int): Int {
    val x = input and 0xFF
    return ((x shl count) and 0xFF) or (x ushr (8 - count))
}

internal fun rol8x(input: Int, count: Int): Int {
    val x = input and 0xFF
    return (x shl count) or (x ushr (8 - count))
}

private fun weirdRor8(input: Int, count: Int): Int {
    if (count == 0) return 0
    val x = input and 0xFF
    return (x ushr count) or (x shl (8 - count))
}

private fun weirdRol8(input: Int, count: Int): Int {
    if (count == 0) return 0
    val x = input and 0xFF
    return ((x shl count) and 0xFF) or (x ushr (8 - count))
}

private fun weirdRol32(input: Int, count: Int): Int {
    if (count == 0) return 0
    val x = input and 0xFF
    return (x shl count) xor (x ushr (8 - count))
}

internal fun garble(buffer0: ByteArray, buffer1: ByteArray, buffer2: ByteArray, buffer3: ByteArray, buffer4: ByteArray) {
    val b0 = U8(buffer0); val b1 = U8(buffer1); val b2 = U8(buffer2); val b3 = U8(buffer3); val b4 = U8(buffer4)
    var tmp = 0; var tmp2 = 0; var tmp3 = 0
    var A = 0; var B = 0; var C = 0; var D = 0; var E = 0; var M = 0; var J = 0; var G = 0; var F = 0; var H = 0
    var K = 0; var R = 0; var S = 0; var T = 0; var U = 0; var V = 0; var W = 0; var X = 0; var Y = 0; var Z = 0
    b2[12] = (20 + ((((((b1[64] and 92)) or (((udiv(b1[99], 3)) and 35)))) and b4[umod(rol8x(b4[(umod(b1[206], 21))], 4), 21)])))
    b1[4] = (((udiv(b1[99], 5)) * (udiv(b1[99], 5))) * 2)
    b2[34] = 184
    b1[153] = (b1[153] xor (((b2[umod(b1[203], 35)] * b2[umod(b1[203], 35)]) * b1[190])))
    b0[3] = (b0[3] - ((((((b4[umod(b1[205], 21)] ushr 1)) and 80)) or 943168)))
    b0[16] = 147
    b0[13] = 98
    b1[33] = (b1[33] - ((b4[umod(b1[36], 21)] and 246)))
    tmp2 = b2[umod(b1[67], 35)]
    b2[12] = 7
    tmp = b0[umod(b1[181], 20)]
    b1[2] = (b1[2] - 3136)
    b0[19] = b4[umod(b1[58], 21)]
    b3[0] = (92 - b2[umod(b1[32], 35)])
    b3[4] = (b2[umod(b1[15], 35)] + 158)
    b1[34] = (b1[34] + (udiv(b4[umod(((((b2[umod(b1[15], 35)] + 158)) and 255)), 21)], 5)))
    b0[19] = (b0[19] + (0xfffffee6.toInt() - ((((b0[umod(b3[4], 20)] ushr 1)) and 102))))
    b1[15] = (((3 * ((((((b1[72] ushr ((b4[umod(b1[190], 21)] and 7)))) xor ((b1[72] shl ((((7 - ((b4[umod(b1[190], 21)] - 1))) and 7))))))) - ((3 * b4[umod(b1[126], 21)])))))) xor b1[15])
    b0[15] = (b0[15] xor ((b2[umod(b1[181], 35)] * b2[umod(b1[181], 35)]) * b2[umod(b1[181], 35)]))
    b2[4] = (b2[4] xor udiv(b1[202], 3))
    A = (92 - b0[umod(b3[0], 20)])
    E = ((((A and 198)) or (((b1[105]).inv() and 198))) or ((A and ((b1[105]).inv()))))
    b2[1] = (b2[1] + (((E * E) * E)))
    b0[19] = (b0[19] xor udiv(((((224 or ((b4[umod(b1[92], 21)] and 27)))) * b2[umod(b1[41], 35)])), 3))
    b1[140] = (b1[140] + weirdRor8(92, (b1[5] and 7)))
    b2[12] = (b2[12] + (((((((((b1[4]).inv()) xor b2[umod(b1[12], 35)])) or b1[182])) and 192)) or ((((((b1[4]).inv()) xor b2[umod(b1[12], 35)])) and b1[182]))))
    b1[36] = (b1[36] + 125)
    b1[124] = rol8x((((((((74 and b1[138])) or ((((74 or b1[138])) and b0[15])))) and b0[umod(b1[43], 20)])) or (((((((74 and b1[138])) or ((((74 or b1[138])) and b0[15]))) or b0[umod(b1[43], 20)])) and 95))), 4)
    b3[8] = ((((((((b0[umod(b3[4], 20)] and 95))) and ((((b4[umod(b1[68], 21)] and 46)) shl 1)))) or 16)) xor 92)
    A = (b1[177] + b4[umod(b1[79], 21)])
    D = (((((((A ushr 1)) or (udiv(((3 * b1[148])), 5)))) and b2[1])) or ((((A ushr 1)) and (udiv(((3 * b1[148])), 5)))))
    b3[12] = ((((-34) - D)))
    A = (8 - (((b2[22] and 7))))
    B = ((b1[33] ushr ((A and 7))))
    C = (b1[33] shl ((b2[22] and 7)))
    b2[16] = (b2[16] + ((((((b2[umod(b3[0], 35)] and 159)) or b0[umod(b3[4], 20)]) or 8)) - ((((B xor C)) or 128))))
    b0[14] = (b0[14] xor b2[umod(b3[12], 35)])
    A = weirdRol8(b4[umod(b0[umod(b1[201], 20)], 21)], ((((b2[umod(b1[112], 35)] shl 1)) and 7)))
    D = (((b0[umod(b1[208], 20)] and 131)) or ((b0[umod(b1[164], 20)] and 124)))
    b1[19] = (b1[19] + (((A and (udiv(D, 5)))) or ((((A or (udiv(D, 5)))) and 37))))
    b2[8] = weirdRor8(140, (((((b4[umod(b1[45], 21)] + 92)) * ((b4[umod(b1[45], 21)] + 92)))) and 7))
    b1[190] = 56
    b2[8] = (b2[8] xor b3[0])
    b1[53] = ((udiv(((b0[umod(b1[83], 20)] or 204)), 5))).inv()
    b0[13] = (b0[13] + b0[umod(b1[41], 20)])
    b0[10] = udiv(((((b2[umod(b3[0], 35)] and b1[2])) or ((((b2[umod(b3[0], 35)] or b1[2])) and b3[12])))), 15)
    A = (((((((56 or ((b4[umod(b1[2], 21)] and 68)))) or b2[umod(b3[8], 35)])) and 42)) or ((((((b4[umod(b1[2], 21)] and 68)) or 56)) and b2[umod(b3[8], 35)])))
    b3[16] = (((A * A)) + 110)
    b3[20] = (202 - b3[16])
    b3[24] = b1[151]
    b2[13] = (b2[13] xor b4[umod(b3[0], 21)])
    B = (((((b2[umod(b1[179], 35)] - 38)) and 177)) or ((b3[12] and 177)))
    C = ((((b2[umod(b1[179], 35)] - 38))) and b3[12])
    b3[28] = (30 + ((((B or C)) * ((B or C)))))
    b3[32] = (b3[28] + 62)
    A = (((((b3[20] + ((b3[0] and 74)))) or (b4[umod(b3[0], 21)]).inv())) and 121)
    B = ((((b3[20] + ((b3[0] and 74)))) and (b4[umod(b3[0], 21)]).inv()))
    tmp3 = ((A or B))
    C = (((((((((A or B)) xor 0xffffffa6.toInt())) or b3[0])) and 4)) or ((((((A or B)) xor 0xffffffa6.toInt())) and b3[0])))
    b1[47] = (((b2[umod(b1[89], 35)] + C)) xor b1[47])
    b3[36] = (((((rol8((((tmp and 179)) + 68), 2) and b0[3])) or ((tmp2 and (b0[3]).inv())))) - 15)
    b1[123] = (b1[123] xor 221)
    A = ((udiv((b4[umod(b3[0], 21)]), 3)) - b2[umod(b3[4], 35)])
    C = (((((((b3[0] and 163)) + 92)) and 246)) or ((b3[0] and 92)))
    E = (((((C or b3[24])) and 54)) or ((C and b3[24])))
    b3[40] = (A - E)
    b3[44] = ((tmp3 xor 81) xor ((((((b3[0] ushr 1)) and 101)) + 26)))
    b3[48] = (b2[umod(b3[4], 35)] and 27)
    b3[52] = 27
    b3[56] = 199
    b3[64] = (b3[4] + ((((((((((((((b3[40] or b3[24])) and 177)) or ((b3[40] and b3[24])))) and (((((((b4[umod(b3[0], 20)] and 177)) or 176))) or (((b4[umod(b3[0], 21)]) and (3).inv())))))) or ((((((((b3[40] and b3[24])) or ((((b3[40] or b3[24])) and 177)))) and 199)) or ((((((((b4[umod(b3[0], 21)] and 1)) + 176)) or ((b4[umod(b3[0], 21)] and (3).inv())))) and b3[56])))))) and ((b3[52]).inv()))) or b3[48])))
    b2[33] = (b2[33] xor b1[26])
    b1[106] = (b1[106] xor (b3[20] xor 133))
    b2[30] = ((((udiv(b3[64], 3)) - ((275 or ((b3[0] and 247)))))) xor b0[umod(b1[122], 20)])
    b1[22] = (((b2[umod(b1[90], 35)] and 95)) or 68)
    A = (((b4[umod(b3[36], 21)] and 184)) or ((b2[umod(b3[44], 35)] and (184).inv())))
    b2[18] = (b2[18] + (((((A * A) * A)) ushr 1)))
    b2[5] = (b2[5] - b4[umod(b1[92], 21)])
    A = (((((((b1[41] and (24).inv())) or ((b2[umod(b1[183], 35)] and 24)))) and ((b3[16] + 53)))) or ((b3[20] and b2[umod(b3[20], 35)])))
    B = (((b1[17] and ((b3[44]).inv()))) or ((b0[umod(b1[59], 20)] and b3[44])))
    b2[18] = (b2[18] xor ((A * B)))
    A = (weirdRor8(b1[11], (b2[umod(b1[28], 35)] and 7)) and 7)
    B = (((((((b0[umod(b1[93], 20)] and (b0[14]).inv())) or ((b0[14] and 150)))) and (28).inv())) or ((b1[7] and 28)))
    b2[22] = ((((((((((B or weirdRol8(b2[umod(b3[0], 35)], A))) and b2[33])) or ((B and weirdRol8(b2[umod(b3[0], 35)], A))))) + 74)) and 255))
    A = b4[umod(((b0[umod(b1[39], 20)] xor 217)), 21)]
    b0[15] = (b0[15] - (((((((((b3[20] or b3[0])) and 214)) or ((b3[20] and b3[0])))) and A)) or (((((((((b3[20] or b3[0])) and 214)) or ((b3[20] and b3[0]))) or A)) and b3[32]))))
    B = ((((((((b2[umod(b1[57], 35)] and b0[umod(b3[64], 20)])) or ((((b0[umod(b3[64], 20)] or b2[umod(b1[57], 35)])) and 95))) or ((b3[64] and 45))) or 82)) and 32))
    C = (((((b2[umod(b1[57], 35)] and b0[umod(b3[64], 20)])) or ((((b2[umod(b1[57], 35)] or b0[umod(b3[64], 20)])) and 95)))) and ((((b3[64] and 45)) or 82)))
    D = (((((((udiv(b3[0], 3)) - ((b3[64] or b1[22]))))) xor ((b3[28] + 62))) xor (((B or C)))))
    T = b0[umod(((D and 255)), 20)]
    b3[68] = (((((b0[umod(b1[99], 20)] * b0[umod(b1[99], 20)]) * b0[umod(b1[99], 20)]) * b0[umod(b1[99], 20)])) or b2[umod(b3[64], 35)])
    U = b0[umod(b1[50], 20)]
    W = b2[umod(b1[138], 35)]
    X = b4[umod(b1[39], 21)]
    Y = b0[umod(b1[4], 20)]
    Z = b4[umod(b1[202], 21)]
    V = b0[umod(b1[151], 20)]
    S = b2[umod(b1[14], 35)]
    R = b0[umod(b1[145], 20)]
    A = (((b2[umod(b3[68], 35)] and b0[umod(b1[209], 20)])) or ((((b2[umod(b3[68], 35)] or b0[umod(b1[209], 20)])) and 24)))
    B = weirdRol8(b4[umod(b1[127], 21)], (b2[umod(b3[68], 35)] and 7))
    C = (((A and b0[10])) or ((B and (b0[10]).inv())))
    D = (7 xor ((b4[umod(b2[umod(b3[36], 35)], 21)] shl 1)))
    b3[72] = (((C and 71)) or ((D and (71).inv())))
    b2[2] = (b2[2] + (((((((b0[umod(b3[20], 20)] shl 1)) and 159)) or ((b4[umod(b1[190], 21)] and (159).inv())))) and ((((((((b4[umod(b3[64], 21)] and 110)) or ((b0[umod(b1[25], 20)] and (110).inv())))) and (150).inv())) or ((b1[25] and 150))))))
    b2[14] = (b2[14] - (((((b2[umod(b3[20], 35)] and ((b3[72] xor b2[umod(b1[100], 35)])))) and (34).inv())) or ((b1[97] and 34))))
    b0[17] = 115
    b1[23] = (b1[23] xor ((((((((((((b4[umod(b1[17], 21)] or b0[umod(b3[20], 20)])) and b3[72])) or ((b4[umod(b1[17], 21)] and b0[umod(b3[20], 20)])))) and (udiv(b1[50], 3)))) or (((((((((b4[umod(b1[17], 21)] or b0[umod(b3[20], 20)])) and b3[72])) or ((b4[umod(b1[17], 21)] and b0[umod(b3[20], 20)]))) or (udiv(b1[50], 3)))) and 246)))) shl 1)))
    b0[13] = ((((((((((((b0[umod(b3[40], 20)] or b1[10])) and 82)) or ((b0[umod(b3[40], 20)] and b1[10])))) and 209)) or ((((b0[umod(b1[39], 20)] shl 1)) and 46)))) ushr 1))
    b2[33] = (b2[33] - (b1[113] and 9))
    b2[28] = (b2[28] - ((((((((2 or ((b1[110] and 222)))) ushr 1)) and (223).inv())) or ((b3[20] and 223)))))
    J = weirdRol8(((V or Z)), ((U and 7)))
    A = (((b2[16] and T)) or ((W and ((b2[16]).inv()))))
    B = (((b1[33] and 17)) or ((X and (17).inv())))
    E = (((((Y or (udiv(((A + B)), 5)))) and 147)) or ((Y and (udiv(((A + B)), 5)))))
    M = (((b3[40] and b4[umod((((((b3[8] + J) + E)) and 255)), 21)])) or ((((b3[40] or b4[umod((((((b3[8] + J) + E)) and 255)), 21)])) and b2[23])))
    b0[15] = ((((((((b4[umod(b3[20], 21)] - 48)) and ((b1[184]).inv()))) or ((((b4[umod(b3[20], 21)] - 48)) and 189))) or ((189 and (b1[184]).inv())))) and (((M * M) * M)))
    b2[22] = (b2[22] + b1[183])
    b3[76] = (((3 * b4[umod(b1[1], 21)])) xor b3[0])
    A = b2[umod(((((b3[8] + ((J + E)))) and 255)), 35)]
    F = (((((((b4[umod(b1[178], 21)] and A)) or ((((b4[umod(b1[178], 21)] or A)) and 209)))) * b0[umod(b1[13], 20)])) * ((b4[umod(b1[26], 21)] ushr 1)))
    G = (((((F + 1933574137)) * 198) - (((((((F + 1933574137)) * 396) + 212)) and 212))) + 85)
    b3[80] = (((b3[36] + ((G xor 148))) + ((((G xor 107)) shl 1))) - 127)
    b3[84] = ((((b2[umod(b3[64], 35)]) and 245)) or ((b2[umod(b3[20], 35)] and 10)))
    A = (b0[umod(b3[68], 20)] or 81)
    b2[18] = (b2[18] - ((((((A * A) * A)) and (b0[15]).inv())) or (((udiv(b3[80], 15)) and b0[15]))))
    b3[88] = ((((b3[8] + J) + E) - b0[umod(b1[160], 20)]) + (udiv(b4[umod(b0[umod((((((b3[8] + J) + E)) and 255)), 20)], 21)], 3)))
    B = (((((R xor b3[72])) and (198).inv())) or ((((S * S)) and 198)))
    F = (((b4[umod(b1[69], 21)] and b1[172])) or ((((b4[umod(b1[69], 21)] or b1[172])) and ((((b3[12] - B)) + 77)))))
    b0[16] = (147 - ((((b3[72] and ((((F and 251)) or 1)))) or ((((((F and 250)) or b3[72])) and 198)))))
    C = ((((b4[umod(b1[168], 21)] and b0[umod(b1[29], 20)]) and 7)) or ((((b4[umod(b1[168], 21)] or b0[umod(b1[29], 20)])) and 6)))
    F = (((b4[umod(b1[155], 21)] and b1[105])) or ((((b4[umod(b1[155], 21)] or b1[105])) and 141)))
    b0[3] = (b0[3] - b4[umod(weirdRol32(F, C), 21)])
    b1[5] = (weirdRor8(b0[12], (((udiv(b0[umod(b1[61], 20)], 5)) and 7))) xor (udiv(((((b2[umod(b3[84], 35)]).inv()) and 0xffffffff.toInt())), 5)))
    b1[198] = (b1[198] + b1[3])
    A = ((162 or b2[umod(b3[64], 35)]))
    b1[164] = (b1[164] + (udiv(((A * A)), 5)))
    G = weirdRor8(139, ((b3[80] and 7)))
    C = ((((((b4[umod(b3[64], 21)] * b4[umod(b3[64], 21)]) * b4[umod(b3[64], 21)])) and 95)) or ((b0[umod(b3[40], 20)] and (95).inv())))
    b3[92] = (((((G and 12)) or ((b0[umod(b3[20], 20)] and 12))) or ((G and b0[umod(b3[20], 20)]))) or C)
    b2[12] = (b2[12] + udiv((((((b1[103] and 32)) or ((b3[92] and (((b1[103] or 60)))))) or 16)), 3))
    b3[96] = b1[143]
    b3[100] = 27
    b3[104] = (((((((b3[40] and (b2[8]).inv())) or ((b1[35] and b2[8])))) and b3[64])) xor 119)
    b3[108] = (238 and ((((((((b3[40] and (b2[8]).inv())) or ((b1[35] and b2[8])))) and b3[64])) shl 1)))
    b3[112] = ((((b3[64]).inv() and (udiv(b3[84], 3)))) xor 49)
    b3[116] = (98 and (((((b3[64]).inv() and (udiv(b3[84], 3)))) shl 1)))
    A = (((b1[35] and b2[8])) or ((b3[40] and (b2[8]).inv())))
    B = (((A and b3[64])) or ((((udiv(b3[84], 3)) and (b3[64]).inv()))))
    b1[143] = (b3[96] - ((((B and ((86 + ((((b1[172] and 64)) ushr 1)))))) or ((((((((((b1[172] and 65)) ushr 1)) xor 86)) or (((((b3[64]).inv() and (udiv(b3[84], 3)))) or ((((((b3[40] and (b2[8]).inv())) or ((b1[35] and b2[8])))) and b3[64])))))) and b3[100])))))
    b2[29] = 162
    A = (((((((b4[umod(b3[88], 21)]) and 160)) or ((b0[umod(b1[125], 20)] and 95)))) ushr 1))
    B = (b2[umod(b1[149], 35)] xor ((b1[43] * b1[43])))
    b0[15] = (b0[15] + (((B and A)) or ((((A or B)) and 115))))
    b3[120] = (b3[64] - b0[umod(b3[40], 20)])
    b1[95] = b4[umod(b3[20], 21)]
    A = weirdRor8(b2[umod(b3[80], 35)], ((((b2[umod(b1[17], 35)] * b2[umod(b1[17], 35)]) * b2[umod(b1[17], 35)])) and 7))
    b0[7] = (b0[7] - ((A * A)))
    b2[8] = ((b2[8] - b1[184]) + (((b4[umod(b1[202], 21)] * b4[umod(b1[202], 21)]) * b4[umod(b1[202], 21)])))
    b0[16] = (((b2[umod(b1[102], 35)] shl 1)) and 132)
    b3[124] = (((b4[umod(b3[40], 21)] ushr 1)) xor b3[68])
    b0[7] = (b0[7] - ((b0[umod(b1[191], 20)] - ((((((b4[umod(b1[80], 21)] shl 1)) and (177).inv())) or ((b4[umod(b4[umod(b3[88], 21)], 21)] and 177)))))))
    b0[6] = b0[umod(b1[119], 20)]
    A = (((b4[umod(b1[190], 21)] and (209).inv())) or ((b1[118] and 209)))
    B = (b0[umod(b3[120], 20)] * b0[umod(b3[120], 20)])
    b0[12] = (((b0[umod(b3[84], 20)] xor ((b2[umod(b1[71], 35)] + b2[umod(b1[15], 35)])))) and ((((A and B)) or ((((A or B)) and 27)))))
    B = (((b1[32] and b2[umod(b3[88], 35)])) or ((((b1[32] or b2[umod(b3[88], 35)])) and 23)))
    D = ((((((b4[umod(b1[57], 21)] * 231)) and 169)) or ((B and 86))))
    F = (((((((b0[umod(b1[82], 20)] and (29).inv())) or ((b4[umod(b3[124], 21)] and 29)))) and 190)) or ((b4[umod((udiv(D, 5)), 21)] and (190).inv())))
    H = ((b0[umod(b3[40], 20)] * b0[umod(b3[40], 20)]) * b0[umod(b3[40], 20)])
    K = ((((H and b1[82])) or ((H and 92))) or ((b1[82] and 92)))
    b3[128] = (((((F and K)) or ((((F or K)) and 192)))) xor (udiv(D, 5)))
    b2[25] = (b2[25] xor (((((b0[umod(b3[120], 20)] shl 1)) * b1[5])) - ((weirdRol8(b3[76], ((b4[umod(b3[124], 21)] and 7))) and ((b3[20] + 110))))))
}
