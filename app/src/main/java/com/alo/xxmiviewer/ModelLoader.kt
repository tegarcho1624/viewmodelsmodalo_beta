package com.alo.xxmiviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Vertices intercalados: posicion(3) normal(3) uv(2) = 8 floats por vertice. Ya convertidos a Y arriba. */
class CpuVerts(val data: FloatArray, val count: Int)

class CpuDraw(val id: String, val name: String, val count: Int, val start: Int, val conds: List<Cond>)

class CpuSection(
    val name: String,
    val vertsKey: String,
    val indices: IntArray,
    val draws: List<CpuDraw>,
    val diff: List<DiffAssign>,
    val fixedTex: String? = null,   // ruta de textura fija dentro del zip (FBX); null = la decide el .ini
    val alphaCut: Boolean = false,  // true: se descartan los pixeles transparentes de la textura (glTF MASK/BLEND)
)

class CpuModel(
    val archive: Archive,
    val ini: IniModel,
    val verts: Map<String, CpuVerts>,
    val sections: List<CpuSection>,
    val min: FloatArray,
    val max: FloatArray,
)

object ModelLoader {
    fun build(ar: Archive, ini: IniModel, progress: (String) -> Unit): CpuModel {
        val cache = HashMap<String, ByteArray>()
        fun rd(file: String): ByteArray {
            val p = ar.resolve(ini.dir, file) ?: throw IllegalStateException("Falta el archivo $file")
            return cache.getOrPut(p) { ar.read(p) }
        }

        val verts = HashMap<String, CpuVerts>()
        val sections = ArrayList<CpuSection>()
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)

        for (sec in ini.sections) {
            val ibName = sec.ib ?: continue
            if (sec.draws.isEmpty()) continue
            val ibRes = ini.res[ibName] ?: continue
            val ibFile = ibRes.props["filename"] ?: continue

            // XXMI: ResourceXIB -> ResourceXPosition / ResourceXTexcoord (la letra final A/B... se descarta)
            val base = ibName.removeSuffix("ib")
            var posName: String? = null
            var texName: String? = null
            for (b in listOf(base, base.dropLast(1))) {
                if (ini.res.containsKey(b + "position")) {
                    posName = b + "position"
                    texName = if (ini.res.containsKey(b + "texcoord")) b + "texcoord" else null
                    break
                }
            }
            if (posName == null) continue
            val posFile = ini.res[posName]?.props?.get("filename") ?: continue

            progress("Leyendo ${sec.name}…")
            if (!verts.containsKey(posName)) {
                val stride = ini.res[posName]?.props?.get("stride")?.toIntOrNull() ?: 40
                var tex: ByteArray? = null
                var tstride = 20
                val tRes = if (texName != null) ini.res[texName] else null
                val tf = tRes?.props?.get("filename")
                if (tf != null) {
                    tex = rd(tf)
                    tstride = tRes?.props?.get("stride")?.toIntOrNull() ?: 20
                }
                val v = buildVerts(rd(posFile), stride, tex, tstride)
                verts[posName] = v
                val d = v.data
                for (i in 0 until v.count) {
                    for (k in 0 until 3) {
                        val x = d[i * 8 + k]
                        if (x < mn[k]) mn[k] = x
                        if (x > mx[k]) mx[k] = x
                    }
                }
            }

            val ibBytes = rd(ibFile)
            val is16 = (ibRes.props["format"] ?: "").contains("R16_UINT", ignoreCase = true)
            val bb = ByteBuffer.wrap(ibBytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = if (is16) ibBytes.size / 2 else ibBytes.size / 4
            val idx = IntArray(n) { if (is16) bb.getShort(it * 2).toInt() and 0xFFFF else bb.getInt(it * 4) }

            val si = sections.size
            val draws = ArrayList<CpuDraw>()
            for (d in sec.draws) {
                if (d.start >= n) continue
                val count = minOf(d.count, n - d.start)
                draws.add(CpuDraw("$si:${d.name}:${d.start}", d.name, count, d.start, d.conds))
            }
            if (draws.isNotEmpty()) sections.add(CpuSection(sec.name, posName, idx, draws, sec.diff))
        }
        if (sections.isEmpty()) throw IllegalStateException("No pude emparejar los buffers del .ini con sus archivos.")
        return CpuModel(ar, ini, verts, sections, mn, mx)
    }

    private fun buildVerts(pb: ByteArray, ps: Int, tb: ByteArray?, ts: Int): CpuVerts {
        val n = pb.size / ps
        val p = ByteBuffer.wrap(pb).order(ByteOrder.LITTLE_ENDIAN)
        val t = tb?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        val tn = if (tb != null) tb.size / ts else 0
        val out = FloatArray(n * 8)
        for (i in 0 until n) {
            val o = i * ps
            val x = p.getFloat(o)
            val y = p.getFloat(o + 4)
            val z = p.getFloat(o + 8)
            val j = i * 8
            // Z arriba (juego) -> Y arriba (visor): (x, y, z) -> (x, z, -y)
            out[j] = x; out[j + 1] = z; out[j + 2] = -y
            out[j + 3] = p.getFloat(o + 12)
            out[j + 4] = p.getFloat(o + 20)
            out[j + 5] = -p.getFloat(o + 16)
            if (t != null && i < tn) {
                val q = i * ts + 4
                out[j + 6] = half(t.getShort(q).toInt() and 0xFFFF)
                out[j + 7] = half(t.getShort(q + 2).toInt() and 0xFFFF)
            }
        }
        return CpuVerts(out, n)
    }

    private fun half(h: Int): Float {
        val e = (h shr 10) and 31
        val f = h and 1023
        val s = if (h and 0x8000 != 0) -1f else 1f
        return when (e) {
            0 -> s * 5.960464477539063e-8f * f
            31 -> if (f != 0) Float.NaN else s * Float.POSITIVE_INFINITY
            else -> s * Math.pow(2.0, (e - 15).toDouble()).toFloat() * (1f + f / 1024f)
        }
    }
}
