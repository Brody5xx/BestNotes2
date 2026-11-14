package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/* ============================================================
   RichText.kt — el texto con estilo (negrita, colores, tamaños…)
   dentro de las cajas de texto nativas de Android.
   Solo se guardan NUESTROS estilos (las clases de abajo); así lo
   que pone el teclado mientras escribes no se cuela en la nota.
   ============================================================ */

/** Marca para saber que un estilo es nuestro. */
interface Ours
class BoldS : StyleSpan(Typeface.BOLD), Ours
class ItalS : StyleSpan(Typeface.ITALIC), Ours
class UnderS : UnderlineSpan(), Ours
class StrikeS : StrikethroughSpan(), Ours
class SizeS(size: Int) : AbsoluteSizeSpan(size, true), Ours
class ColorS(c: Int) : ForegroundColorSpan(c), Ours
class BgS(c: Int) : BackgroundColorSpan(c), Ours
class LinkS(url: String) : URLSpan(url), Ours
/** Iluminado de la búsqueda (no se guarda en la nota). */
class SearchS : BackgroundColorSpan(T.search)

object Rich {
    fun build(runs: List<Run>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (r in runs) {
            val a = sb.length
            sb.append(r.text)
            setStyle(sb, a, sb.length, r.st)
        }
        return sb
    }

