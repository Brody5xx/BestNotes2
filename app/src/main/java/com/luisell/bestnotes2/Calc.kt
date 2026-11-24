package com.luisell.bestnotes2

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/* ============================================================
   Calc.kt — la CALCULADORA (como la de Best Notes):
   Calc · Y= · Gráfica · Tabla · Divisas · ⏱ Horas de trabajo.
   Todo nativo: el lector de fórmulas está aquí abajo (sin eval).
   ============================================================ */

/** Lee fórmulas como "2sin(30)+√(9)" y las vuelve una función de x. */
object Formula {
    var deg = false
    var ans = 0.0

    private fun toR(v: Double) = if (deg) v * Math.PI / 180 else v
    private fun fromR(v: Double) = if (deg) v * 180 / Math.PI else v
    private val FUN: Map<String, (Double) -> Double> = mapOf(
        "sin" to { v -> Math.sin(toR(v)) }, "cos" to { v -> Math.cos(toR(v)) }, "tan" to { v -> Math.tan(toR(v)) },
        "asin" to { v -> fromR(Math.asin(v)) }, "acos" to { v -> fromR(Math.acos(v)) }, "atan" to { v -> fromR(Math.atan(v)) },
        "ln" to { v -> Math.log(v) }, "log" to { v -> Math.log10(v) }, "sqrt" to { v -> Math.sqrt(v) }, "cbrt" to { v -> Math.cbrt(v) },
        "abs" to { v -> abs(v) }, "exp" to { v -> Math.exp(v) }, "tenx" to { v -> 10.0.pow(v) },
    )
    private val NAMES = listOf("asin", "acos", "atan", "sqrt", "cbrt", "tenx", "sin", "cos", "tan", "abs", "exp", "log", "ln", "Ans", "pi", "e", "x")

    class Bad : Exception()
    private class Tk(val k: Char, val v: Double = 0.0, val name: String = "")   // k: n número, i nombre, f función, o símbolo

    private fun fact(n: Double): Double {
        if (n < 0 || n != floor(n) || n > 170) return Double.NaN
        var r = 1.0; var i = 2; while (i <= n) { r *= i; i++ }; return r
    }

    fun norm(t: String): String = t.replace('×', '*').replace('÷', '/').replace('−', '-').replace('‐', '-')
        .replace("sin⁻¹", "asin").replace("cos⁻¹", "acos").replace("tan⁻¹", "atan")
        .replace("²", "^2").replace("³", "^3").replace("⁻¹", "^(-1)")
        .replace("π", "pi").replace("√", "sqrt").replace("∛", "cbrt").replace("ᴇ", "*10^")
        .replace("10^(", "tenx(").replace("e^(", "exp(")

    private fun tokenize(t: String): MutableList<Tk> {
        val out = ArrayList<Tk>(); var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch == ' ') { i++; continue }
            if (ch.isDigit() || (ch == '.' && i + 1 < t.length && t[i + 1].isDigit())) {
                var j = i; while (j < t.length && (t[j].isDigit() || t[j] == '.')) j++
                val num = t.substring(i, j)
                if (num.count { it == '.' } > 1) throw Bad()
                out.add(Tk('n', num.toDouble())); i = j; continue
            }
            if ("+-*/^()!%,".indexOf(ch) >= 0) { out.add(Tk('o', name = ch.toString())); i++; continue }
            val hit = NAMES.firstOrNull { t.startsWith(it, i) } ?: throw Bad()
            out.add(if (FUN.containsKey(hit)) Tk('f', name = hit) else Tk('i', name = hit)); i += hit.length
        }
        var open = 0
        for (x in out) { if (x.k == 'o' && x.name == "(") open++; if (x.k == 'o' && x.name == ")") open-- }
        while (open-- > 0) out.add(Tk('o', name = ")"))       // cierra los paréntesis que faltan (como la TI)
        return out
    }

    fun compile(text: String): (Double) -> Double {
        val tk = tokenize(norm(text)); var p = 0
        if (tk.isEmpty()) throw Bad()
        fun peek() = tk.getOrNull(p)
        fun isOp(t: Tk?, s: String) = t != null && t.k == 'o' && t.name == s
        fun eat(s: String): Boolean { if (isOp(peek(), s)) { p++; return true }; return false }
        fun startsPrimary(t: Tk?) = t != null && (t.k == 'n' || t.k == 'i' || t.k == 'f' || isOp(t, "("))
        lateinit var expr: () -> (Double) -> Double
        lateinit var unary: () -> (Double) -> Double
        lateinit var power: () -> (Double) -> Double
        fun primary(): (Double) -> Double {
            val t = tk.getOrNull(p++) ?: throw Bad()
            when (t.k) {
                'n' -> { val v = t.v; return { v } }
                'o' -> if (t.name == "(") { val a = expr(); if (!eat(")")) throw Bad(); return a }
                'i' -> when (t.name) {
                    "pi" -> return { Math.PI }; "e" -> return { Math.E }
                    "Ans" -> { val an = ans; return { an } }
                    "x" -> return { x -> x }
                }
                'f' -> {
                    val f = FUN.getValue(t.name)
                    val arg = if (isOp(peek(), "(")) { p++; val a = expr(); if (!eat(")")) throw Bad(); a } else power()
                    return { x -> f(arg(x)) }
                }
            }
            throw Bad()
        }
        fun postfix(): (Double) -> Double {
            var a = primary()
            while (true) {
                if (eat("!")) { val b = a; a = { x -> fact(b(x)) }; continue }
                if (eat("%")) { val b = a; a = { x -> b(x) / 100 }; continue }
                break
            }
            return a
        }
        power = {
            val a = postfix()
            if (eat("^")) { val b = unary(); { x: Double -> a(x).pow(b(x)) } } else a
        }
        unary = {
            when {
                eat("-") -> { val a = unary(); { x: Double -> -a(x) } }
                eat("+") -> unary()
                else -> power()
            }
        }
        fun term(): (Double) -> Double {
            var a = unary()
            while (true) {
                val t = peek()
                if (isOp(t, "*") || isOp(t, "/")) {
                    p++; val b = unary(); val l = a
                    a = if (t!!.name == "*") { x -> l(x) * b(x) } else { x -> l(x) / b(x) }
                } else if (startsPrimary(t)) {                 // 2x, 3(4), 2π
                    val b = power(); val l = a; a = { x -> l(x) * b(x) }
                } else break
            }
            return a
        }
        expr = {
            var a = term()
            while (isOp(peek(), "+") || isOp(peek(), "-")) {
                val op = tk[p++].name; val b = term(); val l = a
                a = if (op == "+") { x -> l(x) + b(x) } else { x -> l(x) - b(x) }
            }
            a
        }
        val fn = expr()
        if (p < tk.size) throw Bad()
        return fn
    }

    /** Números bonitos: 10 cifras, 1.5ᴇ12 para los muy grandes. */
    fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return L.t("calcError")
        if (abs(v) < 1e-12) return "0"
        val a = abs(v)
        if (a >= 1e10 || a < 1e-6) {
            val s = String.format(java.util.Locale.US, "%.9e", v).split("e")
            return trimZ(s[0]) + "ᴇ" + s[1].toInt()
        }
        val s = java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
        return s.replace('-', '−')
    }
    private fun trimZ(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s

    /** Calcula un texto (o null si no se puede). */
    fun eval(text: String): Double? = try { compile(text)(0.0) } catch (_: Exception) { null }
}

