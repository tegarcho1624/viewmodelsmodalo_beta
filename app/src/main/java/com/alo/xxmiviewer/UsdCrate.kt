package com.alo.xxmiviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Descompresion LZ4 (formato de bloque) y el envoltorio de chunks que usa USD (TfFastCompression). */
object Lz4 {
    fun block(src: ByteArray, start: Int, len: Int, out: ByteArray, outStart: Int): Int {
        var o = outStart
        var i = start
        val end = start + len
        while (i < end) {
            val t = src[i++].toInt() and 0xFF
            var ll = t shr 4
            if (ll == 15) {
                while (true) {
                    val b = src[i++].toInt() and 0xFF
                    ll += b
                    if (b != 255) break
                }
            }
            System.arraycopy(src, i, out, o, ll)
            i += ll
            o += ll
            if (i >= end) break
            val off = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            var ml = t and 15
            if (ml == 15) {
                while (true) {
                    val b = src[i++].toInt() and 0xFF
                    ml += b
                    if (b != 255) break
                }
            }
            ml += 4
            var s = o - off
            for (k in 0 until ml) out[o++] = out[s++]
        }
        return o - outStart
    }

    /** Formato de USD: 1 byte con el numero de chunks (0 = un solo bloque), luego [int32 tamano + datos] por chunk. */
    fun fast(src: ByteArray, start: Int, len: Int, outSize: Int): ByteArray {
        val out = ByteArray(outSize)
        val nch = src[start].toInt() and 0xFF
        if (nch == 0) {
            block(src, start + 1, len - 1, out, 0)
            return out
        }
        val bb = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        var p = start + 1
        var o = 0
        val endAll = start + len
        while (p < endAll && o < outSize) {
            val cs = bb.getInt(p)
            p += 4
            o += block(src, p, cs, out, o)
            p += cs
        }
        return out
    }
}

/** Lector del formato binario USDC ("crate") de OpenUSD, version 0.4 o mayor. */
class UsdCrate(val d: ByteArray) {
    class Spec(val path: String, val type: Int, val fields: HashMap<String, Long>)

    private val bb: ByteBuffer = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
    private val minor: Int
    private val sections = HashMap<String, LongArray>()
    lateinit var tokens: Array<String>
    private lateinit var fieldTok: IntArray
    private lateinit var fieldRep: LongArray
    private lateinit var fsets: IntArray
    lateinit var paths: Array<String>
    val specs = HashMap<String, Spec>()

    init {
        if (d.size < 88 || String(d, 0, 8, Charsets.ISO_8859_1) != "PXR-USDC") {
            throw IllegalArgumentException("No es un USD binario (.usdc).")
        }
        minor = d[9].toInt()
        if (d[8].toInt() != 0 || minor < 4) throw IllegalArgumentException("Version de USDC no soportada: ${d[8]}.${d[9]}")
        val toc = bb.getLong(16).toInt()
        val ns = bb.getLong(toc).toInt()
        for (i in 0 until ns) {
            val o = toc + 8 + i * 32
            var e = 0
            while (e < 16 && d[o + e].toInt() != 0) e++
            val name = String(d, o, e, Charsets.ISO_8859_1)
            sections[name] = longArrayOf(bb.getLong(o + 16), bb.getLong(o + 24))
        }
        readTokens()
        readFields()
        readFieldSets()
        readPaths()
        readSpecs()
    }

    private fun sec(name: String): Int = (sections[name] ?: throw IllegalArgumentException("USDC sin seccion $name"))[0].toInt()

    /** Enteros de 32 bits comprimidos (delta + codigos de 2 bits + LZ4). Devuelve los valores y la posicion siguiente. */
    private fun cints(pos: Int, n: Int): Pair<IntArray, Int> {
        val cs = bb.getLong(pos).toInt()
        val raw = Lz4.fast(d, pos + 8, cs, n * 4 + (n + 3) / 4 + 4 + 8)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val common = b.getInt(0)
        var p = 4 + (n + 3) / 4
        val res = IntArray(n)
        var prev = 0
        for (i in 0 until n) {
            val code = (raw[4 + i / 4].toInt() shr ((i % 4) * 2)) and 3
            val delta = when (code) {
                0 -> common
                1 -> { val v = raw[p].toInt(); p += 1; v }
                2 -> { val v = b.getShort(p).toInt(); p += 2; v }
                else -> { val v = b.getInt(p); p += 4; v }
            }
            prev += delta
            res[i] = prev
        }
        return res to (pos + 8 + cs)
    }

