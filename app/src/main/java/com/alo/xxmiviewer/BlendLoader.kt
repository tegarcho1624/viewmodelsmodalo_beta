package com.alo.xxmiviewer

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt

private class BIntBuf {
    var a = IntArray(1024)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

/**
 * Convierte un .blend (Blender 2.5 a 3.5 con mallas en formato clasico) en un CpuModel.
 * Una seccion por objeto malla y material, con la textura de imagen del material.
 * Ignora huesos, modificadores y shape keys: se ve la malla en su forma base.
 */
object BlendLoader {
    fun build(ar: Archive, path: String, progress: (String) -> Unit): CpuModel {
        progress("Leyendo .blend…")
        var raw = ar.read(path)
        if (raw.size > 4 && (raw[0].toInt() and 0xFF) == 0x1F && (raw[1].toInt() and 0xFF) == 0x8B) {
            progress("Descomprimiendo .blend…")
            raw = GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        } else if (raw.size > 4 && (raw[0].toInt() and 0xFF) == 0x28 && (raw[1].toInt() and 0xFF) == 0xB5 &&
            (raw[2].toInt() and 0xFF) == 0x2F && (raw[3].toInt() and 0xFF) == 0xFD
        ) {
            throw IllegalArgumentException(
                "Este .blend esta comprimido con Zstandard (Blender 3.0 o mas nuevo). " +
                    "Guardalo sin comprimir o exportalo a FBX u OBJ desde Blender.",
            )
        }
        val bf = BlendFile(raw)

        // objetos de tipo malla (type == 1) y su dato de malla
        val all = ArrayList<Pair<BlendFile.Block, BlendFile.Block>>()
        val shown = ArrayList<Pair<BlendFile.Block, BlendFile.Block>>()
        for (ob in bf.blocksOf("OB")) {
            val p = ob.pos
            if (bf.i16(p + bf.fo("Object", "type")) != 1) continue
            val me = bf.block(bf.ptr(p + bf.fo("Object", "data"))) ?: continue
            if (me.code != "ME") continue
            all.add(ob to me)
            val hidden = bf.has("Object", "restrictflag") && (bf.u8(p + bf.fo("Object", "restrictflag")) and 1) != 0
            if (!hidden) shown.add(ob to me)
        }
        val use = if (shown.isNotEmpty()) shown else all
        if (use.isEmpty()) throw IllegalStateException("El .blend no tiene objetos de tipo malla.")

        val verts = HashMap<String, CpuVerts>()
        val sections = ArrayList<CpuSection>()
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        val texCache = HashMap<Long, String?>()

        for ((oi, pair) in use.withIndex()) {
            val ob = pair.first
            val me = pair.second
            val mp = me.pos
            val objName = bf.idName(ob.pos)
            progress("Procesando $objName…")

            if (!bf.has("Mesh", "mpoly") || !bf.has("Mesh", "mloop") || !bf.has("Mesh", "mvert")) {
                throw IllegalStateException(
                    "Este .blend guarda las mallas en el formato de Blender 3.6 o mas nuevo, que todavia no se puede leer. " +
                        "Exportalo a FBX u OBJ desde Blender.",
                )
            }
            val totvert = bf.i32(mp + bf.fo("Mesh", "totvert"))
            val totpoly = bf.i32(mp + bf.fo("Mesh", "totpoly"))
            val totloop = bf.i32(mp + bf.fo("Mesh", "totloop"))
            val vBlk = bf.block(bf.ptr(mp + bf.fo("Mesh", "mvert")))
            val pBlk = bf.block(bf.ptr(mp + bf.fo("Mesh", "mpoly")))
            val lBlk = bf.block(bf.ptr(mp + bf.fo("Mesh", "mloop")))
            if (vBlk == null || pBlk == null || lBlk == null || totpoly <= 0) continue

            val vStride = bf.sizeOf("MVert")
            val coOff = bf.fo("MVert", "co")
            val noOff = bf.fo("MVert", "no")
            val pStride = bf.sizeOf("MPoly")
            val psOff = bf.fo("MPoly", "loopstart")
            val ptOff = bf.fo("MPoly", "totloop")
            val pmOff = bf.fo("MPoly", "mat_nr")
            val lStride = bf.sizeOf("MLoop")
            val lvOff = bf.fo("MLoop", "v")

            val nv = minOf(totvert, vBlk.size / vStride)
            val np = minOf(totpoly, pBlk.size / pStride)
            var nl = minOf(totloop, lBlk.size / lStride)

            val uvBlk = uvBlock(bf, mp)
            var uvStride = 0
            var uvOff = 0
            if (uvBlk != null && bf.has("MLoopUV", "uv")) {
                uvStride = bf.sizeOf("MLoopUV")
                uvOff = bf.fo("MLoopUV", "uv")
                nl = minOf(nl, uvBlk.size / uvStride)
            }

            // matriz de objeto (Blender guarda 4x4 por columnas): p' = M * p
            val m = FloatArray(16)
            if (bf.has("Object", "obmat")) {
                val o = ob.pos + bf.fo("Object", "obmat")
                for (k in 0 until 16) m[k] = bf.f32(o + k * 4)
            } else {
                m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
            }

            // un vertice por esquina de poligono: la UV es por esquina
            val data = FloatArray(nl * 8)
            for (j in 0 until nl) {
                var v = bf.i32(lBlk.pos + j * lStride + lvOff)
                if (v < 0 || v >= nv) v = 0
                val vp = vBlk.pos + v * vStride
                val x = bf.f32(vp + coOff)
                val y = bf.f32(vp + coOff + 4)
                val z = bf.f32(vp + coOff + 8)
                val ox = m[0] * x + m[4] * y + m[8] * z + m[12]
                val oy = m[1] * x + m[5] * y + m[9] * z + m[13]
                val oz = m[2] * x + m[6] * y + m[10] * z + m[14]
                // Blender es Z arriba -> visor Y arriba: (x, y, z) -> (x, z, -y)
                data[j * 8] = ox
                data[j * 8 + 1] = oz
                data[j * 8 + 2] = -oy

                val nx = bf.i16(vp + noOff) / 32767f
                val ny = bf.i16(vp + noOff + 2) / 32767f
                val nz = bf.i16(vp + noOff + 4) / 32767f
                val ax = m[0] * nx + m[4] * ny + m[8] * nz
                val ay = m[1] * nx + m[5] * ny + m[9] * nz
                val az = m[2] * nx + m[6] * ny + m[10] * nz
                val l = sqrt(ax * ax + ay * ay + az * az)
                if (l > 0f) {
                    data[j * 8 + 3] = ax / l
                    data[j * 8 + 4] = az / l
                    data[j * 8 + 5] = -ay / l
                } else {
                    data[j * 8 + 4] = 1f
                }
                if (uvStride > 0) {
                    val up = uvBlk!!.pos + j * uvStride + uvOff
                    data[j * 8 + 6] = bf.f32(up)
                    data[j * 8 + 7] = 1f - bf.f32(up + 4)   // Blender: V desde abajo
                }
                for (a in 0 until 3) {
                    val q = data[j * 8 + a]
                    if (q < mn[a]) mn[a] = q
                    if (q > mx[a]) mx[a] = q
                }
            }
            val key = "blend$oi"
            verts[key] = CpuVerts(data, nl)

            // materiales de la malla, en el orden de sus ranuras
            val matPtrs = ArrayList<Long>()
            val totcol = bf.i16(mp + bf.fo("Mesh", "totcol"))
            val matBlk = bf.block(bf.ptr(mp + bf.fo("Mesh", "mat")))
            if (matBlk != null) {
                for (i in 0 until totcol) {
                    if ((i + 1) * bf.ptrSize <= matBlk.size) matPtrs.add(bf.ptr(matBlk.pos + i * bf.ptrSize))
                }
            }
            if (matPtrs.isEmpty()) matPtrs.add(0L)
            val bufs = Array(matPtrs.size) { BIntBuf() }

            // poligonos -> triangulos en abanico, agrupados por ranura de material
            for (i in 0 until np) {
                val pp = pBlk.pos + i * pStride
                val ls = bf.i32(pp + psOff)
                val tl = bf.i32(pp + ptOff)
                if (tl < 3 || ls < 0 || ls + tl > nl) continue
                var slot = bf.i16(pp + pmOff)
                if (slot < 0 || slot >= bufs.size) slot = if (slot < 0) 0 else bufs.size - 1
                val b = bufs[slot]
                for (t in 1 until tl - 1) {
                    b.add(ls)
                    b.add(ls + t)
                    b.add(ls + t + 1)
                }
            }

            for (s in matPtrs.indices) {
                if (bufs[s].n == 0) continue
                val mb = bf.block(matPtrs[s])
                val matName = if (mb != null) bf.idName(mb.pos) else "Material ${s + 1}"
                val label = if (use.size > 1) "$objName · $matName" else matName
                val tex = if (texCache.containsKey(matPtrs[s])) texCache[matPtrs[s]] else texOf(bf, ar, matPtrs[s], matName).also { texCache[matPtrs[s]] = it }
                val si = sections.size
                sections.add(
                    CpuSection(
                        label, key, bufs[s].toArray(),
                        listOf(CpuDraw("$si:$label:0", label, bufs[s].n, 0, emptyList())),
                        emptyList(), tex,
                    ),
                )
            }
        }
        if (sections.isEmpty()) throw IllegalStateException("El .blend no tiene mallas con poligonos.")
        val ini = IniModel("", emptyMap(), emptyMap(), emptyList(), LinkedHashMap())
        return CpuModel(ar, ini, verts, sections, mn, mx)
    }

