package com.alo.xxmiviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

class Img(val w: Int, val h: Int, val rgba: ByteArray, val srgb: Boolean)

/**
 * Lector de DDS en software: BC1, BC3, BC7 y RGBA/BGRA sin comprimir.
 * Decodifica por filas de bloques y reduce a [maxSize] para no gastar memoria (4096 -> 2048).
 */
object Dds {
    fun decode(d: ByteArray, maxSize: Int): Img {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        require(d.size > 128 && bb.getInt(0) == 0x20534444) { "No es un DDS valido" }
        val h = bb.getInt(12)
        val w = bb.getInt(16)
        val fourcc = String(d, 84, 4, Charsets.ISO_8859_1)
        var off = 128
        var dxgi = 0
        if (fourcc == "DX10") { dxgi = bb.getInt(128); off = 148 }

        // tipo: 1 = BC1, 3 = BC3, 7 = BC7, 10 = RGBA8, 11 = BGRA8
        var type = 0
        var srgb = false
        when {
            fourcc == "DXT1" -> type = 1
            fourcc == "DXT5" -> type = 3
            dxgi == 71 -> type = 1
            dxgi == 72 -> { type = 1; srgb = true }
            dxgi == 77 -> type = 3
            dxgi == 78 -> { type = 3; srgb = true }
            dxgi == 98 -> type = 7
            dxgi == 99 -> { type = 7; srgb = true }
            dxgi == 28 -> type = 10
            dxgi == 29 -> { type = 10; srgb = true }
            dxgi == 87 -> type = 11
            dxgi == 91 -> { type = 11; srgb = true }
            fourcc.all { it == '\u0000' } && bb.getInt(88) == 32 -> type = if (bb.getInt(92) == 0xFF) 10 else 11
        }
        require(type != 0) { "Formato DDS no soportado (${if (dxgi != 0) "DXGI $dxgi" else fourcc})" }

        var s = 1
        while (maxOf(w, h) / s > maxSize && s < 4) s *= 2
        val ow = w / s
        val oh = h / s
        val out = ByteArray(ow * oh * 4)

        if (type >= 10) {
            for (y in 0 until oh) for (x in 0 until ow) {
                val si = off + ((y * s) * w + x * s) * 4
                if (si + 3 >= d.size) continue
                val o = (y * ow + x) * 4
                if (type == 10) {
                    out[o] = d[si]; out[o + 1] = d[si + 1]; out[o + 2] = d[si + 2]; out[o + 3] = d[si + 3]
                } else {
                    out[o] = d[si + 2]; out[o + 1] = d[si + 1]; out[o + 2] = d[si]; out[o + 3] = d[si + 3]
                }
            }
            return Img(ow, oh, out, srgb)
        }

        val bw = (w + 3) / 4
        val bh = (h + 3) / 4
        val rowW = bw * 4
        val tmp = ByteArray(rowW * 4 * 4)       // 4 filas de pixeles RGBA
        val px = IntArray(64)                    // un bloque: 16 pixeles * RGBA
        val bc7 = Bc7Decoder()
        val blockBytes = if (type == 1) 8 else 16
        val rowsOut = 4 / s

        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                val p = off + (by * bw + bx) * blockBytes
                if (p + blockBytes > d.size) { java.util.Arrays.fill(px, 0) }
                else when (type) {
                    7 -> bc7.decode(bb.getLong(p), bb.getLong(p + 8), px)
                    1 -> decodeBc1(d, p, px, false)
                    else -> { decodeBc1(d, p + 8, px, true); decodeBc3Alpha(d, p, px) }
                }
                for (r in 0 until 4) for (c in 0 until 4) {
                    val dst = (r * rowW + bx * 4 + c) * 4
                    val src = (r * 4 + c) * 4
                    tmp[dst] = px[src].toByte(); tmp[dst + 1] = px[src + 1].toByte()
                    tmp[dst + 2] = px[src + 2].toByte(); tmp[dst + 3] = px[src + 3].toByte()
                }
            }
            val n = s * s
            for (oyl in 0 until rowsOut) {
                val oy = by * rowsOut + oyl
                if (oy >= oh) break
                for (ox in 0 until ow) {
                    val o = (oy * ow + ox) * 4
                    if (s == 1) {
                        val si = (oyl * rowW + ox) * 4
                        out[o] = tmp[si]; out[o + 1] = tmp[si + 1]; out[o + 2] = tmp[si + 2]; out[o + 3] = tmp[si + 3]
                    } else {
                        for (ch in 0 until 4) {
                            var sum = 0
                            for (dy in 0 until s) for (dx in 0 until s) {
                                sum += tmp[((oyl * s + dy) * rowW + ox * s + dx) * 4 + ch].toInt() and 0xFF
                            }
                            out[o + ch] = ((sum + n / 2) / n).toByte()
                        }
                    }
                }
            }
        }
        return Img(ow, oh, out, srgb)
    }

    private fun decodeBc1(d: ByteArray, p: Int, px: IntArray, forceFour: Boolean) {
        val c0 = (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8)
        val c1 = (d[p + 2].toInt() and 0xFF) or ((d[p + 3].toInt() and 0xFF) shl 8)
        val col = IntArray(16)   // 4 colores RGBA
        fun set(i: Int, r: Int, g: Int, b: Int, a: Int) { col[i * 4] = r; col[i * 4 + 1] = g; col[i * 4 + 2] = b; col[i * 4 + 3] = a }
        fun r5(c: Int) = ((c shr 11) and 31).let { (it shl 3) or (it shr 2) }
        fun g6(c: Int) = ((c shr 5) and 63).let { (it shl 2) or (it shr 4) }
        fun b5(c: Int) = (c and 31).let { (it shl 3) or (it shr 2) }
        val r0 = r5(c0); val g0 = g6(c0); val b0 = b5(c0)
        val r1 = r5(c1); val g1 = g6(c1); val b1 = b5(c1)
        set(0, r0, g0, b0, 255)
        set(1, r1, g1, b1, 255)
        if (c0 > c1 || forceFour) {
            set(2, (2 * r0 + r1) / 3, (2 * g0 + g1) / 3, (2 * b0 + b1) / 3, 255)
            set(3, (r0 + 2 * r1) / 3, (g0 + 2 * g1) / 3, (b0 + 2 * b1) / 3, 255)
        } else {
            set(2, (r0 + r1) / 2, (g0 + g1) / 2, (b0 + b1) / 2, 255)
            set(3, 0, 0, 0, 0)
        }
        val bits = (d[p + 4].toInt() and 0xFF) or ((d[p + 5].toInt() and 0xFF) shl 8) or
            ((d[p + 6].toInt() and 0xFF) shl 16) or ((d[p + 7].toInt() and 0xFF) shl 24)
        for (i in 0 until 16) {
            val ci = (bits ushr (i * 2)) and 3
            px[i * 4] = col[ci * 4]; px[i * 4 + 1] = col[ci * 4 + 1]; px[i * 4 + 2] = col[ci * 4 + 2]; px[i * 4 + 3] = col[ci * 4 + 3]
        }
    }

    private fun decodeBc3Alpha(d: ByteArray, p: Int, px: IntArray) {
        val a0 = d[p].toInt() and 0xFF
        val a1 = d[p + 1].toInt() and 0xFF
        val al = IntArray(8)
        al[0] = a0; al[1] = a1
        if (a0 > a1) { for (i in 1..6) al[i + 1] = ((7 - i) * a0 + i * a1) / 7 }
        else { for (i in 1..4) al[i + 1] = ((5 - i) * a0 + i * a1) / 5; al[6] = 0; al[7] = 255 }
        var bits = 0L
        for (i in 0 until 6) bits = bits or ((d[p + 2 + i].toLong() and 0xFF) shl (8 * i))
        for (i in 0 until 16) px[i * 4 + 3] = al[((bits ushr (3 * i)) and 7).toInt()]
    }
}