class CalcActivity : Activity() {
    companion object {
        const val EXTRA_CAN_INSERT = "puedePoner"
        const val RESULT_TEXT = "texto"
        val COLORS = intArrayOf(0xFF4F8DFF.toInt(), 0xFFFF5A5A.toInt(), 0xFF3ECF6E.toInt(), 0xFFC77DFF.toInt())
    }

    private lateinit var S: JSONObject
    private lateinit var hist: JSONArray
    private val ys = arrayOf("", "", "", "")
    private val on = booleanArrayOf(true, true, true, true)
    private var tab = "calc"
    private var second = false

    private lateinit var tabsRow: LinearLayout
    private lateinit var views: Map<String, View>
    private lateinit var pad: LinearLayout
    private lateinit var angBtn: TextView
    private lateinit var input: EditText
    private lateinit var preview: TextView
    private lateinit var histBox: LinearLayout
    private lateinit var histScroll: ScrollView
    private var active: EditText? = null
    private val yInputs = ArrayList<EditText>()
    private lateinit var graph: GraphView
    private lateinit var table: LinearLayout
    private lateinit var tStart: EditText
    private lateinit var tStep: EditText
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        S = try { JSONObject(getSharedPreferences("ajustes", 0).getString("calcState", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        hist = S.optJSONArray("hist") ?: JSONArray()
        S.optJSONArray("ys")?.let { for (i in 0 until minOf(4, it.length())) ys[i] = it.optString(i) }
        S.optJSONArray("on")?.let { for (i in 0 until minOf(4, it.length())) on[i] = it.optBoolean(i, true) }
        Formula.deg = S.optBoolean("deg")
        Formula.ans = S.optDouble("ans", 0.0).let { if (it.isNaN()) 0.0 else it }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        edgeToEdge(root)
        // arriba: ✕ y las pestañas
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(4)) }
        top.addView(glassBtn(Icons.CLOSE) { finish() })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(3), dp(3), dp(3), dp(3)); background = Glass.make(this@CalcActivity, 20f) }
        for ((id, label) in listOf("calc" to "Calc", "y" to "Y=", "graph" to L.t("calcGraph"), "table" to L.t("calcTable"), "fx" to L.t("calcFx"), "hrs" to "⏱ " + L.t("hrsTab"), "cable" to "🔌 " + L.t("cblTab"))) {
            tabsRow.addView(tv(label, 13f, T.text, true).apply {
                tag = id; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { setTab(id) }
            })
        }
        hs.addView(tabsRow)
        top.addView(hs, lp(0, WRAP, 1f).apply { marginStart = dp(6) })
        root.addView(top)
        // RAD/DEG y "Poner en la nota"
        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(4)) }
        angBtn = pill("RAD") { Formula.deg = !Formula.deg; syncAng(); updatePreview(); save(); graph.invalidate(); if (tab == "table") buildTable() }
        sub.addView(angBtn)
        sub.addView(View(this), lp(0, 1, 1f))
        if (intent.getBooleanExtra(EXTRA_CAN_INSERT, false)) sub.addView(pill("↧ " + L.t("calcInsert")) { insertInNote() })
        root.addView(sub)

        val content = FrameLayout(this)
        views = mapOf("calc" to buildCalc(), "y" to buildY(), "graph" to buildGraph(), "table" to buildTableView(), "fx" to buildFx(), "hrs" to buildHrs(), "cable" to buildCable())
        for (v in views.values) content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(content, lp(MATCH, 0, 1f))
        pad = buildPad()
        root.addView(pad, lp(MATCH, WRAP))
        setContentView(root)
        // el teclado del teléfono NO se abre solo: la calculadora tiene sus propias teclas
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root.post { hideKb() }
        syncAng()
        renderHist()
        setTab("calc")
    }

    private fun pill(text: String, onClick: () -> Unit) = tv(text, 13f, T.text, true).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7)); pressBg(tw(0x1A), 16f, T.border); setOnClickListener { onClick() }
    }

    private fun syncAng() {
        angBtn.text = if (Formula.deg) "DEG" else "RAD"
        if (Formula.deg) { angBtn.setTextColor(T.onPill); angBtn.pressBg(T.pill, 16f) }
        else { angBtn.setTextColor(T.text); angBtn.pressBg(tw(0x1A), 16f, T.border) }
    }

    private fun setTab(t: String) {
        tab = t
        for (i in 0 until tabsRow.childCount) {
            val b = tabsRow.getChildAt(i) as TextView
            val sel = b.tag == t
            b.background = if (sel) roundBg(T.pill, dp(16).toFloat()) else null
            b.setTextColor(if (sel) T.onPill else T.text)
        }
        for ((k, v) in views) v.visibility = if (k == t) View.VISIBLE else View.GONE
        pad.visibility = if (t == "calc" || t == "y") View.VISIBLE else View.GONE
        angBtn.visibility = if (t == "fx" || t == "table" || t == "hrs" || t == "cable") View.INVISIBLE else View.VISIBLE
        hideKb()
        when (t) {
            "calc" -> setActive(input)
            "y" -> setActive(yInputs[0])
            "graph" -> graph.invalidate()
            "table" -> buildTable()
            "fx" -> fxOpen()
            "hrs" -> hrsRender()
        }
    }

    private fun hideKb() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onResume() { super.onResume(); if (tab == "calc" || tab == "y") window.decorView.post { hideKb() } }

    /* ---------- guardar (un poquito después, para no guardar en cada tecla) ---------- */
    private val saver = Runnable {
        S.put("hist", hist); S.put("ys", JSONArray(ys.toList())); S.put("on", JSONArray(on.toList()))
        S.put("deg", Formula.deg); S.put("ans", if (Formula.ans.isFinite()) Formula.ans else 0.0)
        getSharedPreferences("ajustes", 0).edit().putString("calcState", S.toString()).apply()
    }
    private fun save() { handler.removeCallbacks(saver); handler.postDelayed(saver, 300) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(saver); saver.run() }

    /* =========================================================
       CALC: historial + línea para escribir
       ========================================================= */
    private fun lineInput(hint: String = "") = EditText(this).apply {
        showSoftInputOnFocus = false                      // se escribe con las teclas de la calculadora
        setTextColor(T.text); setHintTextColor(T.faint); this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); isSingleLine = true
        background = null; setPadding(dp(14), dp(8), dp(14), dp(8))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun buildCalc(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histScroll = ScrollView(this)
        histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(6)); gravity = Gravity.BOTTOM }
        histScroll.addView(histBox, FrameLayout.LayoutParams(MATCH, WRAP))
        histScroll.isFillViewport = true
        box.addView(histScroll, lp(MATCH, 0, 1f))
        val inBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Glass.make(this@CalcActivity, 18f) }
        input = lineInput()
        input.setOnFocusChangeListener { _, f -> if (f) setActive(input) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        })
        input.setOnEditorActionListener { _, _, _ -> enter(); true }
        inBox.addView(input)
        preview = tv("", 15f, T.muted).apply { gravity = Gravity.END; setPadding(dp(14), 0, dp(14), dp(8)) }
        inBox.addView(preview)
        box.addView(inBox, lp(MATCH, WRAP).apply { setMargins(dp(10), dp(4), dp(10), dp(6)) })
        return box
    }

    private fun updatePreview() {
        val v = input.text.toString().trim()
        val r = if (v.isEmpty()) null else Formula.eval(v)
        preview.text = if (r != null && !r.isNaN()) "= " + Formula.fmt(r) else ""
    }

    private fun renderHist() {
        histBox.removeAllViews()
        val err = L.t("calcError")
        for (i in 0 until hist.length()) {
            val x = hist.optJSONObject(i) ?: continue
            val e = x.optString("e"); val r = x.optString("r")
            histBox.addView(tv(e, 16f, T.muted).apply {
                setPadding(0, dp(6), 0, 0)
                setOnClickListener { setActive(input); insert(e, true) }
            })
            histBox.addView(tv(r, 22f, if (r == err) T.danger else T.text, true).apply {
                gravity = Gravity.END; setPadding(0, 0, 0, dp(6))
                setOnClickListener { if (r != err) { setActive(input); insert(r.replace('−', '-'), true) } }
            })
            histBox.addView(View(this).apply { setBackgroundColor(tw(0x12)) }, lp(MATCH, dp(1)))
        }
        histScroll.post { histScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun enter() {
        var v = input.text.toString().trim()
        if (v.isEmpty() && hist.length() > 0) v = hist.getJSONObject(hist.length() - 1).optString("e")   // ENTER vacío: repetir
        if (v.isEmpty()) return
        val av = Formula.fmt(Formula.ans)
        val r = try { val n = Formula.compile(v)(0.0); if (n.isFinite()) Formula.ans = n; Formula.fmt(n) } catch (_: Exception) { L.t("calcError") }
        hist.put(JSONObject().put("e", v).put("r", r).put("av", av))
        while (hist.length() > 60) hist.remove(0)
        input.setText(""); preview.text = ""
        renderHist(); save()
    }

    private fun setActive(e: EditText) {
        active = e
        for (y in yInputs) y.background = if (y === e) roundBg(tw(0x14), dp(12).toFloat(), tw(0x55), dp(1)) else roundBg(0x0AFFFFFF, dp(12).toFloat())
        e.requestFocus()
        e.setSelection(e.text.length.coerceAtLeast(0).let { minOf(it, e.selectionEnd.takeIf { s -> s >= 0 } ?: it) })
    }

    private fun insert(t0: String, raw: Boolean = false) {
        val el = active ?: return
        var t = t0
        val a = maxOf(0, minOf(el.selectionStart, el.selectionEnd)); val b = maxOf(0, maxOf(el.selectionStart, el.selectionEnd))
        if (!raw && el === input && el.text.isEmpty() && Regex("^([+×÷^!]|−$|²|³|⁻¹)").containsMatchIn(t)) t = "Ans$t"   // como la TI
        el.text.replace(a, b, t)
        el.setSelection(a + t.length)
        changed(el)
    }

    private fun back() {
        val el = active ?: return
        val a = el.selectionStart; val b = el.selectionEnd
        if (a != b) { el.text.delete(minOf(a, b), maxOf(a, b)) }
        else if (a > 0) {
            val before = el.text.substring(0, a)
            val m = Regex("(sin⁻¹\\(|cos⁻¹\\(|tan⁻¹\\(|sin\\(|cos\\(|tan\\(|abs\\(|log\\(|ln\\(|10\\^\\(|e\\^\\(|Ans|√\\(|∛\\(|⁻¹)$").find(before)
            val cut = m?.value?.length ?: 1
            el.text.delete(a - cut, a)
        }
        changed(el)
    }

    private fun changed(el: EditText) {
        if (el === input) updatePreview()
        else { val i = yInputs.indexOf(el); if (i >= 0) { ys[i] = el.text.toString(); save() } }
    }

    private fun clearAll() {
        val el = active ?: return
        if (el === input && el.text.isEmpty()) { hist = JSONArray(); renderHist(); save() }
        el.setText(""); changed(el)
    }

    private fun insertInNote() {
        val text = when (tab) {
            "fx" -> fxText()
            "hrs" -> hrsText()
            "cable" -> cableText()
            else -> {
                val x = if (hist.length() > 0) hist.getJSONObject(hist.length() - 1) else null
                if (x == null || x.optString("r") == L.t("calcError")) "" else {
                    val av = x.optString("av")
                    x.optString("e").replace("Ans", if (av.isNotEmpty()) "($av)" else "Ans") + " = " + x.optString("r")
                }
            }
        }
        if (text.isEmpty()) { toast("—"); return }
        setResult(RESULT_OK, Intent().putExtra(RESULT_TEXT, text)); finish()
    }

    /* =========================================================
       Las teclas (como una TI): [principal, valor, 2nd, valor 2nd, tipo]
       ========================================================= */
    private val KEYS = listOf(
        listOf("2nd", "#2nd", "", "", "sec"), listOf("x", "x", "", "", ""), listOf("π", "π", "", "", ""), listOf("e", "e", "", "", ""), listOf("⌫", "#back", "", "", "clr"),
        listOf("sin", "sin(", "sin⁻¹", "sin⁻¹(", ""), listOf("cos", "cos(", "cos⁻¹", "cos⁻¹(", ""), listOf("tan", "tan(", "tan⁻¹", "tan⁻¹(", ""), listOf("^", "^", "ᴇ", "ᴇ", ""), listOf("AC", "#clear", "", "", "clr"),
        listOf("ln", "ln(", "eˣ", "e^(", ""), listOf("log", "log(", "10ˣ", "10^(", ""), listOf("√", "√(", "∛", "∛(", ""), listOf("x²", "²", "x³", "³", ""), listOf("x⁻¹", "⁻¹", "!", "!", ""),
        listOf("7", "7", "", "", "num"), listOf("8", "8", "", "", "num"), listOf("9", "9", "", "", "num"), listOf("(", "(", "abs", "abs(", ""), listOf(")", ")", "%", "%", ""),
        listOf("4", "4", "", "", "num"), listOf("5", "5", "", "", "num"), listOf("6", "6", "", "", "num"), listOf("×", "×", "", "", "op"), listOf("÷", "÷", "", "", "op"),
        listOf("1", "1", "", "", "num"), listOf("2", "2", "", "", "num"), listOf("3", "3", "", "", "num"), listOf("+", "+", "", "", "op"), listOf("−", "−", "", "", "op"),
        listOf("0", "0", "", "", "num"), listOf(".", ".", "", "", "num"), listOf("(−)", "-", "", "", "num"), listOf("Ans", "Ans", "", "", ""), listOf("ENTER", "#enter", "", "", "ent"),
    )
    private val keyViews = ArrayList<Pair<LinearLayout, List<String>>>()

    private fun buildPad(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(10)) }
        for (r in 0 until 7) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 5) {
                val k = KEYS[r * 5 + c]
                val bg = when (k[4]) { "num" -> tw(0x26); "op" -> tw(0x40); "clr" -> 0x40FF6B6B; "ent" -> T.pill; "sec" -> 0x408B7CFF; else -> tw(0x14) }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    pressBg(bg, 14f)
                    setOnClickListener { key(k) }
                }
                Anim.press(cell, 0.9f)                                  // ✨ las teclas se hunden y rebotan
                cell.alpha = 0f; cell.translationY = dp(24).toFloat()   // ✨ y llegan en ola al abrir
                cell.animate().alpha(1f).translationY(0f).setStartDelay(40L * r + 18L * c).setDuration(340).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
                val small = tv(k[2], 10f, 0xFFB9AEFF.toInt()).apply { gravity = Gravity.CENTER }
                if (k[2].isEmpty()) small.visibility = View.GONE
                cell.addView(small)
                cell.addView(tv(k[0], if (k[0].length > 3) 13f else 17f, if (k[4] == "ent") T.onPill else T.text, k[4] != "").apply { gravity = Gravity.CENTER })
                row.addView(cell, lp(0, dp(44), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                keyViews.add(cell to k)
            }
            box.addView(row)
        }
        return box
    }

    private fun key(k: List<String>) {
        val v = if (second && k[3].isNotEmpty()) k[3] else k[1]
        if (v != "#2nd") setSecond(false)
        when (v) {
            "#2nd" -> setSecond(!second)
            "#back" -> back()
            "#clear" -> clearAll()
            "#enter" -> if (active === input) enter() else { val i = yInputs.indexOf(active); setActive(yInputs[(i + 1) % 4]) }
            else -> insert(v)
        }
    }

    private fun setSecond(on: Boolean) {
        second = on
        for ((cell, k) in keyViews) {
            if (k[2].isEmpty()) continue
            (cell.getChildAt(0) as TextView).alpha = if (on) 1f else 0.75f
            (cell.getChildAt(1) as TextView).alpha = if (on) 0.4f else 1f
        }
    }

    /* =========================================================
       Y= (hasta 4 funciones)
       ========================================================= */
    private fun buildY(): View {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        for (i in 0 until 4) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tog = tv("Y${i + 1}", 15f, COLORS[i], true).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)) }
            fun paintTog() { tog.background = roundBg(if (on[i]) COLORS[i] and tw(0x33) else 0, dp(12).toFloat(), COLORS[i], dp(2)); tog.alpha = if (on[i]) 1f else 0.45f }
            paintTog()
            tog.setOnClickListener { on[i] = !on[i]; paintTog(); save() }
            row.addView(tog, lp(dp(54), WRAP))
            val e = lineInput("x²−4").apply { setText(ys[i]); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
            e.setOnFocusChangeListener { _, f -> if (f) setActive(e) }
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { ys[i] = s.toString(); save() }
            })
            yInputs.add(e)
            row.addView(e, lp(0, WRAP, 1f).apply { marginStart = dp(8) })
            box.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
        sv.addView(box)
        return sv
    }

    private fun funcs(): List<Pair<Int, (Double) -> Double>> = (0 until 4).mapNotNull { i ->
        if (!on[i] || ys[i].isBlank()) null else try { i to Formula.compile(ys[i]) } catch (_: Exception) { null }
    }

    /* =========================================================
       GRÁFICA: mover con un dedo, zoom con dos, rastreo
       ========================================================= */
    private fun buildGraph(): View {
        val box = FrameLayout(this)
        graph = GraphView()
        S.optJSONObject("win")?.let { w -> if (w.optDouble("sc", 0.0) > 0) { graph.cx = w.optDouble("cx"); graph.cy = w.optDouble("cy"); graph.sc = w.optDouble("sc") } }
        box.addView(graph, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(10), dp(4), dp(10), dp(10)) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btns.addView(glassText("+") { graph.zoom(0.5) })
        btns.addView(glassText("−") { graph.zoom(2.0) })
        btns.addView(glassText("⟲") { graph.std(); graph.saveWin() })
        btns.addView(glassText("⌖") { graph.toggleTrace() })
        box.addView(btns, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(14), dp(18), 0) })
        return box
    }

    private fun glassText(t: String, onClick: () -> Unit) = tv(t, 20f, T.text, true).apply {
        gravity = Gravity.CENTER; background = Glass.make(this@CalcActivity, 22f)
        layoutParams = lp(dp(44), dp(44)).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    inner class GraphView : View(this@CalcActivity) {
        var cx = 0.0; var cy = 0.0; var sc = 0.0
        private var trace = false; private var traceFi = 0; private var traceX = 0.0
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tw(0x14); strokeWidth = dp(1).toFloat() }
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt(); strokeWidth = dp(1.4f).toFloat() }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt(); textSize = dp(11).toFloat() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.4f).toFloat(); strokeJoin = Paint.Join.ROUND }
        private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tw(0x59); strokeWidth = dp(1).toFloat(); pathEffect = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val info = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.text; textSize = dp(14).toFloat() }
        private val path = Path()

        init { background = Glass.make(this@CalcActivity, 18f); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }

        fun std() { cx = 0.0; cy = 0.0; sc = 20.0 / maxOf(1, minOf(width, height)); invalidate() }
        fun zoom(f: Double) { sc *= f; saveWin(); invalidate() }
        fun saveWin() { S.put("win", JSONObject().put("cx", cx).put("cy", cy).put("sc", sc)); save() }
        fun toggleTrace() {
            val fs = funcs()
            if (!trace) { trace = true; traceX = cx; traceFi = fs.firstOrNull()?.first ?: 0 }
            else if (fs.size > 1) { val k = fs.indexOfFirst { it.first == traceFi }; if (k < fs.size - 1) traceFi = fs[k + 1].first else trace = false }
            else trace = false
            invalidate()
        }

        private fun niceStep(range: Double): Double {
            val raw = range / 8; val p = 10.0.pow(floor(log10(raw))); val n = raw / p
            return (if (n < 1.5) 1.0 else if (n < 3.5) 2.0 else if (n < 7.5) 5.0 else 10.0) * p
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0) return
            if (sc <= 0) sc = 20.0 / maxOf(1f, minOf(w, h))
            fun X(x: Double) = (w / 2 + (x - cx) / sc).toFloat()
            fun Y(y: Double) = (h / 2 - (y - cy) / sc).toFloat()
            val x0 = cx - w / 2 * sc; val x1 = cx + w / 2 * sc; val y0 = cy - h / 2 * sc; val y1 = cy + h / 2 * sc
            val step = niceStep(x1 - x0)
            var g = ceil(x0 / step) * step
            while (g <= x1) { c.drawLine(X(g), 0f, X(g), h, grid); g += step }
            g = ceil(y0 / step) * step
            while (g <= y1) { c.drawLine(0f, Y(g), w, Y(g), grid); g += step }
            c.drawLine(0f, Y(0.0), w, Y(0.0), axis); c.drawLine(X(0.0), 0f, X(0.0), h, axis)
            num.textAlign = Paint.Align.CENTER
            val ay = minOf(h - dp(4), maxOf(dp(12).toFloat(), Y(0.0) + dp(14)))
            g = ceil(x0 / step) * step
            while (g <= x1) { if (abs(g) > step / 2) c.drawText(Formula.fmt(g.toBigDecimal().round(java.math.MathContext(6)).toDouble()), X(g), ay, num); g += step }
            num.textAlign = Paint.Align.LEFT
            val axx = minOf(w - dp(30), maxOf(dp(4).toFloat(), X(0.0) + dp(4)))
            g = ceil(y0 / step) * step
            while (g <= y1) { if (abs(g) > step / 2) c.drawText(Formula.fmt(g.toBigDecimal().round(java.math.MathContext(6)).toDouble()), axx, Y(g) + dp(4), num); g += step }
            val fs = funcs()
            for ((i, f) in fs) {
                line.color = COLORS[i]; path.reset()
                var pen = false; var prev = Float.NaN
                var px = 0f
                while (px <= w) {
                    val yv = try { f(x0 + px * sc) } catch (_: Exception) { Double.NaN }
                    val py = Y(yv)
                    if (!yv.isFinite() || abs(py) > 1e5) { pen = false; prev = Float.NaN; px += 1.5f; continue }
                    if (pen && !prev.isNaN() && abs(py - prev) > h * 0.6f) pen = false          // asíntota: no se une
                    if (pen) path.lineTo(px, py) else { path.moveTo(px, py); pen = true }
                    prev = py; px += 1.5f
                }
                c.drawPath(path, line)
            }
            if (trace && fs.isNotEmpty()) {
                val (fi, f) = fs.firstOrNull { it.first == traceFi } ?: fs[0]; traceFi = fi
                val yv = f(traceX)
                if (yv.isFinite()) {
                    c.drawLine(X(traceX), 0f, X(traceX), h, dash)
                    dot.style = Paint.Style.FILL; dot.color = COLORS[fi]; c.drawCircle(X(traceX), Y(yv), dp(6).toFloat(), dot)
                    dot.style = Paint.Style.STROKE; dot.color = 0xFFFFFFFF.toInt(); dot.strokeWidth = dp(2).toFloat(); c.drawCircle(X(traceX), Y(yv), dp(6).toFloat(), dot)
                }
                info.color = COLORS[fi]
                c.drawText("Y${fi + 1}", dp(12).toFloat(), h - dp(14), info)
                info.color = T.text
                c.drawText("x = ${Formula.fmt(traceX)}    y = ${Formula.fmt(yv)}", dp(44).toFloat(), h - dp(14), info)
            }
        }

        private val pts = HashMap<Int, android.graphics.PointF>()
        private var g0 = 0.0
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex; pts[e.getPointerId(i)] = android.graphics.PointF(e.getX(i), e.getY(i)); g0 = 0.0
                    parent.requestDisallowInterceptTouchEvent(true)
                    if (trace && pts.size == 1) { traceX = cx + (e.getX(i) - width / 2) * sc; invalidate() }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1) {
                        val id = e.getPointerId(0); val p = pts[id] ?: return true
                        if (trace) { traceX = cx + (e.x - width / 2) * sc }
                        else { cx -= (e.x - p.x) * sc; cy += (e.y - p.y) * sc }
                        p.set(e.x, e.y); invalidate()
                    } else {
                        val d = hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).coerceAtLeast(1.0)
                        if (g0 > 0) { sc *= g0 / d; invalidate() }
                        g0 = d
                        for (k in 0 until e.pointerCount) pts[e.getPointerId(k)]?.set(e.getX(k), e.getY(k))
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> { pts.remove(e.getPointerId(e.actionIndex)); g0 = 0.0 }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pts.clear(); g0 = 0.0; saveWin() }
            }
            return true
        }
    }

    /* =========================================================
       TABLA
       ========================================================= */
    private fun buildTableView(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(14), dp(12), 0) }
        // INICIO y PASO, con su nombre arriba (como en Best Notes)
        val set = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun field(label: String, v: String, end: Boolean): EditText {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(label.uppercase(L.locale), 13f, T.muted).apply { letterSpacing = 0.08f; setPadding(dp(2), 0, 0, dp(8)) })
            val e = EditText(this).apply {
                setText(v); setTextColor(T.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                background = roundBg(0x33000000, dp(18).toFloat(), tw(0x40), dp(1)); setPadding(dp(14), dp(10), dp(14), dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { buildTable() }
                })
            }
            col.addView(e, lp(MATCH, WRAP))
            set.addView(col, lp(0, WRAP, 1f).apply { if (end) marginStart = dp(6) else marginEnd = dp(6) })
            return e
        }
        tStart = field(L.t("calcStart"), "0", false); tStep = field(L.t("calcStep"), "1", true)
        box.addView(set, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(table)
        box.addView(sv, lp(MATCH, 0, 1f))
        return box
    }

    private fun num(e: EditText, def: Double) = e.text.toString().replace(',', '.').replace('−', '-').toDoubleOrNull() ?: def

    private fun buildTable() {
        if (!::table.isInitialized) return
        table.removeAllViews()
        val fs = funcs()
        val a = num(tStart, 0.0); val s = num(tStep, 1.0).let { if (it == 0.0) 1.0 else it }
        val pre = (if (a != 0.0) Formula.fmt(a) + " + " else "") + Formula.fmt(s) + " × "
        // fila: x a la izquierda, las Y en el medio, "1 × n" a la derecha
        fun row(x: String, ys: List<Pair<String, Int>>, mul: String, head: Boolean) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0); minimumHeight = dp(52) }
            r.addView(tv(x, 18f, T.text, head), lp(0, WRAP, 1f))
            for ((t, c) in ys) r.addView(tv(t, 18f, c, head).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1.2f))
            r.addView(tv(mul, 18f, if (head) T.text else T.muted, head).apply { gravity = Gravity.END }, lp(0, WRAP, 1.3f))
            table.addView(r)
            table.addView(View(this).apply { setBackgroundColor(tw(0x1F)) }, lp(MATCH, dp(1)))
        }
        row("x", fs.map { "Y${it.first + 1}" to COLORS[it.first] }, pre + "n", true)
        for (k in 0 until 60) {
            val x = (a + k * s).toBigDecimal().round(java.math.MathContext(12)).toDouble()
            row(Formula.fmt(x), fs.map { (_, f) -> Formula.fmt(try { f(x) } catch (_: Exception) { Double.NaN }) to T.text }, pre + k, false)
        }
    }

    /* =========================================================
       DIVISAS (tasas del día por internet, gratis)
       ========================================================= */
    private lateinit var fxFrom: TextView
    private lateinit var fxTo: TextView
    private lateinit var fxAmt: EditText
    private lateinit var fxRes: TextView
    private lateinit var fxL1: TextView
    private lateinit var fxL2: TextView
    private lateinit var fxInfo: TextView
    private var FX: JSONObject? = null
    private var fxErr = false
    private var fxOld = false
    private var fxAsked = false
    private val POP = listOf("USD", "EUR", "DOP", "MXN", "COP", "GBP", "CAD", "ARS", "CLP", "PEN", "BRL", "JPY", "CNY", "CHF", "VES", "GTQ", "HNL", "CRC", "UYU", "BOB", "PYG", "NIO", "CUP", "INR")

    private fun fxState(): JSONObject = S.optJSONObject("fx") ?: JSONObject().put("from", "USD").put("to", "").put("amt", "1").also { S.put("fx", it) }

    private fun buildFx(): View {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(20)) }
        fun curBtn(which: String) = tv("", 17f, T.text, true).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14)); background = Glass.make(this@CalcActivity, 18f)
            setOnClickListener { pickCurrency(which) }
        }
        fxFrom = curBtn("from"); box.addView(fxFrom)
        fxAmt = EditText(this).apply {
            setTextColor(T.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = roundBg(0x2E000000, dp(16).toFloat(), T.border, dp(1)); setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { fxState().put("amt", s.toString()); fxRender(); save() }
            })
        }
        box.addView(fxAmt, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        box.addView(tv("⇅", 22f, T.text, true).apply {
            gravity = Gravity.CENTER; background = Glass.make(this@CalcActivity, 24f)
            setOnClickListener { val st = fxState(); val x = st.optString("from"); st.put("from", st.optString("to")); st.put("to", x); fxRender(); save() }
        }, lp(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(12), 0, dp(12)) })
        fxTo = curBtn("to"); box.addView(fxTo)
        fxRes = tv("", 34f, T.text, true).apply { setPadding(dp(4), dp(12), dp(4), 0) }; box.addView(fxRes)
        fxL1 = tv("", 15f, T.muted).apply { setPadding(dp(4), dp(4), dp(4), 0) }; box.addView(fxL1)
        fxL2 = tv("", 15f, T.muted).apply { setPadding(dp(4), dp(4), dp(4), 0) }; box.addView(fxL2)
        fxInfo = tv("", 12f, T.muted).apply { setPadding(dp(4), dp(16), dp(4), 0) }; box.addView(fxInfo)
        sv.addView(box)
        FX = try { JSONObject(getSharedPreferences("ajustes", 0).getString("fxRates", "") ?: "") } catch (_: Exception) { null }
        return sv
    }

    private fun cname(cd: String): String = try { java.util.Currency.getInstance(cd).getDisplayName(L.locale).takeIf { it != cd } ?: cd } catch (_: Exception) { cd }
    private fun flag(cd: String): String {
        val special = mapOf("EUR" to "EU", "ANG" to "CW", "XCD" to "AG", "XAF" to "CM", "XOF" to "SN", "XPF" to "PF", "XDR" to "", "BTC" to "")
        val cc = special[cd] ?: cd.take(2)
        if (cc.length < 2) return "🌐"
        return String(Character.toChars(127397 + cc[0].code)) + String(Character.toChars(127397 + cc[1].code))
    }
    private fun rates(): JSONObject? = FX?.optJSONObject("rates")?.takeIf { it.has("USD") }
    private fun smart(v: Double): String {
        val a = abs(v); val max = if (a >= 1) 2 else if (a >= 0.01) 4 else 6
        val f = java.text.NumberFormat.getNumberInstance(L.locale); f.maximumFractionDigits = max; f.minimumFractionDigits = if (max > 2) 0 else 2
        return f.format(v)
    }
    private fun amt(): Double = fxAmt.text.toString().replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0
    private fun conv(a: Double, f: String, t: String): Double { val r = rates()!!; return a * r.getDouble(t) / r.getDouble(f) }

    private fun fxDefault() {
        val st = fxState()
        if (st.optString("to").isNotEmpty()) return
        val region = java.util.Locale.getDefault().country.uppercase()
        val all = rates()?.keys()?.asSequence()?.toList() ?: POP
        st.put("to", all.firstOrNull { it.take(2) == region && it != "USD" } ?: "EUR")
    }

    private fun fxOpen() {
        fxAmt.setText(fxState().optString("amt", "1")); fxAmt.setSelection(fxAmt.text.length)
        fxRender()
        val t = FX?.optLong("t") ?: 0L
        if ((rates() == null || System.currentTimeMillis() - t > 6 * 3600 * 1000L) && !fxAsked) {
            fxAsked = true
            Thread {
                val got = try {
                    val c = URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 10000
                    val txt = c.inputStream.bufferedReader().readText()
                    val o = JSONObject(txt)
                    if (o.optJSONObject("rates") == null) null else JSONObject().put("rates", o.getJSONObject("rates")).put("t", System.currentTimeMillis())
                } catch (_: Exception) { null }
                runOnUiThread {
                    if (got != null) { FX = got; fxOld = false; getSharedPreferences("ajustes", 0).edit().putString("fxRates", got.toString()).apply() }
                    else if (rates() != null) fxOld = true else fxErr = true
                    if (!isFinishing) fxRender()
                }
            }.start()
        }
    }

    private fun fxRender() {
        if (!::fxFrom.isInitialized) return
        fxDefault()
        val st = fxState(); val f = st.optString("from", "USD"); val t = st.optString("to", "EUR")
        fxFrom.text = "${flag(f)}  $f   ${cname(f)}  ▾"; fxTo.text = "${flag(t)}  $t   ${cname(t)}  ▾"
        val r = rates()
        if (r == null) {
            fxRes.text = ""; fxL1.text = ""; fxL2.text = ""
            fxInfo.text = L.t(if (fxErr) "fxFail" else "fxLoading"); return
        }
        if (!r.has(f) || !r.has(t)) { fxRes.text = "—"; return }
        fxRes.text = smart(conv(amt(), f, t)) + " " + t
        fxL1.text = "1 $f = ${smart(conv(1.0, f, t))} $t"
        fxL2.text = "1 $t = ${smart(conv(1.0, t, f))} $f"
        val d = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, L.locale).format(java.util.Date(FX!!.optLong("t")))
        fxInfo.text = L.t(if (fxOld) "fxOffline" else "fxUpdated", "d" to d) + " · ExchangeRate-API"
    }

    private fun fxText(): String {
        if (rates() == null) return ""
        val st = fxState(); val f = st.optString("from"); val t = st.optString("to")
        val nf = java.text.NumberFormat.getNumberInstance(L.locale).apply { maximumFractionDigits = 6 }
        return nf.format(amt()) + " " + f + " = " + smart(conv(amt(), f, t)) + " " + t
    }

    private fun pickCurrency(which: String) {
        val all = rates()?.keys()?.asSequence()?.toList() ?: POP
        val rest = all.filter { it !in POP }.sortedBy { cname(it).lowercase(L.locale) }
        val full = POP.filter { it in all } + rest
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        val q = EditText(this).apply {
            hint = L.t("fxSearch"); setTextColor(T.text); setHintTextColor(T.faint); isSingleLine = true
            background = roundBg(0x2E000000, dp(14).toFloat(), T.border, dp(1)); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(q)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(list) }
        box.addView(sv, lp(MATCH, dp(420)))
        val dlg = dialog().setView(box).setNegativeButton(L.t("cancel"), null).create()
        val sel = fxState().optString(which)
        fun fill() {
            list.removeAllViews()
            val s = q.text.toString().trim().lowercase()
            for (c in full) {
                if (s.isNotEmpty() && !c.lowercase().contains(s) && !cname(c).lowercase().contains(s)) continue
                list.addView(tv("${flag(c)}   $c   ${cname(c)}", 16f, T.text, c == sel).apply {
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                    if (c == sel) background = roundBg(tw(0x1A), dp(12).toFloat())
                    setOnClickListener { fxState().put(which, c); fxRender(); save(); dlg.dismiss() }
                })
            }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill() }
        })
        fill(); dlg.show()
    }

    /* =========================================================
       ⏱ HORAS DE TRABAJO: entrada y salida de cada día; suma la semana
       ========================================================= */
    private lateinit var hDays: LinearLayout
    private lateinit var hSum: TextView
    private lateinit var hDec: TextView

    private fun hrs(): JSONObject = S.optJSONObject("hrs") ?: JSONObject().also { S.put("hrs", it) }

    private fun buildHrs(): View {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        val tot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = Glass.make(this@CalcActivity, 18f) }
        tot.addView(tv(L.t("hrsTotal"), 13f, T.muted))
        hSum = tv("0 h", 32f, T.text, true); tot.addView(hSum)
        hDec = tv("", 13f, T.muted); tot.addView(hDec)
        box.addView(tot)
        hDays = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(hDays, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(pill("🗑 " + L.t("hrsClear")) { S.put("hrs", JSONObject()); hrsRender(); save(); toast("✓") }.apply { gravity = Gravity.CENTER },
            lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })
        box.addView(tv(L.t("hrsHint"), 12f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), 0) })
        sv.addView(box)
        return sv
    }

    /** Nombre del día: 0 = lunes … 6 = domingo. */
    private fun dayName(i: Int, long: Boolean = false): String {
        val c = java.util.Calendar.getInstance().apply { set(2024, 0, 1 + i) }      // 1 ene 2024 fue lunes
        return java.text.SimpleDateFormat(if (long) "EEEE" else "EEE", L.locale).format(c.time).trimEnd('.').replaceFirstChar { it.uppercase() }
    }
    private fun mins(t: String?): Int? { val m = Regex("^(\\d{1,2}):(\\d{2})").find(t ?: "") ?: return null; return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() }
    private fun dayMins(d: JSONObject?): Int {
        if (d == null) return 0
        val a = mins(d.optString("s")) ?: return 0; val b = mins(d.optString("e")) ?: return 0
        var m = b - a; if (m <= 0) m += 24 * 60                // turno que pasa la medianoche
        m -= maxOf(0, d.optString("b").toIntOrNull() ?: d.optInt("b", 0))
        return maxOf(0, m)
    }
    private fun fmtHM(m: Int): String { val h = m / 60; val r = m % 60; return "$h h" + if (r > 0) " ${if (r < 10) "0" else ""}$r min" else "" }
    private fun fmtT(t: String?): String { val m = mins(t) ?: return ""; return L.time(java.util.Calendar.getInstance().apply { set(2024, 0, 1, m / 60, m % 60) }.timeInMillis) }
    private fun hrsTotal() = (0..6).sumOf { dayMins(hrs().optJSONObject("$it")) }

    private fun hrsUpdate() {
        for (i in 0..6) (hDays.findViewWithTag<TextView>("hr$i"))?.text = dayMins(hrs().optJSONObject("$i")).let { if (it > 0) fmtHM(it) else "—" }
        val t = hrsTotal()
        hSum.text = fmtHM(t)
        hDec.text = if (t > 0) "= " + java.text.NumberFormat.getNumberInstance(L.locale).apply { maximumFractionDigits = 2 }.format(t / 60.0) + " h" else ""
    }

    private fun hrsRender() {
        hDays.removeAllViews()
        for (i in 0..6) {
            val d = hrs().optJSONObject("$i") ?: JSONObject()
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8)); background = roundBg(tw(0x10), dp(16).toFloat(), T.border, dp(1)) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(dayName(i), 15f, T.text, true), lp(dp(48), WRAP))
            fun timeBtn(f: String, label: String) = tv(d.optString(f).let { if (it.isEmpty()) label else fmtT(it) }, 14f, if (d.optString(f).isEmpty()) T.faint else T.text, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); pressBg(tw(0x1A), 12f, T.border)
                setOnClickListener {
                    val cur = mins(d.optString(f)) ?: if (f == "s") 8 * 60 else 17 * 60
                    TimePickerDialog(this@CalcActivity, android.R.style.Theme_Material_Dialog_Alert, { _, h, m ->
                        val o = hrs().optJSONObject("$i") ?: JSONObject().also { hrs().put("$i", it) }
                        o.put(f, String.format(java.util.Locale.US, "%02d:%02d", h, m)); hrsRender(); save()
                    }, cur / 60, cur % 60, L.is24).show()
                }
            }
            row.addView(timeBtn("s", L.t("hrsIn")), lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
            row.addView(timeBtn("e", L.t("hrsOut")), lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
            row.addView(tv("—", 14f, T.text, true).apply { tag = "hr$i"; gravity = Gravity.END }, lp(dp(84), WRAP))
            card.addView(row)
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(48), dp(4), 0, 0) }
            row2.addView(tv("☕ " + L.t("hrsBreak"), 13f, T.muted))
            val brk = EditText(this).apply {
                setText(d.optString("b").takeIf { it != "0" } ?: ""); hint = "0"; setTextColor(T.text); setHintTextColor(T.faint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); inputType = InputType.TYPE_CLASS_NUMBER
                background = roundBg(tw(0x1A), dp(10).toFloat()); setPadding(dp(8), dp(4), dp(8), dp(4))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val o = hrs().optJSONObject("$i") ?: JSONObject().also { hrs().put("$i", it) }
                        o.put("b", s.toString()); hrsUpdate(); save()
                    }
                })
            }
            row2.addView(brk, lp(dp(70), WRAP).apply { marginStart = dp(8) })
            row2.addView(View(this), lp(0, 1, 1f))
            if (d.optString("s").isNotEmpty() || d.optString("e").isNotEmpty())
                row2.addView(tv("✕", 16f, T.muted).apply { setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { hrs().remove("$i"); hrsRender(); save() } })
            card.addView(row2)
            hDays.addView(card, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        hrsUpdate()
    }

    private fun hrsText(): String {
        val lines = (0..6).mapNotNull { i ->
            val d = hrs().optJSONObject("$i"); val m = dayMins(d)
            if (m == 0) null else {
                val b = d!!.optString("b").toIntOrNull() ?: 0
                "${dayName(i, true)}: ${fmtT(d.optString("s"))} – ${fmtT(d.optString("e"))}${if (b > 0) " (☕ $b min)" else ""} = ${fmtHM(m)}"
            }
        }
        if (lines.isEmpty()) return ""
        return "⏱ " + L.t("hrsTab") + "\n" + lines.joinToString("\n") + "\n" + L.t("hrsTotal") + ": " + fmtHM(hrsTotal())
    }

    /* =========================================================
       🔌 CABLE: cuántos pies/metros y cuántas cajas hacen falta
       ========================================================= */
    private fun cbl(): JSONObject = S.optJSONObject("cable") ?: JSONObject().also { S.put("cable", it) }
    private lateinit var cOut: LinearLayout

    private fun buildCable(): View {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
        fun field(key: String, label: String, def: String, decimal: Boolean = true): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(10), dp(8)); background = roundBg(tw(0x10), dp(16).toFloat(), T.border, dp(1)) }
            r.addView(tv(label, 15f, T.text), lp(0, WRAP, 1f))
            val e = EditText(this).apply {
                setText(cbl().optString(key, def)); setTextColor(T.text); setHintTextColor(T.faint); hint = def; gravity = Gravity.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
                background = roundBg(tw(0x1A), dp(10).toFloat()); setPadding(dp(10), dp(6), dp(10), dp(6))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { cbl().put(key, s.toString()); cableRender(); save() }
                })
            }
            r.addView(e, lp(dp(110), WRAP))
            return r
        }
        // pies o metros
        val unitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(3), dp(3), dp(3), dp(3)); background = Glass.make(this@CalcActivity, 18f) }
        fun paintUnit() { for (k in 0 until unitRow.childCount) (unitRow.getChildAt(k) as TextView).let { b -> val on = b.tag == cbl().optString("u", "ft"); b.background = if (on) roundBg(T.text, dp(16).toFloat()) else null; b.setTextColor(if (on) T.onPill else T.text) } }
        for ((id, label) in listOf("ft" to L.t("cblFeet"), "m" to L.t("cblMeters"))) unitRow.addView(tv(label, 14f, T.text, true).apply {
            tag = id; gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { cbl().put("u", id); paintUnit(); cableRender(); save() } }, lp(0, WRAP, 1f))
        box.addView(unitRow, lp(MATCH, WRAP)); paintUnit()
        box.addView(field("n", L.t("cblRuns"), "1", false), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(field("len", L.t("cblLen"), "100"), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(field("extra", L.t("cblExtra"), "10"), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(field("box", L.t("cblBox"), "1000"), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        cOut = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14)); background = Glass.make(this@CalcActivity, 18f) }
        box.addView(cOut, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        box.addView(tv(L.t("cblHint"), 12f, T.muted).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), 0) })
        sv.addView(box)
        cableRender()
        return sv
    }

    private fun cableCalc(): CableMath.Result {
        val c = cbl()
        return CableMath.calc(c.optString("n", "1").toIntOrNull() ?: 0, c.optString("len", "100").replace(',', '.').toDoubleOrNull() ?: 0.0,
            c.optString("u", "ft") == "m", c.optString("extra", "10").replace(',', '.').toDoubleOrNull() ?: 0.0, c.optString("box", "1000").replace(',', '.').toDoubleOrNull() ?: 1000.0)
    }

    private fun cableRender() {
        if (!::cOut.isInitialized) return
        cOut.removeAllViews()
        val r = cableCalc()
        val nf = java.text.NumberFormat.getNumberInstance(L.locale).apply { maximumFractionDigits = 1 }
        cOut.addView(tv(L.t("cblTotal"), 13f, T.muted))
        cOut.addView(tv(nf.format(r.totalFt) + " ft", 30f, T.text, true))
        cOut.addView(tv("= " + nf.format(r.totalM) + " m", 14f, T.muted))
        cOut.addView(View(this).apply { setBackgroundColor(tw(0x1F)) }, lp(MATCH, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        cOut.addView(tv("📦 " + L.t("cblBoxes", "n" to r.boxes, "s" to nf.format(r.boxFt)), 17f, T.text, true))
        cOut.addView(tv("↩ " + L.t("cblLeft", "f" to nf.format(r.leftFt), "m" to nf.format(r.leftFt * 0.3048)), 14f, T.muted).apply { setPadding(0, dp(4), 0, 0) })
    }

    private fun cableText(): String {
        val r = cableCalc(); val c = cbl()
        if (r.totalFt <= 0) return ""
        val nf = java.text.NumberFormat.getNumberInstance(L.locale).apply { maximumFractionDigits = 1 }
        val u = if (c.optString("u", "ft") == "m") "m" else "ft"
        return "🔌 " + L.t("cblTab") + "\n" + "${c.optString("n", "1")} × ${c.optString("len", "100")} $u + ${c.optString("extra", "10")}% = " + nf.format(r.totalFt) + " ft (" + nf.format(r.totalM) + " m)" +
            "\n📦 " + L.t("cblBoxes", "n" to r.boxes, "s" to nf.format(r.boxFt)) + " · ↩ " + L.t("cblLeft", "f" to nf.format(r.leftFt), "m" to nf.format(r.leftFt * 0.3048))
    }
}

/** 🔌 Las cuentas del cable (aparte, para poder probarlas). */
object CableMath {
    class Result(val totalFt: Double, val totalM: Double, val boxes: Int, val boxFt: Double, val leftFt: Double)
    /** [runs] corridas de [len] (en metros si [meters]) + [extraPct]% de sobra; cajas de [boxFt] pies. */
    fun calc(runs: Int, len: Double, meters: Boolean, extraPct: Double, boxFt: Double): Result {
        val lenFt = if (meters) len / 0.3048 else len
        val total = maxOf(0.0, runs * lenFt * (1 + extraPct / 100.0))
        val bx = if (boxFt > 0) Math.ceil(total / boxFt - 1e-9).toInt() else 0
        return Result(total, total * 0.3048, bx, boxFt, maxOf(0.0, bx * boxFt - total))
    }
}