    /** Datos de UV por esquina: la capa activa (mloopuv) o la primera capa de tipo UV. */
    private fun uvBlock(bf: BlendFile, mp: Int): BlendFile.Block? {
        if (bf.has("Mesh", "mloopuv")) {
            val b = bf.block(bf.ptr(mp + bf.fo("Mesh", "mloopuv")))
            if (b != null) return b
        }
        if (bf.has("Mesh", "ldata") && bf.has("CustomData", "layers") && bf.has("CustomDataLayer", "data")) {
            val ld = mp + bf.fo("Mesh", "ldata")
            val tot = bf.i32(ld + bf.fo("CustomData", "totlayer"))
            val lay = bf.block(bf.ptr(ld + bf.fo("CustomData", "layers")))
            if (lay != null) {
                val lsz = bf.sizeOf("CustomDataLayer")
                for (i in 0 until tot) {
                    val q = lay.pos + i * lsz
                    if (bf.i32(q + bf.fo("CustomDataLayer", "type")) == 16) {
                        val b = bf.block(bf.ptr(q + bf.fo("CustomDataLayer", "data")))
                        if (b != null) return b
                    }
                }
            }
        }
        return null
    }

    private fun isNonColor(name: String): Boolean {
        val n = name.lowercase()
        return listOf("normal", "spec", "rough", "metal", "gloss", "bump", "height", "occlusion", "disp").any { n.contains(it) }
    }