    private fun setStyle(sp: android.text.Spannable, a: Int, b: Int, st: St) {
        if (a >= b || st.isPlain()) return
        val f = Spanned.SPAN_EXCLUSIVE_INCLUSIVE
        if (st.b) sp.setSpan(BoldS(), a, b, f)
        if (st.i) sp.setSpan(ItalS(), a, b, f)
        if (st.u) sp.setSpan(UnderS(), a, b, f)
        if (st.s) sp.setSpan(StrikeS(), a, b, f)
        if (st.size > 0) sp.setSpan(SizeS(st.size), a, b, f)
        if (st.color != 0) sp.setSpan(ColorS(st.color), a, b, f)
        if (st.bg != 0) sp.setSpan(BgS(st.bg), a, b, f)
        if (st.href != null) sp.setSpan(LinkS(st.href), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun styleOf(spans: Array<Ours>): St {
        var s = St.PLAIN
        for (x in spans) s = when (x) {
            is BoldS -> s.copy(b = true)
            is ItalS -> s.copy(i = true)
            is UnderS -> s.copy(u = true)
            is StrikeS -> s.copy(s = true)
            is SizeS -> s.copy(size = x.size)
            is ColorS -> s.copy(color = x.foregroundColor)
            is BgS -> s.copy(bg = x.backgroundColor)
            is LinkS -> s.copy(href = x.url)
            else -> s
        }
        return s
    }

    /** El texto (con nuestros estilos) de [a] a [b]. */
    fun runs(sp: Spanned, a: Int = 0, b: Int = sp.length): MutableList<Run> {
        val out = ArrayList<Run>()
        var i = a
        while (i < b) {
            val next = sp.nextSpanTransition(i, b, Ours::class.java)
            val all = sp.getSpans(i, next, Ours::class.java)
            val cover = all.filter { sp.getSpanStart(it) <= i && sp.getSpanEnd(it) >= next && sp.getSpanStart(it) < sp.getSpanEnd(it) }.toTypedArray()
            out.add(Run(sp.subSequence(i, next).toString(), styleOf(cover)))
            i = next
        }
        return mergeRuns(out)
    }

    /** Vuelve a poner los estilos de toda la caja según [runs] (el texto debe ser el mismo). */
    fun restyle(e: Editable, runs: List<Run>) {
        e.getSpans(0, e.length, Ours::class.java).forEach { e.removeSpan(it) }
        var i = 0
        for (r in runs) { setStyle(e, i, minOf(e.length, i + r.text.length), r.st); i += r.text.length }
    }

    /** Aplica [fn] al estilo del texto entre [a] y [b]. */
    fun mapRange(e: Editable, a: Int, b: Int, fn: (St) -> St) {
        if (a >= b) return
        val all = runs(e)
        val mid = sliceRuns(all, a, b).map { Run(it.text, fn(it.st)) }
        restyle(e, mergeRuns(sliceRuns(all, 0, a) + mid + sliceRuns(all, b, e.length)))
    }

    /** Estilo en el cursor: el de la letra de antes (o el de después al empezar un renglón). */
    fun styleAt(e: Spanned, pos: Int): St {
        val p = if (pos > 0 && e[pos - 1] != '\n') pos - 1 else if (pos < e.length && e[pos] != '\n') pos else -1
        if (p < 0) return St.PLAIN
        val sp = e.getSpans(p, p + 1, Ours::class.java).filter { e.getSpanStart(it) <= p && e.getSpanEnd(it) > p }
        return styleOf(sp.toTypedArray())
    }

    /** Ilumina [q] en violeta (búsqueda). Devuelve dónde quedó la primera. */
    fun highlight(e: Editable, q: String?): Int {
        e.getSpans(0, e.length, SearchS::class.java).forEach { e.removeSpan(it) }
        if (q.isNullOrBlank()) return -1
        val found = findAll(e.toString(), q)
        found.forEach { e.setSpan(SearchS(), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return found.firstOrNull()?.first ?: -1
    }
}

/**
 * Caja de texto con estilos. Avisa cuando:
 *  - cambia el texto ([onEdit]),
 *  - se mueve el cursor ([onCursor]) — para encender los botones B, I, U…,
 *  - se borra estando al principio ([onBackAtStart]),
 *  - se presiona Enter ([onEnter], si devuelve true se encarga él).
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class RichEdit(ctx: Context) : EditText(ctx) {
    var onEdit: (() -> Unit)? = null
    var onCursor: ((RichEdit) -> Unit)? = null
    var onBackAtStart: ((RichEdit) -> Boolean)? = null
    var onEnter: ((RichEdit, Int) -> Boolean)? = null

    /** Estilo para lo próximo que escribas (cuando tocas B sin seleccionar nada). */
    private var pending: ((St) -> St)? = null
    private var pendA = -1
    private var pendB = -1
    private var muting = false

    init {
        background = null
        setTextColor(T.text)
        setHintTextColor(T.faint)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, T.baseSize.toFloat())
        setLineSpacing(0f, 1.18f)
        gravity = Gravity.TOP or Gravity.START
        setPadding(0, dp(2), 0, dp(2))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isVerticalScrollBarEnabled = false
        highlightColor = 0x558B7CFF
        addTextChangedListener(object : TextWatcher {
            var enterAt = -1
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (muting) return
                enterAt = if (count == before + 1 && s[start + count - 1] == '\n') start + count - 1 else -1
                val fn = pending
                if (fn != null && count > 0) {
                    pendB = maxOf(pendB, start + count)
                    pendA = minOf(pendA, start)
                    pendingRange = start to start + count
                } else pendingRange = null
            }
            var pendingRange: Pair<Int, Int>? = null
            override fun afterTextChanged(s: Editable) {
                if (muting) return
                pendingRange?.let { (a, b) -> pending?.let { fn -> muting = true; Rich.mapRange(s, a, minOf(b, s.length), fn); muting = false } }
                pendingRange = null
                val ea = enterAt; enterAt = -1
                if (ea >= 0 && ea < s.length && s[ea] == '\n' && onEnter != null) {
                    post { if (ea < text.length && text[ea] == '\n') onEnter?.invoke(this@RichEdit, ea) }
                }
                onEdit?.invoke()
            }
        })
    }

    /** Cambia el texto sin avisar que "se editó" (al abrir la nota o deshacer). */
    fun setRuns(runs: List<Run>) {
        muting = true
        setText(Rich.build(runs), BufferType.EDITABLE)
        muting = false
    }

    fun runs(): MutableList<Run> = Rich.runs(text)

    /** Aplica un estilo: a lo seleccionado, o a lo que escribas después si no hay nada seleccionado. */
    fun applyStyle(fn: (St) -> St) {
        val a = selectionStart; val b = selectionEnd
        if (a < 0) return
        if (a != b) {
            muting = true
            Rich.mapRange(text, minOf(a, b), maxOf(a, b), fn)
            muting = false
            onEdit?.invoke()
        } else {
            val old = pending
            pending = if (old == null) fn else { s -> fn(old(s)) }
            pendA = a; pendB = a
        }
        onCursor?.invoke(this)
    }

    /** El estilo que tendrá lo próximo que escribas (para mostrar los botones encendidos). */
    fun currentStyle(): St {
        val a = selectionStart
        if (a < 0) return St.PLAIN
        val base = Rich.styleAt(text, a)
        return pending?.invoke(base) ?: base
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (pending != null && (selStart != selEnd || selStart < pendA || selStart > pendB)) { pending = null }
        onCursor?.invoke(this)
    }

    /** Se detecta "borrar" al principio de la caja con cualquier teclado (también el tuyo). */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && selectionStart == 0 && selectionEnd == 0 && atStart()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0) {
                    if (event.action == KeyEvent.ACTION_DOWN) atStart()
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0 && atStart()) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun atStart(): Boolean = onBackAtStart?.invoke(this) ?: false

    /** Copiar y pegar: se pega solo el texto (sin estilos raros de otras apps). */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste) return super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        return super.onTextContextMenuItem(id)
    }
}
