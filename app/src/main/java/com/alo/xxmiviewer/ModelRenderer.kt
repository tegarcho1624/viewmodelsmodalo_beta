package com.alo.xxmiviewer

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Ajustes de textura de una seccion, elegidos por el usuario en la ventana de texturas. */
class TexCfg(
    val path: String? = null,       // null: la textura original; otro valor: ruta de una imagen del zip
    val none: Boolean = false,      // true: sin textura (gris)
    val bright: Float = 1f,         // 0..2
    val flipV: Boolean = false,     // invierte el eje vertical de la textura
    val cut: Boolean? = null,       // null: el del modelo; true/false: recorte de transparencias
    val cull: Boolean? = null,      // null: el del modelo; true/false: ocultar caras traseras
)

class ModelRenderer(
    private val runGl: (Runnable) -> Unit,
    private val redraw: () -> Unit,
    private val onStats: (Int) -> Unit,
    private val onMessage: (String) -> Unit,
) : GLSurfaceView.Renderer {

    // Estado compartido con la interfaz (se reemplaza completo, nunca se modifica en sitio)
    @Volatile var vars: Map<String, Double> = emptyMap()
    @Volatile var hidden: Set<String> = emptySet()
    @Volatile var texCfg: Map<Int, TexCfg> = emptyMap()   // por numero de seccion
    @Volatile var mode: Int = 0   // 0 textura, 1 normales, 2 gris, 3 partes

    // Camara orbital
    @Volatile var yaw = 0f
    @Volatile var pitch = 0.08f
    @Volatile var dist = 3f
    @Volatile var tx = 0f
    @Volatile var ty = 0f
    @Volatile var tz = 0f
    @Volatile var radius = 1f

    private class GlSection(val vao: Int, val section: CpuSection)
    private class GlTex { var id = 0; var srgb = false; var state = 0 }   // 0 cargando, 1 lista, 2 error

    private var cpuModel: CpuModel? = null
    private val sections = ArrayList<GlSection>()
    private val buffers = ArrayList<Int>()
    private var textures = HashMap<String, GlTex>()

    private var prog = 0
    private var uMvp = 0
    private var uView = 0
    private var uTex = 0
    private var uMode = 0
    private var uHas = 0
    private var uSrgb = 0
    private var uTint = 0
    private var uCut = 0
    private var uBright = 0
    private var uFlip = 0
    private var width = 1
    private var height = 1
    private var lastTris = -1
    private var aniso = false

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    // ---------- API (hilo GL) ----------

    fun setModel(m: CpuModel?) {
        cpuModel = m
        if (prog != 0) upload()
    }

    fun resetCam() {
        val m = cpuModel ?: return
        tx = (m.min[0] + m.max[0]) / 2
        ty = (m.min[1] + m.max[1]) / 2
        tz = (m.min[2] + m.max[2]) / 2
        radius = hypot(hypot(m.max[0] - m.min[0], m.max[1] - m.min[1]), m.max[2] - m.min[2]) / 2
        if (radius <= 0f) radius = 1f
        dist = radius / sin(Math.toRadians(17.5).toFloat()) * 1.05f
        pitch = 0.08f
        yaw = 0f
    }

    // ---------- API (hilo de interfaz) ----------

    fun rotate(dx: Float, dy: Float) {
        yaw -= dx * 0.008f
        pitch = (pitch + dy * 0.006f).coerceIn(-1.45f, 1.45f)
    }

    fun zoom(factor: Float) {
        dist = (dist * factor).coerceIn(radius * 0.15f, radius * 30f)
    }

    fun pan(dx: Float, dy: Float, viewH: Float) {
        val cy = cos(yaw); val sy = sin(yaw); val sp = sin(pitch); val cp = cos(pitch)
        val rx = cy; val rz = -sy                       // derecha
        val ux = -sp * sy; val uy = cp; val uz = -sp * cy // arriba
        val k = dist * tan(Math.toRadians(17.5).toFloat()) * 2f / viewH
        tx -= (rx * dx - ux * dy) * k
        ty -= (0f * dx - uy * dy) * k
        tz -= (rz * dx - uz * dy) * k
    }

    // ---------- GLSurfaceView.Renderer ----------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0.075f, 0.082f, 0.106f, 1f)
        prog = buildProgram()
        uMvp = GLES30.glGetUniformLocation(prog, "uMVP")
        uView = GLES30.glGetUniformLocation(prog, "uView")
        uTex = GLES30.glGetUniformLocation(prog, "uTex")
        uMode = GLES30.glGetUniformLocation(prog, "uMode")
        uHas = GLES30.glGetUniformLocation(prog, "uHas")
        uSrgb = GLES30.glGetUniformLocation(prog, "uSrgb")
        uTint = GLES30.glGetUniformLocation(prog, "uTint")
        uCut = GLES30.glGetUniformLocation(prog, "uCut")
        uBright = GLES30.glGetUniformLocation(prog, "uBright")
        uFlip = GLES30.glGetUniformLocation(prog, "uFlip")
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        aniso = ext.contains("GL_EXT_texture_filter_anisotropic")
        // El contexto pudo perderse (pausa/reanudar): se vuelve a subir el modelo
        buffers.clear(); sections.clear(); textures = HashMap()
        if (cpuModel != null) upload()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(1, w); height = maxOf(1, h)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val m = cpuModel ?: return
        if (sections.isEmpty()) return

        val cp = cos(pitch); val sp = sin(pitch); val cy = cos(yaw); val sy = sin(yaw)
        val ex = tx + dist * cp * sy
        val ey = ty + dist * sp
        val ez = tz + dist * cp * cy
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / height, dist * 0.02f, dist * 20f)
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uView, 1, false, view, 0)
        GLES30.glUniform1i(uMode, mode)
        GLES30.glUniform1i(uTex, 0)

        val v = vars
        val hid = hidden
        var di = 0
        var tris = 0
        val cfgs = texCfg
        for ((si, gs) in sections.withIndex()) {
            val cfg = cfgs[si]
            val t = if (cfg != null && cfg.none) null else texFor(m, gs.section, v, cfg)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            if (t != null) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
            GLES30.glUniform1i(uHas, if (t != null) 1 else 0)
            GLES30.glUniform1i(uSrgb, if (t != null && t.srgb) 1 else 0)
            GLES30.glUniform1i(uCut, if (t != null && (cfg?.cut ?: gs.section.alphaCut)) 1 else 0)
            GLES30.glUniform1f(uBright, cfg?.bright ?: 1f)
            GLES30.glUniform1i(uFlip, if (cfg != null && cfg.flipV) 1 else 0)
            if (cfg?.cull ?: gs.section.cull) GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glBindVertexArray(gs.vao)
            for (d in gs.section.draws) {
                val idx = di++
                if (hid.contains(d.id) || !Ini.evalConds(d.conds, v)) continue
                val c = hue(idx)
                GLES30.glUniform3f(uTint, c[0], c[1], c[2])
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, d.count, GLES30.GL_UNSIGNED_INT, d.start * 4)
                tris += d.count / 3
            }
        }
        GLES30.glBindVertexArray(0)
        if (tris != lastTris) { lastTris = tris; onStats(tris) }
    }

    // ---------- Subida de datos ----------

    private fun upload() {
        releaseGl()
        val m = cpuModel ?: return
        val vbos = HashMap<String, Int>()
        for ((k, v) in m.verts) {
            val id = gen { GLES30.glGenBuffers(1, it, 0) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
            val fb = ByteBuffer.allocateDirect(v.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(v.data).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.data.size * 4, fb, GLES30.GL_STATIC_DRAW)
            vbos[k] = id; buffers.add(id)
        }
        for (s in m.sections) {
            val vao = gen { GLES30.glGenVertexArrays(1, it, 0) }
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos.getValue(s.vertsKey))
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
            val ibo = gen { GLES30.glGenBuffers(1, it, 0) }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            val ib = ByteBuffer.allocateDirect(s.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            ib.put(s.indices).position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            GLES30.glBindVertexArray(0)
            buffers.add(ibo)
            sections.add(GlSection(vao, s))
        }
        resetCam()
        redraw()
    }

    private fun gen(f: (IntArray) -> Unit): Int { val a = IntArray(1); f(a); return a[0] }

    private fun releaseGl() {
        for (s in sections) GLES30.glDeleteVertexArrays(1, intArrayOf(s.vao), 0)
        sections.clear()
        for (b in buffers) GLES30.glDeleteBuffers(1, intArrayOf(b), 0)
        buffers.clear()
        for (t in textures.values) if (t.id != 0) GLES30.glDeleteTextures(1, intArrayOf(t.id), 0)
        textures = HashMap()
        lastTris = -1
    }

    // ---------- Texturas ----------

    private fun texFor(m: CpuModel, sec: CpuSection, v: Map<String, Double>, cfg: TexCfg?): GlTex? {
        val path: String
        if (cfg?.path != null) {
            path = cfg.path
        } else if (sec.fixedTex != null) {
            path = sec.fixedTex
        } else {
            var r: String? = null
            for (a in sec.diff) if (Ini.evalConds(a.conds, v)) r = a.res
            if (r == null) return null
            val file = m.ini.res[r]?.props?.get("filename") ?: return null
            path = m.archive.resolve(m.ini.dir, file) ?: return null
        }
        var t = textures[path]
        if (t == null) {
            t = GlTex()
            textures[path] = t
            startDecode(m, path, t)
        }
        return if (t.state == 1) t else null
    }

    private fun startDecode(m: CpuModel, path: String, t: GlTex) {
        Thread {
            try {
                val img = Images.load(m.archive.read(path), path, 2048)
                runGl(Runnable {
                    if (cpuModel === m) {
                        uploadTex(t, img)
                        redraw()
                    }
                })
            } catch (e: Throwable) {
                t.state = 2
                onMessage("No se pudo cargar ${path.substringAfterLast('/')}: ${e.message}")
            }
        }.start()
    }

    private fun uploadTex(t: GlTex, img: Img) {
        val id = gen { GLES30.glGenTextures(1, it, 0) }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val bb = ByteBuffer.allocateDirect(img.rgba.size)
        bb.put(img.rgba).position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0,
            if (img.srgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8,
            img.w, img.h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb,
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        if (aniso) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 0x84FE, 8f)
        t.id = id
        t.srgb = img.srgb
        t.state = 1
    }

    // ---------- Shaders ----------

    private fun hue(i: Int): FloatArray {
        val h = ((i * 0.618034f) % 1f) * 6f
        val c = 0.62f
        val x = c * (1f - abs(h % 2f - 1f))
        val m = 0.3f
        val rgb = when (h.toInt() % 6) {
            0 -> floatArrayOf(c, x, 0f)
            1 -> floatArrayOf(x, c, 0f)
            2 -> floatArrayOf(0f, c, x)
            3 -> floatArrayOf(0f, x, c)
            4 -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        return floatArrayOf(rgb[0] + m, rgb[1] + m, rgb[2] + m)
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FS)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec2 aUv;
uniform mat4 uMVP;
uniform mat4 uView;
out vec3 vN;
out vec2 vUv;
void main() {
  vN = mat3(uView) * aNrm;
  vUv = aUv;
  gl_Position = uMVP * vec4(aPos, 1.0);
}
"""
        private const val FS = """#version 300 es
precision highp float;
in vec3 vN;
in vec2 vUv;
out vec4 o;
uniform sampler2D uTex;
uniform int uMode;
uniform bool uHas;
uniform bool uSrgb;
uniform vec3 uTint;
uniform bool uCut;
uniform float uBright;
uniform bool uFlip;
void main() {
  vec3 n = normalize(vN);
  if (!gl_FrontFacing) n = -n;
  float key = max(dot(n, normalize(vec3(0.35, 0.55, 0.75))), 0.0);
  float fill = max(dot(n, normalize(vec3(-0.7, 0.1, 0.45))), 0.0) * 0.35;
  float rim = pow(1.0 - max(n.z, 0.0), 3.0) * 0.18;
  float light = 0.38 + 0.12 * n.y + key * 0.72 + fill + rim;
  vec4 tc = uHas ? texture(uTex, uFlip ? vec2(vUv.x, 1.0 - vUv.y) : vUv) : vec4(1.0);
  if (uCut && tc.a < 0.5) discard;
  if (uMode == 1) { o = vec4(n * 0.5 + 0.5, 1.0); return; }
  vec3 c;
  if (uMode == 0 && uHas) {
    c = tc.rgb * uBright * light;
    if (uSrgb) c = pow(c, vec3(1.0 / 2.2));
  } else if (uMode == 3) {
    c = uTint * light;
  } else {
    c = vec3(0.74) * light;
  }
  o = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