/** Decodificador BC7 (8 modos). Verificado bloque a bloque contra una implementacion de referencia. */
class Bc7Decoder {
    private val ep = IntArray(24)        // 6 extremos * RGBA
    private val i1 = IntArray(16)
    private val i2 = IntArray(16)
    private var lo = 0L
    private var hi = 0L
    private var pos = 0

    private fun rd(n: Int): Int {
        if (n == 0) return 0
        val mask = (1L shl n) - 1
        val r = when {
            pos >= 64 -> (hi ushr (pos - 64)) and mask
            pos + n <= 64 -> (lo ushr pos) and mask
            else -> ((lo ushr pos) or (hi shl (64 - pos))) and mask
        }
        pos += n
        return r.toInt()
    }

    fun decode(lo: Long, hi: Long, out: IntArray) {
        this.lo = lo; this.hi = hi
        val m = java.lang.Long.numberOfTrailingZeros(lo)
        if (lo == 0L || m >= 8) { java.util.Arrays.fill(out, 0); return }
        pos = m + 1
        val md = MODES[m]
        val ns = md[0]; val pb = md[1]; val rb = md[2]; val isbBits = md[3]
        val cb = md[4]; val ab = md[5]; val epb = md[6]; val spb = md[7]; val ib = md[8]; val ib2 = md[9]

        val part = rd(pb)
        val rot = rd(rb)
        val isb = rd(isbBits)
        val ne = ns * 2
        for (e in 0 until ne) { ep[e * 4] = 0; ep[e * 4 + 1] = 0; ep[e * 4 + 2] = 0; ep[e * 4 + 3] = 255 }
        for (ch in 0 until 3) for (e in 0 until ne) ep[e * 4 + ch] = rd(cb)
        if (ab > 0) for (e in 0 until ne) ep[e * 4 + 3] = rd(ab)
        var cprec = cb
        var aprec = ab
        if (epb == 1) {
            for (e in 0 until ne) {
                val p = rd(1)
                for (ch in 0 until 3) ep[e * 4 + ch] = (ep[e * 4 + ch] shl 1) or p
                if (ab > 0) ep[e * 4 + 3] = (ep[e * 4 + 3] shl 1) or p
            }
            cprec++
            if (ab > 0) aprec++
        } else if (spb == 1) {
            for (s in 0 until ns) {
                val p = rd(1)
                for (e in (2 * s)..(2 * s + 1)) for (ch in 0 until 3) ep[e * 4 + ch] = (ep[e * 4 + ch] shl 1) or p
            }
            cprec++
        }
        for (e in 0 until ne) {
            for (ch in 0 until 3) ep[e * 4 + ch] = expand(ep[e * 4 + ch], cprec)
            if (ab > 0) ep[e * 4 + 3] = expand(ep[e * 4 + 3], aprec)
        }

        val a1 = when (ns) { 2 -> Bc7Tables.A2[part]; 3 -> Bc7Tables.A3[part * 2]; else -> -1 }
        val a2 = if (ns == 3) Bc7Tables.A3[part * 2 + 1] else -1

        readIdx(ib, i1, ns, part, a1, a2)
        if (ib2 > 0) readIdx(ib2, i2, ns, part, a1, a2)

        for (i in 0 until 16) {
            val s = subset(i, ns, part)
            val e0 = s * 8
            val e1 = s * 8 + 4
            val cbits: Int
            val abits: Int
            val ci: Int
            val ai: Int
            if (ib2 > 0) {
                if (isb == 0) { cbits = ib; abits = ib2; ci = i1[i]; ai = i2[i] }
                else { cbits = ib2; abits = ib; ci = i2[i]; ai = i1[i] }
            } else { cbits = ib; abits = ib; ci = i1[i]; ai = i1[i] }
            val wc = weight(cbits, ci)
            val wa = weight(abits, ai)
            var r = (ep[e0] * (64 - wc) + ep[e1] * wc + 32) shr 6
            var g = (ep[e0 + 1] * (64 - wc) + ep[e1 + 1] * wc + 32) shr 6
            var b = (ep[e0 + 2] * (64 - wc) + ep[e1 + 2] * wc + 32) shr 6
            var a = (ep[e0 + 3] * (64 - wa) + ep[e1 + 3] * wa + 32) shr 6
            when (rot) {
                1 -> { val t = a; a = r; r = t }
                2 -> { val t = a; a = g; g = t }
                3 -> { val t = a; a = b; b = t }
            }
            out[i * 4] = r; out[i * 4 + 1] = g; out[i * 4 + 2] = b; out[i * 4 + 3] = a
        }
    }

