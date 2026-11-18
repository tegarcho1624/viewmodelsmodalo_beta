package com.alo.xxmiviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lector del formato .blend de Blender (cabecera clasica: 2.5 hasta 4.1).
 * Un .blend es una lista de bloques; la tabla DNA1 (SDNA) describe la forma de cada estructura,
 * asi que los campos se buscan por nombre y no por posiciones fijas.
 */
class BlendFile(val d: ByteArray) {
    class Block(val code: String, val size: Int, val old: Long, val sdna: Int, val count: Int, val pos: Int)
    class Layout(val off: HashMap<String, Int>, val size: Int)

    val ptrSize: Int
    val version: Int
    val blocks = ArrayList<Block>()
    private val byOld = HashMap<Long, Block>()
    private val bb: ByteBuffer

    private var names: Array<String> = emptyArray()
    private var types: Array<String> = emptyArray()
    private var tlen: IntArray = IntArray(0)
    private var structType: IntArray = IntArray(0)
    private var structFields: Array<IntArray> = emptyArray()
    private val structIdx = HashMap<String, Int>()
    private val layouts = HashMap<String, Layout>()

    init {
        if (d.size < 12 || String(d, 0, 7, Charsets.ISO_8859_1) != "BLENDER") {
            throw IllegalArgumentException("No es un archivo .blend.")
        }
        val c7 = d[7].toInt().toChar()
        if (c7 != '_' && c7 != '-') {
            throw IllegalArgumentException("Este .blend usa el formato nuevo de Blender 4.2 o mas reciente, que todavia no se puede leer. Exportalo a FBX u OBJ desde Blender.")
        }
        ptrSize = if (c7 == '-') 8 else 4
        val little = d[8].toInt().toChar() == 'v'
        bb = ByteBuffer.wrap(d).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        version = String(d, 9, 3, Charsets.ISO_8859_1).toIntOrNull() ?: 0

        val hs = if (ptrSize == 8) 24 else 20
        var pos = 12
        while (pos + hs <= d.size) {
            val code = readCode(pos)
            val size = bb.getInt(pos + 4)
            val old = if (ptrSize == 8) bb.getLong(pos + 8) else (bb.getInt(pos + 8).toLong() and 0xFFFFFFFFL)
            val sdna = bb.getInt(pos + 8 + ptrSize)
            val count = bb.getInt(pos + 12 + ptrSize)
            val b = Block(code, size, old, sdna, count, pos + hs)
            blocks.add(b)
            if (code == "ENDB") break
            if (old != 0L) byOld[old] = b
            if (size < 0) break
            pos += hs + size
        }
        readDna()
    }

    private fun readCode(pos: Int): String {
        var e = 4
        while (e > 0 && d[pos + e - 1].toInt() == 0) e--
        return String(d, pos, e, Charsets.ISO_8859_1)
    }

    private fun readStrings(start: Int, n: Int, out: ArrayList<String>): Int {
        var p = start
        for (i in 0 until n) {
            var e = p
            while (d[e].toInt() != 0) e++
            out.add(String(d, p, e - p, Charsets.ISO_8859_1))
            p = e + 1
        }
        return p
    }

    private fun readDna() {
        val b = blocks.firstOrNull { it.code == "DNA1" }
            ?: throw IllegalArgumentException("El .blend no tiene la tabla de estructuras (DNA1).")
        var p = b.pos + 8                       // "SDNA" y "NAME"
        var n = bb.getInt(p)
        p += 4
        val nm = ArrayList<String>(n)
        p = readStrings(p, n, nm)
        p = (p + 3) and 3.inv()
        p += 4                                  // "TYPE"
        n = bb.getInt(p)
        p += 4
        val ty = ArrayList<String>(n)
        p = readStrings(p, n, ty)
        p = (p + 3) and 3.inv()
        p += 4                                  // "TLEN"
        val tl = IntArray(n)
        for (i in 0 until n) {
            tl[i] = bb.getShort(p).toInt() and 0xFFFF
            p += 2
        }
        p = (p + 3) and 3.inv()
        p += 4                                  // "STRC"
        val ns = bb.getInt(p)
        p += 4
        val st = IntArray(ns)
        val sf = ArrayList<IntArray>(ns)
        for (i in 0 until ns) {
            st[i] = bb.getShort(p).toInt() and 0xFFFF
            val nf = bb.getShort(p + 2).toInt() and 0xFFFF
            p += 4
            val f = IntArray(nf * 2)
            for (k in 0 until nf * 2) {
                f[k] = bb.getShort(p).toInt() and 0xFFFF
                p += 2
            }
            sf.add(f)
        }
        names = nm.toTypedArray()
        types = ty.toTypedArray()
        tlen = tl
        structType = st
        structFields = sf.toTypedArray()
        for (i in 0 until ns) structIdx[types[st[i]]] = i
    }

    /** Posicion de cada campo dentro de su estructura y tamano total, segun la DNA del propio archivo. */
    fun layout(s: String): Layout {
        layouts[s]?.let { return it }
        val si = structIdx[s] ?: throw IllegalArgumentException("El .blend no tiene la estructura $s.")
        val f = structFields[si]
        val off = HashMap<String, Int>()
        var o = 0
        var k = 0
        while (k < f.size) {
            val ft = f[k]
            val fn = f[k + 1]
            k += 2
            var nm = names[fn]
            val isPtr = nm.startsWith("*") || nm.startsWith("(*")
            nm = nm.trimStart('*')
            if (nm.startsWith("(")) nm = nm.substring(2, nm.indexOf(')'))
            var arr = 1
            val br = nm.indexOf('[')
            if (br >= 0) {
                for (part in nm.substring(br).split("[")) {
                    val t = part.removeSuffix("]")
                    if (t.isNotEmpty()) arr *= (t.toIntOrNull() ?: 1)
                }
                nm = nm.substring(0, br)
            }
            off[nm] = o
            o += (if (isPtr) ptrSize else tlen[ft]) * arr
        }
        val l = Layout(off, o)
        layouts[s] = l
        return l
    }

    fun has(s: String, f: String): Boolean = structIdx.containsKey(s) && layout(s).off.containsKey(f)

    /** Posicion relativa del campo [f] dentro de la estructura [s]. */
    fun fo(s: String, f: String): Int =
        layout(s).off[f] ?: throw IllegalArgumentException("El .blend no tiene el campo $s.$f.")

    fun sizeOf(s: String): Int = layout(s).size

    fun i32(pos: Int): Int = bb.getInt(pos)
    fun i16(pos: Int): Int = bb.getShort(pos).toInt()
    fun u8(pos: Int): Int = d[pos].toInt() and 0xFF
    fun f32(pos: Int): Float = bb.getFloat(pos)
    fun ptr(pos: Int): Long = if (ptrSize == 8) bb.getLong(pos) else (bb.getInt(pos).toLong() and 0xFFFFFFFFL)

    /** Bloque al que apunta el puntero guardado en el archivo (direccion original de Blender). */
    fun block(p: Long): Block? = if (p == 0L) null else byOld[p]

    fun blocksOf(code: String): List<Block> = blocks.filter { it.code == code }

    fun str(pos: Int, max: Int): String {
        var e = pos
        val lim = minOf(pos + max, d.size)
        while (e < lim && d[e].toInt() != 0) e++
        return String(d, pos, e - pos, Charsets.UTF_8)
    }

    /** Nombre del dato (objeto, material, imagen...) sin el prefijo de dos letras. */
    fun idName(pos: Int): String {
        val n = str(pos + fo("ID", "name"), 66)
        return if (n.length > 2) n.substring(2) else n
    }
}
