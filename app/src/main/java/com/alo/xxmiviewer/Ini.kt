package com.alo.xxmiviewer

typealias Fn = (Map<String, Double>) -> Double

/** Condicion de un bloque if / else if / else del .ini. */
class Cond(val kind: Int, val expr: Fn, val prior: List<Fn>)   // kind: 0 = if, 1 = else if, 2 = else

class Draw(val name: String, val count: Int, val start: Int, val conds: List<Cond>)
class DiffAssign(val conds: List<Cond>, val res: String)
class Section(val name: String) {
    var ib: String? = null
    val draws = ArrayList<Draw>()
    val diff = ArrayList<DiffAssign>()
}
class Resource(val name: String) { val props = HashMap<String, String>() }
class VarDef(val label: String, val values: List<Int>, val key: String)

class IniModel(
    val dir: String,
    val res: Map<String, Resource>,
    val defs: Map<String, Double>,
    val sections: List<Section>,
    val vars: LinkedHashMap<String, VarDef>,
)

object Ini {
    fun evalConds(conds: List<Cond>, v: Map<String, Double>): Boolean {
        for (c in conds) {
            val ok = when (c.kind) {
                0 -> c.expr(v) != 0.0
                1 -> c.prior.none { it(v) != 0.0 } && c.expr(v) != 0.0
                else -> c.prior.none { it(v) != 0.0 }
            }
            if (!ok) return false
        }
        return true
    }

    private class Frame(var kind: Int, var expr: String, var prior: List<String>, val branches: MutableList<String>)

    private val reElif = Regex("^(?:else\\s+if|elif)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reIf = Regex("^if\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reElse = Regex("^else$", RegexOption.IGNORE_CASE)
    private val reEndif = Regex("^endif$", RegexOption.IGNORE_CASE)
    private val reConst = Regex("^(?:global\\s+)?(?:persist\\s+)?\\$(\\w+)\\s*=\\s*(-?[\\d.]+)", RegexOption.IGNORE_CASE)
    private val reVarList = Regex("^\\$(\\w+)\\s*=\\s*(.+)$")
    private val reCount = Regex("\\s*\\(\\d+\\)\\s*$")

    fun parse(text: String, iniPath: String): IniModel {
        val dir = if (iniPath.contains('/')) iniPath.substringBeforeLast('/') else ""
        val res = HashMap<String, Resource>()
        val defs = HashMap<String, Double>()
        val secs = ArrayList<Section>()
        val vars = LinkedHashMap<String, VarDef>()

        var kind = 0   // 0 = otra seccion, 1 = Resource, 2 = Constants, 3 = TextureOverride
        var curRes: Resource? = null
        var curSec: Section? = null
        var lastComment = ""
        val stack = ArrayList<Frame>()
        var otherKey: String? = null
        val otherVars = ArrayList<Pair<String, List<Int>>>()

        fun flushOther() {
            val k = otherKey
            if (k != null) {
                for ((n, vals) in otherVars) {
                    val ln = n.lowercase()
                    if (!vars.containsKey(ln)) vars[ln] = VarDef(n, vals, keyLabel(k))
                }
            }
            otherKey = null
            otherVars.clear()
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] == ';') { lastComment = line.substring(1).trim(); continue }
            if (line[0] == '[') {
                val end = line.indexOf(']')
                if (end > 0) {
                    flushOther()
                    val name = line.substring(1, end)
                    val ln = name.lowercase()
                    lastComment = ""
                    stack.clear()
                    curRes = null; curSec = null; kind = 0
                    when {
                        ln.startsWith("resource") -> { kind = 1; val r = Resource(name); res[ln] = r; curRes = r }
                        ln == "constants" -> kind = 2
                        ln.startsWith("textureoverride") -> { kind = 3; val s = Section(name); secs.add(s); curSec = s }
                    }
                    continue
                }
            }
            val l = line.substringBefore(';').trim()
            if (l.isEmpty()) continue

            val mElif = reElif.matchEntire(l)
            if (mElif != null) {
                val f = stack.lastOrNull()
                if (f != null) {
                    f.kind = 1; f.expr = mElif.groupValues[1]; f.prior = f.branches.toList(); f.branches.add(f.expr)
                }
                continue
            }
            val mIf = reIf.matchEntire(l)
            if (mIf != null) {
                val e = mIf.groupValues[1]
                stack.add(Frame(0, e, emptyList(), mutableListOf(e)))
                continue
            }
            if (reElse.matches(l)) {
                val f = stack.lastOrNull()
                if (f != null) { f.kind = 2; f.expr = ""; f.prior = f.branches.toList() }
                continue
            }
            if (reEndif.matches(l)) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                continue
            }

            if (kind == 2) {
                reConst.find(l)?.let { m -> m.groupValues[2].toDoubleOrNull()?.let { defs[m.groupValues[1].lowercase()] = it } }
                continue
            }
            val eq = l.indexOf('=')
            if (eq < 0) continue
            val k = l.substring(0, eq).trim().lowercase()
            val v = l.substring(eq + 1).trim()