    private fun subset(i: Int, ns: Int, part: Int): Int = when (ns) {
        1 -> 0
        2 -> Bc7Tables.P2[part * 16 + i]
        else -> Bc7Tables.P3[part * 16 + i]
    }

    private fun readIdx(bits: Int, dst: IntArray, ns: Int, part: Int, a1: Int, a2: Int) {
        for (i in 0 until 16) {
            val s = subset(i, ns, part)
            val anchor = (s == 0 && i == 0) || (s == 1 && i == a1) || (s == 2 && i == a2)
            dst[i] = rd(if (anchor) bits - 1 else bits)
        }
    }

    private fun expand(x: Int, p: Int): Int = (x shl (8 - p)) or (x shr (2 * p - 8))

    private fun weight(bits: Int, idx: Int): Int = when (bits) {
        2 -> W2[idx]
        3 -> W3[idx]
        else -> W4[idx]
    }

    companion object {
        // ns, pb, rb, isb, cb, ab, epb, spb, ib, ib2
        private val MODES = arrayOf(
            intArrayOf(3, 4, 0, 0, 4, 0, 1, 0, 3, 0),
            intArrayOf(2, 6, 0, 0, 6, 0, 0, 1, 3, 0),
            intArrayOf(3, 6, 0, 0, 5, 0, 0, 0, 2, 0),
            intArrayOf(2, 6, 0, 0, 7, 0, 1, 0, 2, 0),
            intArrayOf(1, 0, 2, 1, 5, 6, 0, 0, 2, 3),
            intArrayOf(1, 0, 2, 0, 7, 8, 0, 0, 2, 2),
            intArrayOf(1, 0, 0, 0, 7, 7, 1, 0, 4, 0),
            intArrayOf(2, 6, 0, 0, 5, 5, 1, 0, 2, 0),
        )
        private val W2 = intArrayOf(0, 21, 43, 64)
        private val W3 = intArrayOf(0, 9, 18, 27, 37, 46, 55, 64)
        private val W4 = intArrayOf(0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64)
    }
}
