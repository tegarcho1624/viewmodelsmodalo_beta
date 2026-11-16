package com.alo.xxmiviewer

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.StringTokenizer

private class FloatBuf {
    var a = FloatArray(4096)
    var n = 0
    fun add(v: Float) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
}

private class IBuf {
    var a = IntArray(1024)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

/** Lector de OBJ (con MTL opcional). Una parte por material (o por grupo si hay varios grupos). */
object ObjLoader {
    fun build(ar: Archive, path: String, progress: (String) -> Unit): CpuModel {
        progress("Leyendo OBJ…")
        val dir = if (path.contains('/')) path.substringBeforeLast('/') else ""
        val bytes = ar.read(path)

        val pos = FloatBuf()   // x y z
        val tex = FloatBuf()   // u v
        val nor = FloatBuf()   // x y z

        // vertices unicos: clave (v, vt, vn) -> indice
        val out = FloatBuf()   // 8 floats por vertice
        val dedupe = HashMap<Long, Int>()
        var vertCount = 0
        var missingNormals = false

        class Key(val group: String, val mat: String)
        val parts = LinkedHashMap<String, IBuf>()
        val keyInfo = HashMap<String, Key>()

        var group = "default"
        var mat = ""
        val mtlFiles = ArrayList<String>()
        val faceIdx = IntArray(3)
        val cur = ArrayList<Int>()

        fun resolveIdx(i: Int, count: Int): Int = if (i > 0) i - 1 else if (i < 0) count + i else -1

        fun vertex(vi: Int, ti: Int, ni: Int): Int {
            val key = if (vi < 2_000_000 && ti < 2_000_000 && ni < 2_000_000 && vi >= -1 && ti >= -1 && ni >= -1)
                ((vi + 1).toLong() shl 42) or ((ti + 1).toLong() shl 21) or (ni + 1).toLong() else -1L
            if (key >= 0) dedupe[key]?.let { return it }
            out.add(pos.a[vi * 3]); out.add(pos.a[vi * 3 + 1]); out.add(pos.a[vi * 3 + 2])
            if (ni >= 0 && ni * 3 + 2 < nor.n) { out.add(nor.a[ni * 3]); out.add(nor.a[ni * 3 + 1]); out.add(nor.a[ni * 3 + 2]) }
            else { out.add(0f); out.add(0f); out.add(0f); missingNormals = true }
            if (ti >= 0 && ti * 2 + 1 < tex.n) { out.add(tex.a[ti * 2]); out.add(1f - tex.a[ti * 2 + 1]) }   // OBJ: V desde abajo
            else { out.add(0f); out.add(0f) }
            val id = vertCount++
            if (key >= 0) dedupe[key] = id
            return id
        }

        val reader = BufferedReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8), 1 shl 16)
        while (true) {
            val line = (reader.readLine() ?: break).trim()
            if (line.isEmpty() || line[0] == '#') continue
            val st = StringTokenizer(line)
            val cmd = st.nextToken()
            when (cmd) {
                "v" -> { pos.add(st.nextToken().toFloat()); pos.add(st.nextToken().toFloat()); pos.add(st.nextToken().toFloat()) }
                "vt" -> { tex.add(st.nextToken().toFloat()); tex.add(if (st.hasMoreTokens()) st.nextToken().toFloat() else 0f) }
                "vn" -> { nor.add(st.nextToken().toFloat()); nor.add(st.nextToken().toFloat()); nor.add(st.nextToken().toFloat()) }
                "g", "o" -> group = if (st.hasMoreTokens()) line.substring(cmd.length).trim() else "default"
                "usemtl" -> mat = line.substring(cmd.length).trim()
                "mtllib" -> mtlFiles.add(line.substring(cmd.length).trim())
                "f" -> {
                    cur.clear()
                    while (st.hasMoreTokens()) {
                        val tok = st.nextToken()
                        val a = tok.indexOf('/')
                        val vi: Int
                        var ti = -1
                        var ni = -1
                        if (a < 0) {
                            vi = resolveIdx(tok.toInt(), pos.n / 3)
                        } else {
                            vi = resolveIdx(tok.substring(0, a).toInt(), pos.n / 3)
                            val b = tok.indexOf('/', a + 1)
                            val ts = if (b < 0) tok.substring(a + 1) else tok.substring(a + 1, b)
                            if (ts.isNotEmpty()) ti = resolveIdx(ts.toInt(), tex.n / 2)
                            if (b >= 0 && b + 1 < tok.length) ni = resolveIdx(tok.substring(b + 1).toInt(), nor.n / 3)
                        }
                        if (vi < 0 || vi * 3 + 2 >= pos.n) continue
                        cur.add(vertex(vi, ti, ni))
                    }
                    if (cur.size >= 3) {
                        val k = "$group\u0000$mat"
                        val buf = parts.getOrPut(k) { keyInfo[k] = Key(group, mat); IBuf() }
                        for (t in 1 until cur.size - 1) { faceIdx[0] = cur[0]; faceIdx[1] = cur[t]; faceIdx[2] = cur[t + 1]; buf.add(faceIdx[0]); buf.add(faceIdx[1]); buf.add(faceIdx[2]) }
                    }
                }
            }
        }
        if (parts.isEmpty() || vertCount == 0) throw IllegalStateException("El OBJ no tiene caras.")

