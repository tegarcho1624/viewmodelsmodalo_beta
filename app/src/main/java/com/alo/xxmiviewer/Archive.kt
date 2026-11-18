package com.alo.xxmiviewer

import java.io.File
import java.util.zip.ZipFile

/**
 * Zip del mod con acceso aleatorio. Las rutas se normalizan a '/' y las busquedas ignoran mayusculas.
 * Los zips anidados (zip dentro de zip) se abren tambien; sus rutas llevan el prefijo "dentro.zip!/".
 */
class Archive(file: File, private val tmpDir: File) {
    private class Src(val zip: ZipFile, val raw: String)

    private val zips = ArrayList<ZipFile>()
    private val tmpFiles = ArrayList<File>()
    private val srcByLower = HashMap<String, Src>()      // ruta en minusculas -> origen
    private val pathByLower = HashMap<String, String>()  // ruta en minusculas -> ruta normalizada
    private val byBase = HashMap<String, String>()       // nombre de archivo en minusculas -> ruta normalizada
    private val mem = HashMap<String, ByteArray>()       // archivos virtuales (p. ej. imagenes empaquetadas dentro de un .blend)
    val paths = ArrayList<String>()

    init {
        addZip(file, "", 0)
    }

    private fun addZip(f: File, prefix: String, depth: Int) {
        val zip = ZipFile(f)
        zips.add(zip)
        val nested = ArrayList<Pair<String, String>>()   // (ruta normalizada, nombre real)
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val p = prefix + e.name.replace('\\', '/').trimStart('/')
            val l = p.lowercase()
            if (l.contains("__macosx/") || p.substringAfterLast('/').startsWith("._")) continue
            srcByLower[l] = Src(zip, e.name)
            pathByLower[l] = p
            paths.add(p)
            byBase.putIfAbsent(l.substringAfterLast('/'), p)
            if (l.endsWith(".zip") || l.endsWith(".usdz")) nested.add(p to e.name)
        }
        if (depth >= 8) return
        for ((p, raw) in nested) {
            try {
                val entry = zip.getEntry(raw) ?: continue
                val tmp = File.createTempFile("nested_", ".zip", tmpDir)
                tmpFiles.add(tmp)
                zip.getInputStream(entry).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                addZip(tmp, "$p!/", depth + 1)
            } catch (_: Exception) {
                // zip anidado danado: se ignora
            }
        }
    }

    fun read(path: String): ByteArray {
        mem[path.lowercase()]?.let { return it }
        val src = srcByLower[path.lowercase()] ?: throw IllegalStateException("No existe $path")
        val entry = src.zip.getEntry(src.raw) ?: throw IllegalStateException("No existe $path")
        return src.zip.getInputStream(entry).use { it.readBytes() }
    }

    /** Resuelve un nombre relativo a la carpeta [dir]; si no esta, busca por nombre de archivo. */
    fun resolve(dir: String, name: String): String? {
        val parts = (if (dir.isEmpty()) name else "$dir/$name").replace('\\', '/').split('/')
        val out = ArrayList<String>()
        for (p in parts) {
            when (p) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(p)
            }
        }
        val full = out.joinToString("/")
        pathByLower[full.lowercase()]?.let { return it }
        return byBase[name.replace('\\', '/').substringAfterLast('/').lowercase()]
    }

    /** Agrega un archivo que no esta en el zip (por ejemplo una imagen empaquetada en un .blend). */
    fun addVirtual(path: String, bytes: ByteArray) {
        val l = path.lowercase()
        mem[l] = bytes
        if (!pathByLower.containsKey(l)) {
            pathByLower[l] = path
            paths.add(path)
            byBase.putIfAbsent(l.substringAfterLast('/'), path)
        }
    }

    private fun norm(s: String, keepExt: Boolean): String {
        var b = s.replace('\\', '/').substringAfterLast('/')
        if (!keepExt) b = b.substringBeforeLast('.')
        val sb = StringBuilder()
        for (ch in b.lowercase()) if (ch.isLetterOrDigit()) sb.append(ch)
        return sb.toString()
    }

    /** Busca una imagen por nombre ignorando mayusculas, espacios, guiones y guiones bajos ("Type 0 Texture.png" = "Type_0_Texture.png"). */
    fun resolveFuzzy(name: String): String? {
        if (name.isEmpty()) return null
        val imgs = images()
        val n = norm(name, true)
        imgs.firstOrNull { norm(it, true) == n }?.let { return it }
        val ne = norm(name, false)
        if (ne.isEmpty()) return null
        return imgs.firstOrNull { norm(it, false) == ne }
    }

    /** Imagenes del archivo (posibles texturas). */
    fun images(): List<String> = paths.filter {
        val l = it.lowercase()
        l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".dds") ||
            l.endsWith(".bmp") || l.endsWith(".webp")
    }

    /** Mejor imagen del archivo para usar de textura; [hint] es un nombre de material o de objeto. */
    fun bestImage(hint: String): String? {
        val imgs = images()
        if (imgs.isEmpty()) return null
        if (imgs.size == 1) return imgs[0]
        val h = hint.lowercase()
        if (h.isNotEmpty()) imgs.firstOrNull { it.lowercase().contains(h) }?.let { return it }
        val keys = listOf("basecolor", "base_color", "albedo", "diffuse", "color")
        imgs.firstOrNull { p -> keys.any { p.lowercase().contains(it) } }?.let { return it }
        return imgs[0]
    }

    fun close() {
        for (z in zips) try { z.close() } catch (_: Exception) {}
        zips.clear()
        for (t in tmpFiles) try { t.delete() } catch (_: Exception) {}
        tmpFiles.clear()
    }
}
