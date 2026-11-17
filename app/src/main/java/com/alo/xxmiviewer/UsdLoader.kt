package com.alo.xxmiviewer

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private class UIntBuf {
    var a = IntArray(1024)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

/** Convierte un USDC/USDZ en un CpuModel: una seccion por (malla, material). Ignora huesos, animacion y variantes. */
object UsdLoader {
    // ---- matrices 4x4 en convencion de columnas (p' = M * p), fila-mayor: m[fila * 4 + col]
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

    private fun transpose(a: DoubleArray) = DoubleArray(16) { a[(it % 4) * 4 + it / 4] }

    private fun translateM(t: DoubleArray) = ident().also { it[3] = t[0]; it[7] = t[1]; it[11] = t[2] }
    private fun scaleM(s: DoubleArray) = ident().also { it[0] = s[0]; it[5] = s[1]; it[10] = s[2] }

    private fun rot1(axis: Char, deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        val c = cos(a); val s = sin(a)
        val m = ident()
        when (axis) {
            'X' -> { m[5] = c; m[6] = -s; m[9] = s; m[10] = c }
            'Y' -> { m[0] = c; m[2] = s; m[8] = -s; m[10] = c }
            else -> { m[0] = c; m[1] = -s; m[4] = s; m[5] = c }
        }
        return m
    }

    /** "XYZ": se gira primero en X, luego en Y, luego en Z. */
    private fun rotM(order: String, v: DoubleArray): DoubleArray {
        var m = ident()
        for (ch in order) {
            val idx = when (ch) { 'X' -> 0; 'Y' -> 1; else -> 2 }
            m = mul(rot1(ch, v[idx]), m)
        }
        return m
    }

    private fun quatM(q: DoubleArray): DoubleArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val n = sqrt(x * x + y * y + z * z + w * w).let { if (it == 0.0) 1.0 else it }
        val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
        val m = ident()
        m[0] = 1 - 2 * (qy * qy + qz * qz); m[1] = 2 * (qx * qy - qz * qw); m[2] = 2 * (qx * qz + qy * qw)
        m[4] = 2 * (qx * qy + qz * qw); m[5] = 1 - 2 * (qx * qx + qz * qz); m[6] = 2 * (qy * qz - qx * qw)
        m[8] = 2 * (qx * qz - qy * qw); m[9] = 2 * (qy * qz + qx * qw); m[10] = 1 - 2 * (qx * qx + qy * qy)
        return m
    }

    fun build(ar: Archive, path: String, progress: (String) -> Unit): CpuModel {
        progress("Leyendo USD…")
        val bytes = ar.read(path)
        if (bytes.size < 8 || String(bytes, 0, 8, Charsets.ISO_8859_1) != "PXR-USDC") {
            throw IllegalArgumentException("Solo se admite USD binario (.usdc o .usdz); este parece USD en texto.")
        }
        val c = UsdCrate(bytes)
        val specs = c.specs
        val dir = path.substringBeforeLast('/', "")

        fun fld(p: String, name: String, f: String = "default"): Long? = specs["$p.$name"]?.fields?.get(f)
        fun typeName(p: String): String? = c.token(specs[p]?.fields?.get("typeName"))

        val zUp = c.token(specs["/"]?.fields?.get("upAxis")) == "Z"

        // ---------------- transformaciones
        fun localMatrix(p: String): DoubleArray? {
            val order = c.tokenList(fld(p, "xformOpOrder"))
            if (order.isEmpty()) return null
            var m = ident()
            for (op in order) {
                if (op.startsWith("!invert!")) continue
                val r = fld(p, op) ?: continue
                val name = op.removePrefix("xformOp:").substringBefore(':')
                val o: DoubleArray? = when {
                    name == "transform" -> c.matrix4(r)?.let { transpose(it) }
                    name == "translate" -> c.vec3(r)?.let { translateM(it) }
                    name == "scale" -> (c.vec3(r) ?: c.number(r)?.let { doubleArrayOf(it, it, it) })?.let { scaleM(it) }
                    name == "orient" -> c.quat(r)?.let { quatM(it) }
                    name.length == 9 && name.startsWith("rotate") -> c.vec3(r)?.let { rotM(name.substring(6), it) }
                    name.length == 7 && name.startsWith("rotate") -> c.number(r)?.let {
                        val v = doubleArrayOf(it, it, it)
                        rotM(name.substring(6), v)
                    }
                    else -> null
                }
                if (o != null) m = mul(m, o)
            }
            return m
        }

        fun world(p: String): DoubleArray {
            val chain = ArrayList<String>()
            var cur = p
            while (cur.isNotEmpty() && cur != "/") { chain.add(cur); cur = cur.substringBeforeLast('/', "") }
            var m = ident()
            for (q in chain.asReversed()) localMatrix(q)?.let { m = mul(m, it) }
            return m
        }

        // ---------------- materiales y texturas
        fun bindingOf(p: String): String? {
            var cur = p
            while (cur.isNotEmpty() && cur != "/") {
                val t = c.pathList(specs["$cur.material:binding"]?.fields?.get("targetPaths")).firstOrNull()
                if (t != null) return t
                cur = cur.substringBeforeLast('/', "")
            }
            return null
        }

        fun texOf(mat: String?): String? {
            val matName = mat?.substringAfterLast('/') ?: ""
            var file: String? = null
            if (mat != null) {
                val prefix = "$mat/"
                var texPrim: String? = null
                for ((p, s) in specs) {
                    if (p.startsWith(prefix) && (p.endsWith(".inputs:diffuseColor") || p.endsWith(".inputs:baseColor") || p.endsWith(".inputs:albedo"))) {
                        val conn = c.pathList(s.fields["connectionPaths"]).firstOrNull()
                        if (conn != null) { texPrim = conn.substringBefore('.'); break }
                    }
                }
                if (texPrim != null) file = c.token(fld(texPrim, "inputs:file"))
                if (file == null) {
                    for ((p, s) in specs) {
                        if (p.startsWith(prefix) && p.endsWith(".inputs:file")) {
                            file = c.token(s.fields["default"])
                            if (file != null) break
                        }
                    }
                }
            }
            val f = file
            val resolved = if (f != null) ar.resolve(dir, f.removePrefix("./")) else null
            return resolved ?: ar.bestImage(matName)
        }

        // ---------------- mallas
        val verts = HashMap<String, CpuVerts>()
        val sections = ArrayList<CpuSection>()
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f)
        val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)

        val meshes = specs.entries.filter { it.value.type == 6 && typeName(it.key) == "Mesh" }.map { it.key }.sorted()
        for ((mi, mp) in meshes.withIndex()) {
            val name = mp.substringAfterLast('/')
            progress("Procesando $name…")
            val pts = c.floats(fld(mp, "points"), 3) ?: continue
            val fvc = c.ints(fld(mp, "faceVertexCounts")) ?: continue
            val fvi = c.ints(fld(mp, "faceVertexIndices")) ?: continue
            val n = fvi.size
            val nPts = pts.size / 3

            // indice de cara de cada esquina
            val faceOf = IntArray(n)
            var q = 0
            for (f in fvc.indices) for (k in 0 until fvc[f]) { if (q < n) faceOf[q++] = f }

            // normales y UV: segun la cantidad de valores es por vertice, por esquina o por cara
            fun layer(vals: FloatArray?, idx: IntArray?, k: Int): ((Int, Int) -> Float)? {
                if (vals == null) return null
                val cnt = vals.size / k
                return { corner, comp ->
                    val i = when {
                        idx != null -> idx.getOrElse(corner) { 0 }
                        cnt == n -> corner
                        cnt == nPts -> fvi[corner]
                        cnt == fvc.size -> faceOf[corner]
                        else -> 0
                    }
                    val o = i * k + comp
                    if (o in vals.indices) vals[o] else 0f
                }
            }
            val nrmAttr = if (specs.containsKey("$mp.normals")) "normals" else "primvars:normals"
            val nrm = layer(c.floats(fld(mp, nrmAttr), 3), c.ints(fld(mp, "$nrmAttr:indices")) ?: c.ints(fld(mp, "primvars:normals:indices")), 3)
            val stName = listOf("primvars:st", "primvars:st0", "primvars:UVMap", "primvars:uv", "primvars:map1")
                .firstOrNull { specs.containsKey("$mp.$it") }
                ?: specs.keys.filter { it.startsWith("$mp.primvars:") && !it.endsWith(":indices") && !it.contains("normals") }
                    .map { it.substring(mp.length + 1) }.firstOrNull()
            val uv = if (stName != null) layer(c.floats(fld(mp, stName), 2), c.ints(fld(mp, "$stName:indices")), 2) else null

            val w = world(mp)
            val data = FloatArray(n * 8)
            for (j in 0 until n) {
                val pi = fvi[j]
                val x = pts[pi * 3].toDouble(); val y = pts[pi * 3 + 1].toDouble(); val z = pts[pi * 3 + 2].toDouble()
                var ox = w[0] * x + w[1] * y + w[2] * z + w[3]
                var oy = w[4] * x + w[5] * y + w[6] * z + w[7]
                var oz = w[8] * x + w[9] * y + w[10] * z + w[11]
                if (zUp) { val t = oy; oy = oz; oz = -t }
                data[j * 8] = ox.toFloat(); data[j * 8 + 1] = oy.toFloat(); data[j * 8 + 2] = oz.toFloat()
                if (nrm != null) {
                    val nx = nrm(j, 0).toDouble(); val ny = nrm(j, 1).toDouble(); val nz = nrm(j, 2).toDouble()
                    var ax = w[0] * nx + w[1] * ny + w[2] * nz
                    var ay = w[4] * nx + w[5] * ny + w[6] * nz
                    var az = w[8] * nx + w[9] * ny + w[10] * nz
                    if (zUp) { val t = ay; ay = az; az = -t }
                    val l = sqrt(ax * ax + ay * ay + az * az).let { if (it == 0.0) 1.0 else it }
                    data[j * 8 + 3] = (ax / l).toFloat(); data[j * 8 + 4] = (ay / l).toFloat(); data[j * 8 + 5] = (az / l).toFloat()
                }
                if (uv != null) {
                    data[j * 8 + 6] = uv(j, 0)
                    data[j * 8 + 7] = 1f - uv(j, 1)   // USD: V desde abajo
                }
                for (a in 0 until 3) {
                    val v = data[j * 8 + a]
                    if (v < mn[a]) mn[a] = v
                    if (v > mx[a]) mx[a] = v
                }
            }
            if (nrm == null) flatNormals(data, n)
            val key = "usd$mi"
            verts[key] = CpuVerts(data, n)

            // materiales: el de la malla y el de cada GeomSubset
            val meshMat = bindingOf(mp)
            val slotMats = ArrayList<String?>()
            slotMats.add(meshMat)
            val faceSlot = IntArray(fvc.size)
            val prefix = "$mp/"
            for ((sp, ss) in specs) {
                if (!sp.startsWith(prefix) || sp.indexOf('/', prefix.length) >= 0 || ss.type != 6) continue
                if (typeName(sp) != "GeomSubset") continue
                val idx = c.ints(fld(sp, "indices")) ?: continue
                slotMats.add(bindingOf(sp) ?: meshMat)
                for (f in idx) if (f in faceSlot.indices) faceSlot[f] = slotMats.size - 1
            }
            val bufs = Array(slotMats.size) { UIntBuf() }
            var start = 0
            for (f in fvc.indices) {
                val cnt = fvc[f]
                val b = bufs[faceSlot[f]]
                for (t in 1 until cnt - 1) { b.add(start); b.add(start + t); b.add(start + t + 1) }
                start += cnt
            }
            for (s in slotMats.indices) {
                if (bufs[s].n == 0) continue
                val matName = slotMats[s]?.substringAfterLast('/')
                val label = if (slotMats.size > 1 && matName != null) "$name · $matName" else name
                val si = sections.size
                sections.add(
                    CpuSection(label, key, bufs[s].toArray(), listOf(CpuDraw("$si:$label:0", label, bufs[s].n, 0, emptyList())), emptyList(), texOf(slotMats[s])),
                )
            }
        }
        if (sections.isEmpty()) throw IllegalStateException("El USD no tiene mallas con geometria.")
        val ini = IniModel("", emptyMap(), emptyMap(), emptyList(), LinkedHashMap())
        return CpuModel(ar, ini, verts, sections, mn, mx)
    }

    /** Normales por triangulo cuando la malla no trae (las esquinas no estan soldadas, asi que sale plano). */
    private fun flatNormals(d: FloatArray, n: Int) {
        var t = 0
        while (t + 2 < n) {
            val ux = d[(t + 1) * 8] - d[t * 8]; val uy = d[(t + 1) * 8 + 1] - d[t * 8 + 1]; val uz = d[(t + 1) * 8 + 2] - d[t * 8 + 2]
            val vx = d[(t + 2) * 8] - d[t * 8]; val vy = d[(t + 2) * 8 + 1] - d[t * 8 + 1]; val vz = d[(t + 2) * 8 + 2] - d[t * 8 + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
            if (l > 0f) { nx /= l; ny /= l; nz /= l } else { nx = 0f; ny = 1f; nz = 0f }
            for (k in 0 until 3) { d[(t + k) * 8 + 3] = nx; d[(t + k) * 8 + 4] = ny; d[(t + k) * 8 + 5] = nz }
            t += 3
        }
    }
}
