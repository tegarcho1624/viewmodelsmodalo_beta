package com.alo.xxmiviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Nodo de un FBX binario. Las propiedades son Int/Long/Float/Double/Boolean/String o arrays tipados. */
class FbxNode(val name: String) {
    val props = ArrayList<Any>()
    val kids = ArrayList<FbxNode>()
    fun kid(n: String): FbxNode? = kids.firstOrNull { it.name == n }
    fun str(i: Int): String = (props.getOrNull(i) as? String) ?: ""
}

object Fbx {
    fun parse(d: ByteArray): List<FbxNode> {
        val magic = "Kaydara FBX Binary"
        if (d.size < 30 || String(d, 0, 18, Charsets.ISO_8859_1) != magic) {
            throw IllegalArgumentException("Solo se admite FBX binario (este parece FBX en texto).")
        }
        val r = Reader(d)
        r.big = r.bb.getInt(23) >= 7500
        r.pos = 27
        val top = ArrayList<FbxNode>()
        val hdr = if (r.big) 25 else 13
        while (r.pos + hdr <= d.size) {
            val n = r.node() ?: break
            top.add(n)
        }
        return top
    }

    private class Reader(val d: ByteArray) {
        val bb: ByteBuffer = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        var big = false

        fun node(): FbxNode? {
            val end: Long
            val np: Long
            val nl: Int
            if (big) {
                end = bb.getLong(pos); np = bb.getLong(pos + 8); nl = d[pos + 24].toInt() and 0xFF; pos += 25
            } else {
                end = bb.getInt(pos).toLong() and 0xFFFFFFFFL
                np = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
                nl = d[pos + 12].toInt() and 0xFF; pos += 13
            }
            if (end == 0L) return null
            val n = FbxNode(String(d, pos, nl, Charsets.ISO_8859_1))
            pos += nl
            for (i in 0 until np.toInt()) n.props.add(prop())
            while (pos < end) {
                val k = node() ?: break
                n.kids.add(k)
            }
            pos = end.toInt()
            return n
        }

        fun prop(): Any {
            val t = d[pos].toInt().toChar()
            pos++
            when (t) {
                'Y' -> { val v = bb.getShort(pos); pos += 2; return v.toInt() }
                'C' -> { val v = d[pos].toInt() != 0; pos += 1; return v }
                'I' -> { val v = bb.getInt(pos); pos += 4; return v }
                'F' -> { val v = bb.getFloat(pos); pos += 4; return v }
                'D' -> { val v = bb.getDouble(pos); pos += 8; return v }
                'L' -> { val v = bb.getLong(pos); pos += 8; return v }
                'S' -> {
                    val len = bb.getInt(pos); pos += 4
                    val s = String(d, pos, len, Charsets.UTF_8); pos += len; return s
                }
                'R' -> {
                    val len = bb.getInt(pos); pos += 4
                    val s = d.copyOfRange(pos, pos + len); pos += len; return s
                }
                'f', 'd', 'l', 'i', 'b' -> {
                    val len = bb.getInt(pos)
                    val enc = bb.getInt(pos + 4)
                    val cl = bb.getInt(pos + 8)
                    pos += 12
                    val sz = when (t) { 'f', 'i' -> 4; 'd', 'l' -> 8; else -> 1 }
                    val raw: ByteArray
                    if (enc == 1) {
                        raw = ByteArray(len * sz)
                        val inf = Inflater()
                        inf.setInput(d, pos, cl)
                        var off = 0
                        while (off < raw.size && !inf.finished()) {
                            val c = inf.inflate(raw, off, raw.size - off)
                            if (c == 0 && (inf.needsInput() || inf.needsDictionary())) break
                            off += c
                        }
                        inf.end()
                    } else {
                        raw = d.copyOfRange(pos, pos + len * sz)
                    }
                    pos += cl
                    val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    return when (t) {
                        'f' -> FloatArray(len).also { b.asFloatBuffer().get(it) }
                        'd' -> DoubleArray(len).also { b.asDoubleBuffer().get(it) }
                        'i' -> IntArray(len).also { b.asIntBuffer().get(it) }
                        'l' -> LongArray(len).also { b.asLongBuffer().get(it) }
                        else -> raw
                    }
                }
                else -> throw IllegalArgumentException("Propiedad FBX desconocida: $t")
            }
        }
    }
}

private class IntBuf {
    var a = IntArray(1024)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

/** Convierte un FBX en un CpuModel: un seccion por (malla, material), con su textura difusa. Ignora huesos y blendshapes. */
object FbxLoader {
    private class Conn(val type: String, val child: Long, val parent: Long, val prop: String)

