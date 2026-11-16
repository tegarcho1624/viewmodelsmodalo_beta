package com.alo.xxmiviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.exp
import kotlin.math.hypot

class MainActivity : Activity() {

    private val cBg = Color.parseColor("#0E1015")
    private val cPanel = Color.parseColor("#171A22")
    private val cLine = Color.parseColor("#2A2F3C")
    private val cChip = Color.parseColor("#222633")
    private val cFg = Color.parseColor("#E8EAF0")
    private val cMuted = Color.parseColor("#8D95A8")
    private val cAccent = Color.parseColor("#E2B455")
    private val cAccentFg = Color.parseColor("#1A1405")
    private val cWarn = Color.parseColor("#F0A35E")

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ModelRenderer
    private lateinit var root: LinearLayout
    private lateinit var panel: ScrollView
    private lateinit var emptyView: LinearLayout
    private lateinit var hud: TextView
    private lateinit var status: TextView
    private lateinit var note: TextView
    private lateinit var varsHeader: TextView
    private lateinit var varsBox: LinearLayout
    private lateinit var partsBox: LinearLayout
    private val modeButtons = ArrayList<Button>()
    private val ui = Handler(Looper.getMainLooper())

    private var archive: Archive? = null
    private val vars = HashMap<String, Double>()
    private val hidden = HashSet<String>()
    private val partRows = ArrayList<Pair<CheckBox, CpuDraw>>()
    private var loadToken = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = ModelRenderer(
            runGl = { glView.queueEvent(it) },
            redraw = { glView.requestRender() },
            onStats = { n -> ui.post { hud.text = "%,d triángulos visibles".format(n) } },
            onMessage = { m -> ui.post { note.text = m } },
        )
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        setupTouch()