        val data = out.a.copyOf(out.n)
        if (missingNormals) smoothNormals(data, vertCount, parts.values.map { it.toArray() })

        // limites
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        for (i in 0 until vertCount) for (k in 0 until 3) {
            val v = data[i * 8 + k]
            if (v < mn[k]) mn[k] = v
            if (v > mx[k]) mx[k] = v
        }

        // texturas: MTL (map_Kd) y, si no hay, la mejor imagen del zip
        progress("Buscando texturas…")
        val matTex = HashMap<String, String>()
        for (m in mtlFiles) {
            val p = ar.resolve(dir, m) ?: continue
            var name = ""
            for (raw in String(ar.read(p), Charsets.UTF_8).lines()) {
                val l = raw.trim()
                if (l.startsWith("newmtl ")) name = l.substring(7).trim()
                else if (l.startsWith("map_Kd") && name.isNotEmpty()) {
                    val file = l.substring(6).trim().split(Regex("\\s+")).lastOrNull() ?: continue
                    ar.resolve(dir, file)?.let { matTex[name] = it }
                }
            }
        }
        val images = ar.images()
        val objBase = path.substringAfterLast('/').substringBeforeLast('.').lowercase()

        fun fallbackTex(mat: String): String? {
            if (images.isEmpty()) return null
            if (images.size == 1) return images[0]
            val m = mat.lowercase()
            images.firstOrNull { val b = it.substringAfterLast('/').lowercase(); (m.isNotEmpty() && b.contains(m)) || b.contains(objBase) }?.let { return it }
            val keys = listOf("diffuse", "basecolor", "base_color", "albedo", "color", "col", "diff")
            images.firstOrNull { val b = it.substringAfterLast('/').lowercase(); keys.any { k -> b.contains(k) } }?.let { return it }
            return images[0]
        }

        val groupCount = parts.keys.map { keyInfo.getValue(it).group }.distinct().size
        val matCount = parts.keys.map { keyInfo.getValue(it).mat }.distinct().size
        val sections = ArrayList<CpuSection>()
        for ((k, buf) in parts) {
            val info = keyInfo.getValue(k)
            val label = when {
                groupCount > 1 && matCount > 1 && info.mat.isNotEmpty() -> "${info.group} · ${info.mat}"
                groupCount > 1 -> info.group
                info.mat.isNotEmpty() -> info.mat
                else -> info.group
            }
            val si = sections.size
            val tex = matTex[info.mat] ?: fallbackTex(info.mat)
            sections.add(CpuSection(label, "obj", buf.toArray(), listOf(CpuDraw("$si:$label:0", label, buf.n, 0, emptyList())), emptyList(), tex))
        }
        val ini = IniModel("", emptyMap(), emptyMap(), emptyList(), LinkedHashMap())
        return CpuModel(ar, ini, mapOf("obj" to CpuVerts(data, vertCount)), sections, mn, mx)
    }

    /** Normales suaves por vertice (solo cuando el OBJ no trae vn). */
    private fun smoothNormals(d: FloatArray, n: Int, indexSets: List<IntArray>) {
        for (i in 0 until n) { d[i * 8 + 3] = 0f; d[i * 8 + 4] = 0f; d[i * 8 + 5] = 0f }
        for (idx in indexSets) {
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t]; val b = idx[t + 1]; val c = idx[t + 2]
                val ux = d[b * 8] - d[a * 8]; val uy = d[b * 8 + 1] - d[a * 8 + 1]; val uz = d[b * 8 + 2] - d[a * 8 + 2]
                val vx = d[c * 8] - d[a * 8]; val vy = d[c * 8 + 1] - d[a * 8 + 1]; val vz = d[c * 8 + 2] - d[a * 8 + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (v in intArrayOf(a, b, c)) { d[v * 8 + 3] += nx; d[v * 8 + 4] += ny; d[v * 8 + 5] += nz }
                t += 3
            }
        }
        for (i in 0 until n) {
            val x = d[i * 8 + 3]; val y = d[i * 8 + 4]; val z = d[i * 8 + 5]
            val l = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (l > 0f) { d[i * 8 + 3] = x / l; d[i * 8 + 4] = y / l; d[i * 8 + 5] = z / l } else d[i * 8 + 4] = 1f
        }
    }
}
