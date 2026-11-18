package com.alo.xxmiviewer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// ---------------------------------------------------------------- utilidades JSON

@Suppress("UNCHECKED_CAST")
private fun gMap(o: Any?): Map<String, Any?>? = o as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun gList(o: Any?): List<Any?>? = o as? List<Any?>

private fun gInt(o: Any?, def: Int): Int = (o as? Double)?.toInt() ?: def

private fun gStr(o: Any?): String? {
    val s = o as? String
    return if (s.isNullOrEmpty()) null else s
}

/** Lector JSON minimo (no hay org.json fuera de Android, y asi el mismo codigo se puede probar en el servidor). */
private class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? = value()

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == '﻿')) i++
    }

    private fun value(): Any? {
        ws()
        if (i >= s.length) throw IllegalArgumentException("JSON incompleto")
        val c = s[i]
        if (c == '{') return obj()
        if (c == '[') return arr()
        if (c == '"') return str()
        if (s.startsWith("true", i)) { i += 4; return true }
        if (s.startsWith("false", i)) { i += 5; return false }
        if (s.startsWith("null", i)) { i += 4; return null }
        return num()
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            if (s[i] != ':') throw IllegalArgumentException("JSON invalido")
            i++
            m[k] = value()
            ws()
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; break }
            throw IllegalArgumentException("JSON invalido")
        }
        return m
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            ws()
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; break }
            throw IllegalArgumentException("JSON invalido")
        }
        return l
    }

    private fun str(): String {
        if (s[i] != '"') throw IllegalArgumentException("JSON invalido")
        i++
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            if (c == '"') break
            if (c == '\\') {
                val e = s[i++]
                if (e == 'n') sb.append('\n')
                else if (e == 't') sb.append('\t')
                else if (e == 'r') sb.append('\r')
                else if (e == 'b') sb.append('\b')
                else if (e == 'f') sb.append('\u000C')
                else if (e == 'u') { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else sb.append(e)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
        if (st == i) throw IllegalArgumentException("JSON invalido")
        return s.substring(st, i).toDouble()
    }
}

private class GIntBuf {
    var a = IntArray(1024)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

// ---------------------------------------------------------------- accesores

/** Acceso a los accessors / bufferViews de un glTF, con todos los tipos de componente. */
private class GltfData(
    private val accessors: List<Any?>,
    private val views: List<Any?>,
    private val bufs: List<ByteBuffer?>,
) {
    class Acc(val comps: Int, val count: Int, val bb: ByteBuffer?, val base: Int, val stride: Int, val ctype: Int, val normalized: Boolean)

    private fun compSize(t: Int): Int = when (t) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        else -> 4
    }

    private fun readComp(bb: ByteBuffer, pos: Int, t: Int): Double = when (t) {
        5120 -> bb.get(pos).toDouble()
        5121 -> (bb.get(pos).toInt() and 0xFF).toDouble()
        5122 -> bb.getShort(pos).toDouble()
        5123 -> (bb.getShort(pos).toInt() and 0xFFFF).toDouble()
        5125 -> (bb.getInt(pos).toLong() and 0xFFFFFFFFL).toDouble()
        else -> bb.getFloat(pos).toDouble()
    }

    private fun normalize(v: Double, t: Int): Double = when (t) {
        5120 -> maxOf(v / 127.0, -1.0)
        5121 -> v / 255.0
        5122 -> maxOf(v / 32767.0, -1.0)
        5123 -> v / 65535.0
        else -> v
    }

    fun open(i: Int): Acc? {
        val a = gMap(accessors.getOrNull(i)) ?: return null
        val ctype = gInt(a["componentType"], 5126)
        val count = gInt(a["count"], 0)
        val comps = when (a["type"] as? String) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            "MAT2" -> 4
            "MAT3" -> 9
            "MAT4" -> 16
            else -> 1
        }
        var bb: ByteBuffer? = null
        var base = 0
        var stride = comps * compSize(ctype)
        val bvi = gInt(a["bufferView"], -1)
        if (bvi >= 0) {
            val v = gMap(views.getOrNull(bvi))
            if (v != null) {
                bb = bufs.getOrNull(gInt(v["buffer"], 0))
                base = gInt(v["byteOffset"], 0) + gInt(a["byteOffset"], 0)
                val bs = gInt(v["byteStride"], 0)
                if (bs > 0) stride = bs
            }
        }
        return Acc(comps, count, bb, base, stride, ctype, a["normalized"] == true)
    }

    fun floats(i: Int): FloatArray? {
        val a = open(i) ?: return null
        val out = FloatArray(a.count * a.comps)
        val bb = a.bb ?: return out
        val cs = compSize(a.ctype)
        for (e in 0 until a.count) {
            val o = a.base + e * a.stride
            for (c in 0 until a.comps) {
                val pos = o + c * cs
                if (pos + cs > bb.limit()) return out
                var v = readComp(bb, pos, a.ctype)
                if (a.normalized) v = normalize(v, a.ctype)
                out[e * a.comps + c] = v.toFloat()
            }
        }
        return out
    }

    fun ints(i: Int): IntArray? {
        val a = open(i) ?: return null
        val out = IntArray(a.count * a.comps)
        val bb = a.bb ?: return out
        val cs = compSize(a.ctype)
        for (e in 0 until a.count) {
            val o = a.base + e * a.stride
            for (c in 0 until a.comps) {
                val pos = o + c * cs
                if (pos + cs > bb.limit()) return out
                out[e * a.comps + c] = readComp(bb, pos, a.ctype).toLong().toInt()
            }
        }
        return out
    }
}