    /** Textura de color del material: nodo Image Texture o, en materiales clasicos, la primera textura de color. */
    private fun texOf(bf: BlendFile, ar: Archive, matPtr: Long, matName: String): String? {
        val mb = bf.block(matPtr) ?: return ar.bestImage(matName)
        val mpos = mb.pos
        var image: BlendFile.Block? = null

        val usesNodes = !bf.has("Material", "use_nodes") || bf.i16(mpos + bf.fo("Material", "use_nodes")) != 0
        if (usesNodes && bf.has("Material", "nodetree")) {
            val nt = bf.block(bf.ptr(mpos + bf.fo("Material", "nodetree")))
            if (nt != null) image = imageFromNodes(bf, nt.pos)
        }
        if (image == null && bf.has("Material", "mtex") && bf.has("MTex", "tex") && bf.has("Tex", "ima")) {
            val base = mpos + bf.fo("Material", "mtex")
            for (i in 0 until 18) {
                val mt = bf.block(bf.ptr(base + i * bf.ptrSize)) ?: continue
                val tex = bf.block(bf.ptr(mt.pos + bf.fo("MTex", "tex"))) ?: continue
                val im = bf.block(bf.ptr(tex.pos + bf.fo("Tex", "ima"))) ?: continue
                if (im.code != "IM") continue
                if (image == null) image = im
                val colorMap = !bf.has("MTex", "mapto") || (bf.i16(mt.pos + bf.fo("MTex", "mapto")) and 1) != 0
                if (colorMap) { image = im; break }
            }
        }
        val ib = image ?: return ar.bestImage(matName)
        return resolveImage(bf, ar, ib) ?: ar.bestImage(matName)
    }

    private fun imageFromNodes(bf: BlendFile, treePos: Int): BlendFile.Block? {
        var n = bf.ptr(treePos + bf.fo("bNodeTree", "nodes"))
        var best: BlendFile.Block? = null
        var guard = 0
        while (n != 0L && guard < 2000) {
            guard++
            val nb = bf.block(n) ?: break
            val np = nb.pos
            val type = bf.i16(np + bf.fo("bNode", "type"))
            val isImg = type == 143 ||
                (bf.has("bNode", "idname") && bf.str(np + bf.fo("bNode", "idname"), 64) == "ShaderNodeTexImage")
            if (isImg) {
                val ib = bf.block(bf.ptr(np + bf.fo("bNode", "id")))
                if (ib != null && ib.code == "IM") {
                    if (best == null) best = ib
                    if (!isNonColor(bf.idName(ib.pos))) return ib
                }
            }
            n = bf.ptr(np + bf.fo("bNode", "next"))
        }
        return best
    }

    /** Ruta de la imagen dentro del zip: empaquetada en el .blend, o por nombre (aproximado) entre los archivos del zip. */
    private fun resolveImage(bf: BlendFile, ar: Archive, im: BlendFile.Block): String? {
        val ip = im.pos
        val idn = bf.idName(ip)
        if (bf.has("Image", "packedfile") && bf.has("PackedFile", "data")) {
            val pf = bf.block(bf.ptr(ip + bf.fo("Image", "packedfile")))
            if (pf != null) {
                val size = bf.i32(pf.pos + bf.fo("PackedFile", "size"))
                val db = bf.block(bf.ptr(pf.pos + bf.fo("PackedFile", "data")))
                if (db != null && size > 0 && size <= db.size) {
                    val vpath = "blend-empaquetado/" + idn.replace('/', '_')
                    ar.addVirtual(vpath, bf.d.copyOfRange(db.pos, db.pos + size))
                    return vpath
                }
            }
        }
        val rawPath = bf.str(ip + bf.fo("Image", "name"), 1024)
        val cleaned = rawPath.replace('\\', '/').removePrefix("//")
        val base = cleaned.substringAfterLast('/')
        return ar.resolve("", cleaned) ?: ar.resolveFuzzy(base) ?: ar.resolveFuzzy(idn)
    }
}
