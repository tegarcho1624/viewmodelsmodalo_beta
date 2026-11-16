package com.alo.xxmiviewer

import java.io.File
import java.util.zip.ZipFile

/** Zip del mod con acceso aleatorio. Las rutas se normalizan a '/' y las busquedas ignoran mayusculas. */
class Archive(file: File) {
    private val zip = ZipFile(file)
    private val rawByLower = HashMap<String, String>()   // ruta normalizada en minusculas -> nombre real en el zip
    private val pathByLower = HashMap<String, String>()  // ruta normalizada en minusculas -> ruta normalizada
    private val byBase = HashMap<String, String>()       // nombre de archivo en minusculas -> ruta normalizada
    val paths = ArrayList<String>()

    init {
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            if (e.isDirectory) continue
            val p = e.name.replace('\\', '/').trimStart('/')
            val l = p.lowercase()
            if (l.contains("__macosx/") || p.substringAfterLast('/').startsWith("._")) continue
            rawByLower[l] = e.name
            pathByLower[l] = p
            paths.add(p)
            byBase.putIfAbsent(l.substringAfterLast('/'), p)
        }
    }

    fun read(path: String): ByteArray {
        val raw = rawByLower[path.lowercase()] ?: throw IllegalStateException("No existe $path")
        val entry = zip.getEntry(raw) ?: throw IllegalStateException("No existe $path")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    /** Resuelve un nombre del .ini relativo a la carpeta del .ini; si no esta, busca por nombre de archivo. */
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

    fun close() {
        try { zip.close() } catch (_: Exception) {}
    }
}