        hud = text("", 12f, cMuted).apply { setPadding(dp(12), 0, dp(12), dp(8)) }
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(text("Abre el zip de un mod", 20f, cFg, bold = true).apply { gravity = Gravity.CENTER })
            addView(text("Lee los .buf, .ib, .dds y el .ini directo en el dispositivo. No necesitas XXMI ni el juego.", 14f, cMuted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16))
            })
            addView(button("Abrir zip", primary = true) { pickZip() })
        }
        val stage = FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(-1, -1))
            addView(emptyView, FrameLayout.LayoutParams(-1, -1))
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        }

        status = text("Sin mod cargado.", 13f, cMuted)
        note = text("", 12f, cWarn)
        varsHeader = header("Variantes")
        varsBox = column()
        partsBox = column()

        val col = column().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        col.addView(text("Visor XXMI", 18f, cFg, bold = true))
        col.addView(status)
        col.addView(button("Abrir zip") { pickZip() }.apply { lp(top = 10) })

        col.addView(varsHeader.apply { lp(top = 18) })
        col.addView(varsBox)

        val partsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        partsRow.addView(header("Partes"), LinearLayout.LayoutParams(0, -2, 1f))
        partsRow.addView(smallButton("Todas") { setAllParts(true) })
        partsRow.addView(smallButton("Ninguna") { setAllParts(false) }.apply { lp(left = 6) })
        col.addView(partsRow.apply { lp(top = 18) })
        col.addView(partsBox)

        col.addView(header("Vista").apply { lp(top = 18) })
        val modes = listOf("Textura", "Normales", "Gris", "Partes")
        val modeRow = LinearLayout(this)
        modes.forEachIndexed { i, name ->
            val b = smallButton(name) { setMode(i) }
            modeButtons.add(b)
            modeRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(4) })
        }
        col.addView(modeRow.apply { lp(top = 8) })
        val camRow = LinearLayout(this)
        camRow.addView(button("Centrar cámara") { glView.queueEvent { renderer.resetCam(); glView.requestRender() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        camRow.addView(button("Girar 180°") { renderer.yaw += Math.PI.toFloat(); glView.requestRender() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        col.addView(camRow.apply { lp(top = 8) })
        col.addView(note.apply { lp(top = 10) })
        setMode(0)
        setVarsVisible(false)

        panel = ScrollView(this).apply {
            setBackgroundColor(cPanel)
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }

        root = LinearLayout(this).apply {
            setBackgroundColor(cBg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(stage)
            addView(panel)
        }
        setContentView(root)
        applyOrientation()
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun applyOrientation() {
        val m = resources.displayMetrics
        val landscape = m.widthPixels > m.heightPixels
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val stage = root.getChildAt(0)
        if (landscape) {
            stage.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            panel.layoutParams = LinearLayout.LayoutParams(dp(330), -1).apply { leftMargin = dp(8) }
        } else {
            stage.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            panel.layoutParams = LinearLayout.LayoutParams(-1, (m.heightPixels * 0.42f).toInt()).apply { topMargin = dp(8) }
        }
        root.requestLayout()
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { super.onPause(); glView.onPause() }
    override fun onDestroy() { super.onDestroy(); archive?.close() }

    // ---------------------------------------------------------------- Camara tactil

    private fun setupTouch() {
        var lx = 0f; var ly = 0f
        var lDist = 0f; var lmx = 0f; var lmy = 0f
        glView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount == 2) {
                        lDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        lmx = (e.getX(0) + e.getX(1)) / 2; lmy = (e.getY(0) + e.getY(1)) / 2
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val keep = if (e.actionIndex == 0) 1 else 0
                    lx = e.getX(keep); ly = e.getY(keep)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1) {
                        renderer.rotate(e.x - lx, e.y - ly)
                        lx = e.x; ly = e.y
                    } else if (e.pointerCount >= 2) {
                        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val mx = (e.getX(0) + e.getX(1)) / 2
                        val my = (e.getY(0) + e.getY(1)) / 2
                        if (lDist > 0f && d > 0f) renderer.zoom(lDist / d)
                        renderer.pan(mx - lmx, my - lmy, glView.height.toFloat())
                        lDist = d; lmx = mx; lmy = my
                    }
                    glView.requestRender()
                }
            }
            true
        }
        // Rueda del raton / trackpad (tabletas con teclado)
        glView.setOnGenericMotionListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
                renderer.zoom(exp(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 0.12f))
                glView.requestRender(); true
            } else false
        }
    }

    // ---------------------------------------------------------------- Abrir archivos

    private fun pickZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { loadZip(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.data
        if (i?.action == Intent.ACTION_VIEW && u != null) loadZip(u)
    }

    private fun setStatus(t: String, err: Boolean = false) {
        ui.post { status.text = t; status.setTextColor(if (err) cWarn else cMuted) }
    }

    private fun loadZip(uri: Uri) {
        val token = ++loadToken
        setStatus("Copiando el zip…")
        Thread {
            try {
                val f = File(cacheDir, "mod_${System.currentTimeMillis()}.zip")
                val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo abrir el archivo")
                ins.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                cacheDir.listFiles()?.filter { it.name.startsWith("mod_") && it != f }?.forEach { it.delete() }
                val ar = Archive(f)
                val cands = ar.paths
                    .filter { it.endsWith(".ini", true) && !it.endsWith("desktop.ini", true) }
                    .map { it to String(ar.read(it), Charsets.UTF_8) }
                    .filter { it.second.contains("drawindexed", true) }
                if (cands.isEmpty()) throw IllegalStateException("No encontré un .ini con drawindexed dentro del zip.")
                if (cands.size == 1) startBuild(ar, cands[0].first, cands[0].second, token)
                else ui.post {
                    val names = cands.map { it.first }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("¿Qué .ini abrir?")
                        .setItems(names) { _, i -> startBuild(ar, cands[i].first, cands[i].second, token) }
                        .setOnCancelListener { setStatus("Cancelado.") }
                        .show()
                }
            } catch (e: Throwable) {
                setStatus("No se pudo leer el zip: ${e.message}", true)
            }
        }.start()
    }

    private fun startBuild(ar: Archive, iniPath: String, text: String, token: Int) {
        Thread {
            try {
                setStatus("Leyendo geometría…")
                val ini = Ini.parse(text, iniPath)
                val cpu = ModelLoader.build(ar, ini) { setStatus(it) }
                ui.post { if (token == loadToken) applyModel(cpu) }
            } catch (e: Throwable) {
                setStatus(e.message ?: e.toString(), true)
            }
        }.start()
    }

    // ---------------------------------------------------------------- Modelo cargado

    private fun applyModel(cpu: CpuModel) {
        val old = archive
        archive = cpu.archive
        if (old != null && old !== cpu.archive) old.close()

        vars.clear()
        vars.putAll(cpu.ini.defs)
        for ((k, vd) in cpu.ini.vars) if (!vars.containsKey(k)) vars[k] = vd.values[0].toDouble()
        hidden.clear()
        renderer.vars = HashMap(vars)
        renderer.hidden = HashSet(hidden)
        note.text = ""
        glView.queueEvent { renderer.setModel(cpu) }

        buildVars(cpu)
        buildParts(cpu)
        emptyView.visibility = View.GONE
        var draws = 0
        var tris = 0
        for (s in cpu.sections) for (d in s.draws) { draws++; tris += d.count / 3 }
        setStatus("$draws partes · %,d triángulos".format(tris))
        glView.requestRender()
    }

    private fun setVarsVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.GONE
        varsHeader.visibility = vis; varsBox.visibility = vis
    }

    private fun buildVars(cpu: CpuModel) {
        varsBox.removeAllViews()
        setVarsVisible(cpu.ini.vars.isNotEmpty())
        for ((k, vd) in cpu.ini.vars) {
            val title = LinearLayout(this)
            title.addView(text(vd.label, 14f, cFg, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (vd.key.isNotEmpty()) title.addView(text(vd.key, 12f, cMuted))
            varsBox.addView(title.apply { lp(top = 10) })
            val row = LinearLayout(this)
            val buttons = ArrayList<Pair<Int, Button>>()
            val distinct = vd.values.distinct()
            for ((n, value) in distinct.withIndex()) {
                val b = smallButton(value.toString()) { }
                buttons.add(value to b)
                b.setOnClickListener {
                    vars[k] = value.toDouble()
                    renderer.vars = HashMap(vars)
                    for ((v2, b2) in buttons) styleButton(b2, v2.toDouble() == vars[k], small = true)
                    refreshParts()
                    glView.requestRender()
                }
                row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (n > 0) leftMargin = dp(4) })
            }
            for ((v2, b2) in buttons) styleButton(b2, v2.toDouble() == vars[k], small = true)
            varsBox.addView(row.apply { lp(top = 4) })
        }
    }

    private fun buildParts(cpu: CpuModel) {
        partsBox.removeAllViews()
        partRows.clear()
        for (s in cpu.sections) for (d in s.draws) {
            val cb = CheckBox(this).apply {
                text = "${d.name}   ·   %,d".format(d.count / 3)
                setTextColor(cFg)
                textSize = 14f
                isChecked = true
                buttonTintList = ColorStateList.valueOf(cAccent)
                setOnCheckedChangeListener { _, on ->
                    if (on) hidden.remove(d.id) else hidden.add(d.id)
                    renderer.hidden = HashSet(hidden)
                    glView.requestRender()
                }
            }
            partsBox.addView(cb)
            partRows.add(cb to d)
        }
        refreshParts()
    }

    private fun refreshParts() {
        for ((cb, d) in partRows) cb.alpha = if (Ini.evalConds(d.conds, vars)) 1f else 0.45f
    }

    private fun setAllParts(on: Boolean) {
        for ((cb, _) in partRows) cb.isChecked = on
    }

    private fun setMode(m: Int) {
        renderer.mode = m
        modeButtons.forEachIndexed { i, b -> styleButton(b, i == m, small = true) }
        if (::glView.isInitialized) glView.requestRender()
    }

    // ---------------------------------------------------------------- Helpers de vistas

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(t: String) = text(t.uppercase(), 11f, cMuted, bold = true).apply { letterSpacing = 0.1f }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun styleButton(b: Button, selected: Boolean, small: Boolean = false, primary: Boolean = false) {
        val fill = when {
            primary -> cAccent
            selected -> cAccent
            else -> cChip
        }
        b.background = GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(8).toFloat(); setStroke(dp(1), if (selected || primary) fill else cLine)
        }
        b.setTextColor(if (primary || selected) cAccentFg else cFg)
        b.isAllCaps = false
        b.stateListAnimator = null
        b.minHeight = 0; b.minimumHeight = 0; b.minWidth = 0; b.minimumWidth = 0
        b.textSize = if (small) 12.5f else 14f
        val v = if (small) dp(8) else dp(12)
        b.setPadding(dp(12), v, dp(12), v)
    }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t
        styleButton(this, false, primary = primary)
        setOnClickListener { onClick() }
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        styleButton(this, false, small = true)
        setOnClickListener { onClick() }
    }

    /** Margenes rapidos (dp) para una vista dentro de un LinearLayout padre. */
    private fun View.lp(top: Int = 0, left: Int = 0) {
        val p = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)
        p.topMargin = dp(top); p.leftMargin = dp(left)
        layoutParams = p
    }
}