    private fun readTokens() {
        val st = sec("TOKENS")
        val n = bb.getLong(st).toInt()
        val us = bb.getLong(st + 8).toInt()
        val cs = bb.getLong(st + 16).toInt()
        val raw = Lz4.fast(d, st + 24, cs, us)
        val out = ArrayList<String>(n)
        var s = 0
        for (i in 0 until us) {
            if (raw[i].toInt() == 0) {
                out.add(String(raw, s, i - s, Charsets.UTF_8))
                s = i + 1
                if (out.size == n) break
            }
        }
        tokens = out.toTypedArray()
    }

    private fun readFields() {
        val st = sec("FIELDS")
        val n = bb.getLong(st).toInt()
        val (ti, pos) = cints(st + 8, n)
        val cs = bb.getLong(pos).toInt()
        val raw = Lz4.fast(d, pos + 8, cs, n * 8)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        fieldTok = ti
        fieldRep = LongArray(n) { b.getLong(it * 8) }
    }

    private fun readFieldSets() {
        val st = sec("FIELDSETS")
        val n = bb.getLong(st).toInt()
        fsets = cints(st + 8, n).first
    }

    private fun readPaths() {
        val st = sec("PATHS")
        val total = bb.getLong(st).toInt()
        val enc = bb.getLong(st + 8).toInt()
        val (pi, p1) = cints(st + 16, enc)
        val (et, p2) = cints(p1, enc)
        val (jm, _) = cints(p2, enc)
        val out = Array(total) { "" }
        // reconstruccion del arbol de rutas (OpenUSD: _BuildDecompressedPathsImpl)
        fun build(start: Int, parentIn: String?) {
            var cur = start
            var parent = parentIn
            var hasChild = true
            var hasSibling = true
            while (hasChild || hasSibling) {
                val th = cur++
                if (parent == null) {
                    parent = "/"
                    out[pi[th]] = "/"
                } else {
                    val t = et[th]
                    val prop = t < 0
                    val tok = tokens[if (t < 0) -t else t]
                    out[pi[th]] = if (prop) "$parent.$tok" else if (parent == "/") "/$tok" else "$parent/$tok"
                }
                hasChild = jm[th] > 0 || jm[th] == -1
                hasSibling = jm[th] >= 0
                if (hasChild) {
                    if (hasSibling) build(th + jm[th], parent)
                    parent = out[pi[th]]
                }
            }
        }
        if (enc > 0) build(0, null)
        paths = out
    }

    private fun readSpecs() {
        val st = sec("SPECS")
        val n = bb.getLong(st).toInt()
        val (a, p1) = cints(st + 8, n)
        val (b, p2) = cints(p1, n)
        val (c, _) = cints(p2, n)
        for (i in 0 until n) {
            val f = HashMap<String, Long>()
            var k = b[i]
            while (k < fsets.size && fsets[k] != -1) {
                val fi = fsets[k]
                f[tokens[fieldTok[fi]]] = fieldRep[fi]
                k++
            }
            val path = paths[a[i]]
            specs[path] = Spec(path, c[i], f)
        }
    }

    // ---------------------------------------------------------------- Valores

    companion object {
        fun typeOf(r: Long): Int = ((r ushr 48) and 0xFF).toInt()
        fun isInline(r: Long): Boolean = ((r ushr 62) and 1L) != 0L
        fun isCompressed(r: Long): Boolean = ((r ushr 61) and 1L) != 0L
        fun payload(r: Long): Long = r and 0xFFFFFFFFFFFFL
    }

    /** Token o ruta de asset guardados en linea (el payload es un indice de token). */
    fun token(r: Long?): String? {
        if (r == null || !isInline(r)) return null
        return tokens.getOrNull(payload(r).toInt())
    }

    private fun arrayCount(o: Int): Int = if (minor >= 7) bb.getLong(o).toInt() else bb.getInt(o)
    private fun arrayHdr(): Int = if (minor >= 7) 8 else 4