            when (kind) {
                1 -> curRes?.props?.put(k, v)
                0 -> {
                    if (k == "key" || k == "back") { if (otherKey == null) otherKey = v }
                    reVarList.matchEntire(l)?.let { m ->
                        if (m.groupValues[2].contains(',')) {
                            val vals = m.groupValues[2].split(',').mapNotNull { it.trim().toIntOrNull() }
                            if (vals.isNotEmpty()) otherVars.add(m.groupValues[1] to vals)
                        }
                    }
                }
                3 -> {
                    val sec = curSec ?: continue
                    val conds = stack.map { f -> Cond(f.kind, Expr.compile(f.expr), f.prior.map { Expr.compile(it) }) }
                    if (k == "ib") {
                        if (v.lowercase() != "null") sec.ib = v.lowercase()
                    } else if (k == "drawindexed") {
                        val a = v.split(',').map { it.trim().toIntOrNull() ?: 0 }
                        if (a.isNotEmpty() && a[0] > 0) {
                            val name = lastComment.replace(reCount, "").ifEmpty { "Parte ${sec.draws.size + 1}" }
                            sec.draws.add(Draw(name, a[0], a.getOrElse(1) { 0 }, conds))
                        }
                        lastComment = ""
                    } else if (k.endsWith("diffuse") || k == "ps-t0") {
                        val r = v.replace(Regex("^ref\\s+", RegexOption.IGNORE_CASE), "").trim().lowercase()
                        sec.diff.add(DiffAssign(conds, r))
                    }
                }
            }
        }
        flushOther()
        return IniModel(dir, res, defs, secs, vars)
    }

    private fun keyLabel(k: String): String {
        val names = mapOf("VK_UP" to "↑", "VK_DOWN" to "↓", "VK_LEFT" to "←", "VK_RIGHT" to "→", "VK_SPACE" to "Espacio", "VK_RETURN" to "Enter")
        val out = ArrayList<String>()
        for (t in k.split(Regex("\\s+"))) {
            val l = t.lowercase()
            if (l.isEmpty() || l.startsWith("no_")) continue
            when {
                l == "ctrl" || l == "alt" || l == "shift" -> out.add(l.replaceFirstChar { it.uppercase() })
                names.containsKey(t.uppercase()) -> out.add(names.getValue(t.uppercase()))
                else -> out.add(t.replace(Regex("^VK_", RegexOption.IGNORE_CASE), "").uppercase())
            }
        }
        return out.joinToString("+")
    }
}

/** Evaluador pequeno de las expresiones de los if del .ini ($var, numeros, == != < > <= >= && || ! + - * /). */
object Expr {
    fun compile(src: String): Fn {
        val fallback: Fn = { _ -> 0.0 }
        return try {
            Parser(src).parseOr()
        } catch (_: Exception) {
            fallback
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(t: String): Boolean { ws(); return s.startsWith(t, i) }
        fun eat(t: String): Boolean { return if (peek(t)) { i += t.length; true } else false }

        fun parseOr(): Fn {
            var l = parseAnd()
            while (eat("||")) { val a = l; val r = parseAnd(); l = { v -> if (a(v) != 0.0 || r(v) != 0.0) 1.0 else 0.0 } }
            return l
        }
        fun parseAnd(): Fn {
            var l = parseEq()
            while (eat("&&")) { val a = l; val r = parseEq(); l = { v -> if (a(v) != 0.0 && r(v) != 0.0) 1.0 else 0.0 } }
            return l
        }
        fun parseEq(): Fn {
            var l = parseRel()
            while (true) {
                if (eat("==")) { val a = l; val r = parseRel(); l = { v -> if (a(v) == r(v)) 1.0 else 0.0 } }
                else if (eat("!=")) { val a = l; val r = parseRel(); l = { v -> if (a(v) != r(v)) 1.0 else 0.0 } }
                else break
            }
            return l
        }
        fun parseRel(): Fn {
            var l = parseAdd()
            while (true) {
                if (eat("<=")) { val a = l; val r = parseAdd(); l = { v -> if (a(v) <= r(v)) 1.0 else 0.0 } }
                else if (eat(">=")) { val a = l; val r = parseAdd(); l = { v -> if (a(v) >= r(v)) 1.0 else 0.0 } }
                else if (eat("<")) { val a = l; val r = parseAdd(); l = { v -> if (a(v) < r(v)) 1.0 else 0.0 } }
                else if (eat(">")) { val a = l; val r = parseAdd(); l = { v -> if (a(v) > r(v)) 1.0 else 0.0 } }
                else break
            }
            return l
        }
        fun parseAdd(): Fn {
            var l = parseMul()
            while (true) {
                if (eat("+")) { val a = l; val r = parseMul(); l = { v -> a(v) + r(v) } }
                else if (eat("-")) { val a = l; val r = parseMul(); l = { v -> a(v) - r(v) } }
                else break
            }
            return l
        }
        fun parseMul(): Fn {
            var l = parseUnary()
            while (true) {
                if (eat("*")) { val a = l; val r = parseUnary(); l = { v -> a(v) * r(v) } }
                else if (eat("/")) { val a = l; val r = parseUnary(); l = { v -> val d = r(v); if (d == 0.0) 0.0 else a(v) / d } }
                else break
            }
            return l
        }
        fun parseUnary(): Fn {
            ws()
            if (peek("!") && !peek("!=")) { i++; val a = parseUnary(); return { v -> if (a(v) == 0.0) 1.0 else 0.0 } }
            if (eat("-")) { val a = parseUnary(); return { v -> -a(v) } }
            return parsePrimary()
        }
        fun parsePrimary(): Fn {
            ws()
            if (eat("(")) { val e = parseOr(); eat(")"); return e }
            if (i < s.length && s[i] == '$') {
                i++
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '\\')) i++
                val name = s.substring(st, i).substringAfterLast('\\').lowercase()
                return { v -> v[name] ?: 0.0 }
            }
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                val n = s.substring(st, i).toDoubleOrNull() ?: 0.0
                return { _ -> n }
            }
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            if (st == i) throw IllegalArgumentException("expresion invalida")
            val w = s.substring(st, i).lowercase()
            val n = if (w == "true") 1.0 else 0.0
            return { _ -> n }
        }
    }
}