// ---------------------------------------------------------------- cargador

/**
 * Convierte un glTF 2.0 (.gltf con .bin externo o embebido, o .glb) en un CpuModel.
 * Una seccion por primitiva. Aplica la jerarquia de nodos y el skinning (pose de reposo de los nodos);
 * ignora animaciones, morph targets, color de material sin textura y la transparencia parcial.
 */
object GltfLoader {
    private fun ident() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(16)
        for (r in 0 until 4) for (c in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[r * 4 + k] * b[k * 4 + c]
            o[r * 4 + c] = s
        }
        return o
    }

    /** glTF guarda las matrices por columnas; aqui se usan por filas (p' = M * p). */
    private fun fromColumns(a: FloatArray, off: Int): DoubleArray {
        val m = DoubleArray(16)
        for (r in 0 until 4) for (c in 0 until 4) m[r * 4 + c] = a[off + c * 4 + r].toDouble()
        return m
    }

    private fun localMatrix(n: Map<String, Any?>?): DoubleArray {
        if (n == null) return ident()
        val mat = gList(n["matrix"])
        if (mat != null && mat.size == 16) {
            val f = FloatArray(16) { ((mat[it] as? Double) ?: 0.0).toFloat() }
            return fromColumns(f, 0)
        }
        val t = gList(n["translation"])
        val r = gList(n["rotation"])
        val s = gList(n["scale"])
        fun num(l: List<Any?>?, k: Int, def: Double): Double = if (l != null && l.size > k) ((l[k] as? Double) ?: def) else def
        val m = ident()
        val x = num(r, 0, 0.0); val y = num(r, 1, 0.0); val z = num(r, 2, 0.0); val w = num(r, 3, 1.0)
        val sx = num(s, 0, 1.0); val sy = num(s, 1, 1.0); val sz = num(s, 2, 1.0)
        m[0] = (1 - 2 * (y * y + z * z)) * sx; m[1] = 2 * (x * y - z * w) * sy; m[2] = 2 * (x * z + y * w) * sz
        m[4] = 2 * (x * y + z * w) * sx; m[5] = (1 - 2 * (x * x + z * z)) * sy; m[6] = 2 * (y * z - x * w) * sz
        m[8] = 2 * (x * z - y * w) * sx; m[9] = 2 * (y * z + x * w) * sy; m[10] = (1 - 2 * (x * x + y * y)) * sz
        m[3] = num(t, 0, 0.0); m[7] = num(t, 1, 0.0); m[11] = num(t, 2, 0.0)
        return m
    }

    private fun b64(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in s) {
            val v: Int = if (ch in 'A'..'Z') ch - 'A'
            else if (ch in 'a'..'z') ch - 'a' + 26
            else if (ch in '0'..'9') ch - '0' + 52
            else if (ch == '+' || ch == '-') 62
            else if (ch == '/' || ch == '_') 63
            else -1
            if (v < 0) continue
            acc = ((acc shl 6) or v) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Quita los %XX de una ruta (los .gltf codifican los espacios como %20). */
    private fun unescape(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { out.write(h); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun triangulate(idx: IntArray, mode: Int, vc: Int): IntArray {
        val b = GIntBuf()
        fun tri(a: Int, c: Int, d: Int) {
            if (a in 0 until vc && c in 0 until vc && d in 0 until vc) { b.add(a); b.add(c); b.add(d) }
        }
        if (mode == 4) {
            var t = 0
            while (t + 2 < idx.size) { tri(idx[t], idx[t + 1], idx[t + 2]); t += 3 }
        } else if (mode == 5) {
            for (t in 0 until idx.size - 2) {
                if (t % 2 == 0) tri(idx[t], idx[t + 1], idx[t + 2]) else tri(idx[t + 1], idx[t], idx[t + 2])
            }
        } else {
            for (t in 1 until idx.size - 1) tri(idx[0], idx[t], idx[t + 1])
        }
        return b.toArray()
    }

    private fun smoothNormals(d: FloatArray, vc: Int, tri: IntArray) {
        var t = 0
        while (t + 2 < tri.size) {
            val a = tri[t]; val b = tri[t + 1]; val c = tri[t + 2]
            val ux = d[b * 8] - d[a * 8]; val uy = d[b * 8 + 1] - d[a * 8 + 1]; val uz = d[b * 8 + 2] - d[a * 8 + 2]
            val vx = d[c * 8] - d[a * 8]; val vy = d[c * 8 + 1] - d[a * 8 + 1]; val vz = d[c * 8 + 2] - d[a * 8 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { d[v * 8 + 3] += nx; d[v * 8 + 4] += ny; d[v * 8 + 5] += nz }
            t += 3
        }
        for (i in 0 until vc) {
            val x = d[i * 8 + 3]; val y = d[i * 8 + 4]; val z = d[i * 8 + 5]
            val l = sqrt(x * x + y * y + z * z)
            if (l > 0f) { d[i * 8 + 3] = x / l; d[i * 8 + 4] = y / l; d[i * 8 + 5] = z / l } else d[i * 8 + 4] = 1f
        }
    }

    fun build(ar: Archive, path: String, progress: (String) -> Unit): CpuModel {
        progress("Leyendo glTF…")
        val dir = path.substringBeforeLast('/', "")
        val bytes = ar.read(path)

        // .glb: cabecera "glTF" y bloques JSON + BIN. .gltf: JSON plano.
        var glbBin: ByteArray? = null
        val jsonText: String
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "glTF") {
            val hb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var p = 12
            var js: String? = null
            while (p + 8 <= bytes.size) {
                val len = hb.getInt(p)
                val type = hb.getInt(p + 4)
                p += 8
                if (len < 0 || p + len > bytes.size) break
                if (type == 0x4E4F534A) js = String(bytes, p, len, Charsets.UTF_8)
                else if (type == 0x004E4942 && glbBin == null) glbBin = bytes.copyOfRange(p, p + len)
                p += len
            }
            jsonText = js ?: throw IllegalArgumentException("El .glb no tiene la parte JSON.")
        } else {
            jsonText = String(bytes, Charsets.UTF_8)
        }
        val g = gMap(MiniJson(jsonText).parse()) ?: throw IllegalArgumentException("El glTF no es un JSON valido.")

        for (e in gList(g["extensionsRequired"]) ?: emptyList()) {
            val n = e as? String
            if (n == "KHR_draco_mesh_compression" || n == "EXT_meshopt_compression") {
                throw IllegalArgumentException("Este glTF usa compresion ($n), que todavia no se puede leer. Exportalo sin comprimir.")
            }
        }

        // buffers
        val bufs = ArrayList<ByteBuffer?>()
        for (bo in gList(g["buffers"]) ?: emptyList()) {
            val b = gMap(bo)
            val uri = gStr(b?.get("uri"))
            val data: ByteArray? = if (uri == null) glbBin
            else if (uri.startsWith("data:")) b64(uri.substringAfter(','))
            else ar.resolve(dir, unescape(uri))?.let { ar.read(it) }
            bufs.add(data?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) })
        }
        val views = gList(g["bufferViews"]) ?: emptyList()
        val data = GltfData(gList(g["accessors"]) ?: emptyList(), views, bufs)

        // nodos: matriz local, padre y matriz de mundo
        val nodes = gList(g["nodes"]) ?: emptyList()
        val n = nodes.size
        val local = Array(n) { localMatrix(gMap(nodes[it])) }
        val parent = IntArray(n) { -1 }
        for (i in 0 until n) {
            for (c in gList(gMap(nodes[i])?.get("children")) ?: emptyList()) {
                val ci = gInt(c, -1)
                if (ci in 0 until n) parent[ci] = i
            }
        }
        val world = arrayOfNulls<DoubleArray>(n)
        fun worldOf(i: Int, depth: Int): DoubleArray {
            val cached = world[i]
            if (cached != null) return cached
            val p = parent[i]
            val w = if (p < 0 || depth > 512) local[i] else mul(worldOf(p, depth + 1), local[i])
            world[i] = w
            return w
        }

        // nodos de la escena por defecto
        val reach = BooleanArray(n)
        val scenes = gList(g["scenes"]) ?: emptyList()
        val roots = ArrayList<Int>()
        if (scenes.isNotEmpty()) {
            val sc = gMap(scenes.getOrNull(gInt(g["scene"], 0))) ?: gMap(scenes[0])
            for (r in gList(sc?.get("nodes")) ?: emptyList()) roots.add(gInt(r, -1))
        } else {
            for (i in 0 until n) if (parent[i] < 0) roots.add(i)
        }
        val stack = ArrayList<Int>()
        for (r in roots) if (r in 0 until n) stack.add(r)
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            if (reach[i]) continue
            reach[i] = true
            for (c in gList(gMap(nodes[i])?.get("children")) ?: emptyList()) {
                val ci = gInt(c, -1)
                if (ci in 0 until n) stack.add(ci)
            }
        }

        // matrices de skinning: mundo del hueso * inversa de bind
        val skins = gList(g["skins"]) ?: emptyList()
        val skinCache = HashMap<Int, Array<DoubleArray>>()
        fun skinMatrices(si: Int): Array<DoubleArray>? {
            val c = skinCache[si]
            if (c != null) return c
            val sk = gMap(skins.getOrNull(si)) ?: return null
            val joints = gList(sk["joints"]) ?: return null
            val ibmI = gInt(sk["inverseBindMatrices"], -1)
            val ibm: FloatArray? = if (ibmI >= 0) data.floats(ibmI) else null
            val out = Array(joints.size) { k ->
                val jn = gInt(joints[k], -1)
                val jw = if (jn in 0 until n) worldOf(jn, 0) else ident()
                val ib = if (ibm != null && (k + 1) * 16 <= ibm.size) fromColumns(ibm, k * 16) else ident()
                mul(jw, ib)
            }
            skinCache[si] = out
            return out
        }

        // materiales y texturas
        val mats = gList(g["materials"]) ?: emptyList()
        val textures = gList(g["textures"]) ?: emptyList()
        val images = gList(g["images"]) ?: emptyList()
        val imgCache = HashMap<Int, String?>()

        fun loadImage(src: Int): String? {
            val im = gMap(images.getOrNull(src)) ?: return null
            val uri = gStr(im["uri"])
            val mime = gStr(im["mimeType"]) ?: ""
            val ext = if (mime.contains("jpeg")) "jpg" else if (mime.contains("webp")) "webp" else "png"
            if (uri == null) {
                val v = gMap(views.getOrNull(gInt(im["bufferView"], -1))) ?: return null
                val bb = bufs.getOrNull(gInt(v["buffer"], 0)) ?: return null
                val off = gInt(v["byteOffset"], 0)
                val len = gInt(v["byteLength"], 0)
                if (len <= 0 || off < 0 || off + len > bb.limit()) return null
                val arr = ByteArray(len)
                val dup = bb.duplicate()
                dup.position(off)
                dup.get(arr)
                val vp = "gltf-embebido/imagen$src.$ext"
                ar.addVirtual(vp, arr)
                return vp
            }
            if (uri.startsWith("data:")) {
                val vp = "gltf-embebido/imagen$src.$ext"
                ar.addVirtual(vp, b64(uri.substringAfter(',')))
                return vp
            }
            val clean = unescape(uri)
            return ar.resolve(dir, clean) ?: ar.resolveFuzzy(clean.substringAfterLast('/'))
        }

        fun imagePath(texI: Int): String? {
            if (texI < 0) return null
            val tex = gMap(textures.getOrNull(texI)) ?: return null
            var src = gInt(tex["source"], -1)
            if (src < 0) {
                val ex = gMap(tex["extensions"])
                if (ex != null) {
                    for (e in ex.values) {
                        val s = gInt(gMap(e)?.get("source"), -1)
                        if (s >= 0) { src = s; break }
                    }
                }
            }
            if (src < 0) return null
            if (imgCache.containsKey(src)) return imgCache[src]
            val r = loadImage(src)
            imgCache[src] = r
            return r
        }

        fun textureIndex(mj: Map<String, Any?>?): Int {
            if (mj == null) return -1
            val pbr = gMap(mj["pbrMetallicRoughness"])
            var t = gInt(gMap(pbr?.get("baseColorTexture"))?.get("index"), -1)
            if (t < 0) {
                val sg = gMap(gMap(mj["extensions"])?.get("KHR_materials_pbrSpecularGlossiness"))
                t = gInt(gMap(sg?.get("diffuseTexture"))?.get("index"), -1)
            }
            return t
        }

        // mallas
        val meshes = gList(g["meshes"]) ?: emptyList()
        val verts = HashMap<String, CpuVerts>()
        val sections = ArrayList<CpuSection>()
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)

        for (ni in 0 until n) {
            if (!reach[ni]) continue
            val nd = gMap(nodes[ni]) ?: continue
            val mi = gInt(nd["mesh"], -1)
            if (mi < 0) continue
            val mesh = gMap(meshes.getOrNull(mi)) ?: continue
            val prims = gList(mesh["primitives"]) ?: continue
            val meshName = gStr(mesh["name"]) ?: gStr(nd["name"]) ?: "Malla $mi"
            progress("Procesando $meshName…")
            val skinIdx = gInt(nd["skin"], -1)
            val sm: Array<DoubleArray>? = if (skinIdx >= 0) skinMatrices(skinIdx) else null
            val nodeW = worldOf(ni, 0)

            for (po in prims) {
                val prim = gMap(po) ?: continue
                if (gMap(prim["extensions"])?.containsKey("KHR_draco_mesh_compression") == true) {
                    throw IllegalArgumentException("Este glTF usa compresion Draco, que todavia no se puede leer. Exportalo sin comprimir.")
                }
                val mode = gInt(prim["mode"], 4)
                if (mode != 4 && mode != 5 && mode != 6) continue
                val attrs = gMap(prim["attributes"]) ?: continue
                val posI = gInt(attrs["POSITION"], -1)
                if (posI < 0) continue
                val pos = data.floats(posI) ?: continue
                val vc = pos.size / 3
                if (vc == 0) continue
                val nI = gInt(attrs["NORMAL"], -1)
                val nrm: FloatArray? = if (nI >= 0) data.floats(nI) else null
                val uI = gInt(attrs["TEXCOORD_0"], -1)
                val uv: FloatArray? = if (uI >= 0) data.floats(uI) else null
                val iI = gInt(prim["indices"], -1)
                val raw: IntArray = if (iI >= 0) (data.ints(iI) ?: IntArray(0)) else IntArray(vc) { it }
                val tri = triangulate(raw, mode, vc)
                if (tri.isEmpty()) continue

                val jI = gInt(attrs["JOINTS_0"], -1)
                val wI = gInt(attrs["WEIGHTS_0"], -1)
                val jnt: IntArray? = if (sm != null && jI >= 0) data.ints(jI) else null
                val wgt: FloatArray? = if (sm != null && wI >= 0) data.floats(wI) else null
                val skinned = sm != null && jnt != null && wgt != null && jnt.size >= vc * 4 && wgt.size >= vc * 4

                val out = FloatArray(vc * 8)
                for (v in 0 until vc) {
                    val x = pos[v * 3].toDouble()
                    val y = pos[v * 3 + 1].toDouble()
                    val z = pos[v * 3 + 2].toDouble()
                    val hasN = nrm != null && nrm.size >= (v + 1) * 3
                    val nx = if (hasN) nrm!![v * 3].toDouble() else 0.0
                    val ny = if (hasN) nrm!![v * 3 + 1].toDouble() else 0.0
                    val nz = if (hasN) nrm!![v * 3 + 2].toDouble() else 0.0
                    var ox = 0.0; var oy = 0.0; var oz = 0.0
                    var px = 0.0; var py = 0.0; var pz = 0.0
                    var tot = 0.0
                    if (skinned) {
                        for (c in 0 until 4) {
                            val wt = wgt!![v * 4 + c].toDouble()
                            if (wt == 0.0) continue
                            val j = jnt!![v * 4 + c]
                            if (j < 0 || j >= sm!!.size) continue
                            val m = sm[j]
                            ox += wt * (m[0] * x + m[1] * y + m[2] * z + m[3])
                            oy += wt * (m[4] * x + m[5] * y + m[6] * z + m[7])
                            oz += wt * (m[8] * x + m[9] * y + m[10] * z + m[11])
                            px += wt * (m[0] * nx + m[1] * ny + m[2] * nz)
                            py += wt * (m[4] * nx + m[5] * ny + m[6] * nz)
                            pz += wt * (m[8] * nx + m[9] * ny + m[10] * nz)
                            tot += wt
                        }
                    }
                    if (!skinned || tot < 1e-6) {
                        // sin huesos: transformacion del nodo (en mallas con skin el nodo se ignora, segun el estandar)
                        val w = if (skinned) ident() else nodeW
                        ox = w[0] * x + w[1] * y + w[2] * z + w[3]
                        oy = w[4] * x + w[5] * y + w[6] * z + w[7]
                        oz = w[8] * x + w[9] * y + w[10] * z + w[11]
                        px = w[0] * nx + w[1] * ny + w[2] * nz
                        py = w[4] * nx + w[5] * ny + w[6] * nz
                        pz = w[8] * nx + w[9] * ny + w[10] * nz
                    }
                    out[v * 8] = ox.toFloat(); out[v * 8 + 1] = oy.toFloat(); out[v * 8 + 2] = oz.toFloat()
                    val l = sqrt(px * px + py * py + pz * pz)
                    if (l > 0.0) {
                        out[v * 8 + 3] = (px / l).toFloat(); out[v * 8 + 4] = (py / l).toFloat(); out[v * 8 + 5] = (pz / l).toFloat()
                    }
                    if (uv != null && uv.size >= (v + 1) * 2) {
                        out[v * 8 + 6] = uv[v * 2]
                        out[v * 8 + 7] = uv[v * 2 + 1]   // glTF: V desde arriba, igual que las imagenes
                    }
                    for (a in 0 until 3) {
                        val q = out[v * 8 + a]
                        if (q < mn[a]) mn[a] = q
                        if (q > mx[a]) mx[a] = q
                    }
                }
                if (nrm == null) smoothNormals(out, vc, tri)

                val matIdx = gInt(prim["material"], -1)
                val mj = gMap(mats.getOrNull(matIdx))
                val matName = gStr(mj?.get("name"))
                val label = if (matName != null) "$meshName · $matName" else meshName
                val am = mj?.get("alphaMode") as? String
                val alphaCut = am == "MASK" || am == "BLEND"
                val tex = imagePath(textureIndex(mj))

                val key = "gltf${verts.size}"
                verts[key] = CpuVerts(out, vc)
                val si = sections.size
                sections.add(
                    CpuSection(
                        label, key, tri,
                        listOf(CpuDraw("$si:$label:0", label, tri.size, 0, emptyList())),
                        emptyList(), tex, alphaCut,
                    ),
                )
            }
        }
        if (sections.isEmpty()) throw IllegalStateException("El glTF no tiene mallas de triangulos.")
        val ini = IniModel("", emptyMap(), emptyMap(), emptyList(), LinkedHashMap())
        return CpuModel(ar, ini, verts, sections, mn, mx)
    }
}