    fun ints(r: Long?): IntArray? {
        if (r == null || isInline(r)) return null
        var o = payload(r).toInt()
        val n = arrayCount(o)
        o += arrayHdr()
        if (isCompressed(r)) return cints(o, n).first
        return IntArray(n) { bb.getInt(o + it * 4) }
    }

    /** Array de floats con [k] componentes por elemento (Vec3f -> k = 3, Vec2f -> k = 2). */
    fun floats(r: Long?, k: Int): FloatArray? {
        if (r == null || isInline(r) || isCompressed(r)) return null
        var o = payload(r).toInt()
        val n = arrayCount(o)
        o += arrayHdr()
        val t = typeOf(r)
        if (t == 19 || t == 23 || t == 27 || t == 9) return FloatArray(n * k) { bb.getDouble(o + it * 8).toFloat() }
        return FloatArray(n * k) { bb.getFloat(o + it * 4) }
    }

    fun tokenList(r: Long?): List<String> {
        if (r == null || isInline(r)) return emptyList()
        var o = payload(r).toInt()
        val n = arrayCount(o)
        o += arrayHdr()
        return List(n) { tokens[bb.getInt(o + it * 4)] }
    }

    /** Vec3f / Vec3d escalar. Los valores en linea se guardan como 3 enteros de 8 bits. */
    fun vec3(r: Long?): DoubleArray? {
        if (r == null) return null
        if (isInline(r)) {
            val p = payload(r)
            return DoubleArray(3) { ((p ushr (8 * it)) and 0xFF).toByte().toDouble() }
        }
        val o = payload(r).toInt()
        return when (typeOf(r)) {
            23 -> DoubleArray(3) { bb.getDouble(o + it * 8) }
            24 -> DoubleArray(3) { bb.getFloat(o + it * 4).toDouble() }
            else -> null
        }
    }

    /** Valor escalar (Float/Double/Int) como Double; en linea, Float y Double se guardan como float32. */
    fun number(r: Long?): Double? {
        if (r == null) return null
        val t = typeOf(r)
        if (isInline(r)) {
            val p = payload(r).toInt()
            return when (t) { 8, 9 -> java.lang.Float.intBitsToFloat(p).toDouble(); 3, 4 -> p.toDouble(); else -> null }
        }
        val o = payload(r).toInt()
        return when (t) { 9 -> bb.getDouble(o); 8 -> bb.getFloat(o).toDouble(); else -> null }
    }

    /** Matrix4d (fila-mayor, convencion de vectores fila). En linea es solo la diagonal en 4 enteros de 8 bits. */
    fun matrix4(r: Long?): DoubleArray? {
        if (r == null) return null
        if (isInline(r)) {
            val p = payload(r)
            val m = DoubleArray(16)
            for (i in 0 until 4) m[i * 5] = ((p ushr (8 * i)) and 0xFF).toByte().toDouble()
            return m
        }
        val o = payload(r).toInt()
        return DoubleArray(16) { bb.getDouble(o + it * 8) }
    }

    /** Cuaternion (x, y, z, w) guardado como Quatf (17) o Quatd (16). */
    fun quat(r: Long?): DoubleArray? {
        if (r == null || isInline(r)) return null
        val o = payload(r).toInt()
        return when (typeOf(r)) {
            16 -> DoubleArray(4) { bb.getDouble(o + it * 8) }
            17 -> DoubleArray(4) { bb.getFloat(o + it * 4).toDouble() }
            else -> null
        }
    }

    /** PathListOp (relaciones y conexiones): devuelve todas las rutas de las listas presentes (menos las borradas). */
    fun pathList(r: Long?): List<String> {
        if (r == null || isInline(r)) return emptyList()
        var o = payload(r).toInt()
        val flags = d[o].toInt() and 0xFF
        o++
        val out = ArrayList<String>()
        // orden en el archivo: explicit(2), added(4), deleted(8), ordered(16), prepended(32), appended(64)
        for (bit in intArrayOf(2, 4, 8, 16, 32, 64)) {
            if (flags and bit == 0) continue
            val n = bb.getLong(o).toInt()
            o += 8
            for (i in 0 until n) {
                if (bit != 8 && bit != 16) paths.getOrNull(bb.getInt(o))?.let { out.add(it) }
                o += 4
            }
        }
        return out
    }
}