    fun build(ar: Archive, path: String, progress: (String) -> Unit): CpuModel {
        progress("Leyendo FBX…")
        val top = Fbx.parse(ar.read(path))
        val objs = top.firstOrNull { it.name == "Objects" } ?: throw IllegalStateException("El FBX no tiene objetos.")
        val byId = HashMap<Long, FbxNode>()
        for (k in objs.kids) (k.props.getOrNull(0) as? Long)?.let { byId[it] = k }
        val conns = ArrayList<Conn>()
        top.firstOrNull { it.name == "Connections" }?.kids?.forEach { c ->
            val t = c.props.getOrNull(0) as? String
            val a = c.props.getOrNull(1) as? Long
            val b = c.props.getOrNull(2) as? Long
            if (t != null && a != null && b != null) conns.add(Conn(t, a, b, c.str(3)))
        }

        // Eje vertical de la escena
        var up = 1
        var upSign = 1
        val gp = top.firstOrNull { it.name == "GlobalSettings" }?.kid("Properties70")
        gp?.kids?.forEach { p ->
            when (p.str(0)) {
                "UpAxis" -> (p.props.getOrNull(4) as? Int)?.let { up = it }
                "UpAxisSign" -> (p.props.getOrNull(4) as? Int)?.let { upSign = it }
            }
        }

        val verts = HashMap<String, CpuVerts>()
        val sections = ArrayList<CpuSection>()
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)

        val geos = objs.kids.filter { it.name == "Geometry" && it.str(2) == "Mesh" }
        for ((gi, geo) in geos.withIndex()) {
            val gid = geo.props[0] as Long
            val modelConn = conns.firstOrNull { it.type == "OO" && it.child == gid && byId[it.parent]?.name == "Model" }
            val model = modelConn?.let { byId[it.parent] }
            val modelId = modelConn?.parent ?: -1L
            val modelName = model?.str(1)?.substringBefore('\u0000') ?: "Malla $gi"

            // materiales del modelo, en el orden de conexion (es el orden de los indices de material)
            val mats = conns.filter { it.type == "OO" && it.parent == modelId && byId[it.child]?.name == "Material" }
                .mapNotNull { byId[it.child] }
            val texPaths = mats.map { m ->
                val mid = m.props[0] as Long
                val tc = conns.firstOrNull { it.type == "OP" && it.parent == mid && byId[it.child]?.name == "Texture" && it.prop.equals("DiffuseColor", true) }
                    ?: conns.firstOrNull { it.type == "OP" && it.parent == mid && byId[it.child]?.name == "Texture" && it.prop.contains("diffuse", true) }
                val tex = tc?.let { byId[it.child] }
                val fn = tex?.kid("FileName")?.str(0)?.ifEmpty { null } ?: tex?.kid("RelativeFilename")?.str(0)
                if (fn.isNullOrEmpty()) null else ar.resolve("", fn)
            }

            progress("Procesando $modelName…")
            val k = geo.kids.associateBy { it.name }
            val positions = k["Vertices"]?.props?.getOrNull(0) as? DoubleArray ?: continue
            val pvi = k["PolygonVertexIndex"]?.props?.getOrNull(0) as? IntArray ?: continue
            val n = pvi.size

            val cp = IntArray(n) { if (pvi[it] < 0) pvi[it].inv() else pvi[it] }
            val poly = IntArray(n)
            var pi = 0
            for (i in 0 until n) { poly[i] = pi; if (pvi[i] < 0) pi++ }
            val polyCount = if (n > 0 && pvi[n - 1] < 0) pi else pi + 1

            val xf = Xf.of(model, up, upSign)
            val data = FloatArray(n * 8)
            val nrm = Layer.of(k["LayerElementNormal"], "Normals", "NormalsIndex", 3)
            val uv = Layer.of(k["LayerElementUV"], "UV", "UVIndex", 2)
            val p = DoubleArray(3)
            val q = DoubleArray(3)
            for (j in 0 until n) {
                val c = cp[j]
                p[0] = positions[c * 3]; p[1] = positions[c * 3 + 1]; p[2] = positions[c * 3 + 2]
                xf.point(p, q)
                data[j * 8] = q[0].toFloat(); data[j * 8 + 1] = q[1].toFloat(); data[j * 8 + 2] = q[2].toFloat()
                for (a in 0 until 3) {
                    val v = q[a].toFloat()
                    if (v < mn[a]) mn[a] = v
                    if (v > mx[a]) mx[a] = v
                }
                if (nrm != null) {
                    p[0] = nrm.get(j, c, poly[j], 0); p[1] = nrm.get(j, c, poly[j], 1); p[2] = nrm.get(j, c, poly[j], 2)
                    xf.normal(p, q)
                    data[j * 8 + 3] = q[0].toFloat(); data[j * 8 + 4] = q[1].toFloat(); data[j * 8 + 5] = q[2].toFloat()
                } else {
                    data[j * 8 + 4] = 1f
                }
                if (uv != null) {
                    data[j * 8 + 6] = uv.get(j, c, poly[j], 0).toFloat()
                    data[j * 8 + 7] = 1f - uv.get(j, c, poly[j], 1).toFloat()   // FBX: V desde abajo
                }
            }
            val key = "fbx$gi"
            verts[key] = CpuVerts(data, n)

            // material por poligono
            val matLayer = k["LayerElementMaterial"]
            val matMap = matLayer?.kid("MappingInformationType")?.str(0) ?: "AllSame"
            val matArr = matLayer?.kid("Materials")?.props?.getOrNull(0) as? IntArray
            val slots = max(1, mats.size)
            val bufs = Array(slots) { IntBuf() }
            var start = 0
            for (pg in 0 until polyCount) {
                var end = start
                while (end < n - 1 && pvi[end] >= 0) end++
                // el ultimo vertice del poligono es el que tiene indice negativo
                val cnt = end - start + 1
                val slot = when {
                    matArr == null || matArr.isEmpty() -> 0
                    matMap == "ByPolygon" -> matArr.getOrElse(pg) { 0 }
                    else -> matArr[0]
                }.coerceIn(0, slots - 1)
                val b = bufs[slot]
                for (t in 1 until cnt - 1) { b.add(start); b.add(start + t); b.add(start + t + 1) }
                start = end + 1
            }
            for (s in 0 until slots) {
                if (bufs[s].n == 0) continue
                val name = mats.getOrNull(s)?.str(1)?.substringBefore('\u0000') ?: modelName
                val label = if (geos.size > 1) "$modelName · $name" else name
                val si = sections.size
                sections.add(
                    CpuSection(label, key, bufs[s].toArray(), listOf(CpuDraw("$si:$label:0", label, bufs[s].n, 0, emptyList())), emptyList(), texPaths.getOrNull(s)),
                )
            }
        }
        if (sections.isEmpty()) throw IllegalStateException("El FBX no tiene mallas.")
        val ini = IniModel("", emptyMap(), emptyMap(), emptyList(), LinkedHashMap())
        return CpuModel(ar, ini, verts, sections, mn, mx)
    }

    /** Capa de normales/UV con sus modos de mapeo. */
    private class Layer(val mapping: String, val indexed: Boolean, val values: DoubleArray, val index: IntArray?, val comps: Int) {
        fun get(pv: Int, cp: Int, poly: Int, c: Int): Double {
            var i = when (mapping) {
                "ByPolygonVertex" -> pv
                "ByVertice", "ByVertex" -> cp
                "ByPolygon" -> poly
                else -> 0
            }
            if (indexed && index != null) i = index.getOrElse(i) { 0 }
            val o = i * comps + c
            return if (o in values.indices) values[o] else 0.0
        }

        companion object {
            fun of(node: FbxNode?, dataName: String, indexName: String, comps: Int): Layer? {
                if (node == null) return null
                val v = node.kid(dataName)?.props?.getOrNull(0) as? DoubleArray ?: return null
                val mapping = node.kid("MappingInformationType")?.str(0) ?: "ByPolygonVertex"
                val ref = node.kid("ReferenceInformationType")?.str(0) ?: "Direct"
                val idx = node.kid(indexName)?.props?.getOrNull(0) as? IntArray
                return Layer(mapping, ref == "IndexToDirect", v, idx, comps)
            }
        }
    }

    /** Transformacion local del modelo (escala, rotacion Euler XYZ, traslacion) mas el cambio de eje vertical. */
    private class Xf(val t: DoubleArray, val r: DoubleArray, val s: DoubleArray, val zUp: Boolean) {
        fun point(p: DoubleArray, out: DoubleArray) {
            val x = p[0] * s[0]; val y = p[1] * s[1]; val z = p[2] * s[2]
            val a = r[0] * x + r[1] * y + r[2] * z + t[0]
            val b = r[3] * x + r[4] * y + r[5] * z + t[1]
            val c = r[6] * x + r[7] * y + r[8] * z + t[2]
            fix(a, b, c, out)
        }

        fun normal(p: DoubleArray, out: DoubleArray) {
            val x = p[0]; val y = p[1]; val z = p[2]
            val a = r[0] * x + r[1] * y + r[2] * z
            val b = r[3] * x + r[4] * y + r[5] * z
            val c = r[6] * x + r[7] * y + r[8] * z
            fix(a, b, c, out)
        }

        private fun fix(a: Double, b: Double, c: Double, out: DoubleArray) {
            if (zUp) { out[0] = a; out[1] = c; out[2] = -b } else { out[0] = a; out[1] = b; out[2] = c }
        }

        companion object {
            fun of(model: FbxNode?, up: Int, upSign: Int): Xf {
                val t = doubleArrayOf(0.0, 0.0, 0.0)
                val rot = doubleArrayOf(0.0, 0.0, 0.0)
                val s = doubleArrayOf(1.0, 1.0, 1.0)
                model?.kid("Properties70")?.kids?.forEach { p ->
                    val dst = when (p.str(0)) { "Lcl Translation" -> t; "Lcl Rotation" -> rot; "Lcl Scaling" -> s; else -> null }
                    if (dst != null) for (i in 0 until 3) (p.props.getOrNull(4 + i) as? Double)?.let { dst[i] = it }
                }
                val d = Math.PI / 180.0
                val cx = cos(rot[0] * d); val sx = sin(rot[0] * d)
                val cy = cos(rot[1] * d); val sy = sin(rot[1] * d)
                val cz = cos(rot[2] * d); val sz = sin(rot[2] * d)
                // R = Rz * Ry * Rx
                val m = doubleArrayOf(
                    cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                    sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                    -sy, cy * sx, cy * cx,
                )
                return Xf(t, m, s, up == 2)
            }
        }
    }
}
