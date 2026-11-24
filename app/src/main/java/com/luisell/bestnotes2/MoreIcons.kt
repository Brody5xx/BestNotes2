package com.luisell.bestnotes2

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Íconos nuevos de Best Notes 2, con el mismo estilo que los del teclado
 * (líneas finas con puntas redondeadas). Números desde 200 para no chocar.
 */
object MoreIcons {
    const val PLUS = 200
    const val FOLDER = 201
    const val GRID = 202
    const val LISTV = 203
    const val SORT = 204
    const val REDO = 205
    const val BULLETS = 206
    const val NUMBERS = 207
    const val PALETTE = 208
    const val CHECKBOX = 209
    const val BOLD = 210
    const val ITALIC = 211
    const val UNDERLINE = 212
    const val STRIKE = 213
    const val HILITE = 214
    const val SIZE = 215
    const val IMPORT = 216
    const val RESTORE = 217
    const val INFO = 218
    const val DOTS_V = 219
    const val UNPIN = 220
    const val OUTLINE = 221
    const val STAR5 = 222
    const val STAR5_ON = 223
    const val PEN = 224
    const val MIC = 225
    const val VIDEO = 226
    const val PLAY = 227
    const val PAUSE = 228
    const val ERASER = 229
    const val MARKER = 230
    const val TEXT_T = 231
    const val FULL = 232
    const val MUTE = 233
    const val ATTACH = 234
    const val SHIELD = 235
    const val SUN = 236
    const val MOON = 237
    const val COPY = 238
    const val SCISSORS = 239
    const val HISTORY = 240
    const val WALLPAPER = 241
    const val ROTATE = 242
    const val INBOX = 243
    const val INSERT = 244
    const val QR = 245
    const val ARROW = 246
    const val CIRCLE = 247
    const val RECT = 248
    const val MEASURE = 249
    const val DRIVE = 250
    const val TEXT_SCAN = 251
    const val MOVE = 252

    private val path = Path()
    private val r = RectF()
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun letter(c: Canvas, ch: String, cx: Float, cy: Float, s: Float, color: Int, tf: Typeface) {
        tp.color = color; tp.typeface = tf; tp.textSize = s * 1.75f
        val fm = tp.fontMetrics
        c.drawText(ch, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    fun draw(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, st: Paint, fl: Paint) {
        when (kind) {
            PLUS -> {
                c.drawLine(cx - s * 0.8f, cy, cx + s * 0.8f, cy, st)
                c.drawLine(cx, cy - s * 0.8f, cx, cy + s * 0.8f, st)
            }
            FOLDER -> {
                path.reset()
                path.moveTo(cx - s * 0.95f, cy - s * 0.6f)
                path.lineTo(cx - s * 0.3f, cy - s * 0.6f)
                path.lineTo(cx - s * 0.1f, cy - s * 0.38f)
                path.lineTo(cx + s * 0.95f, cy - s * 0.38f)
                path.lineTo(cx + s * 0.95f, cy + s * 0.7f)
                path.lineTo(cx - s * 0.95f, cy + s * 0.7f)
                path.close()
                c.drawPath(path, st)
            }
            GRID -> {
                val a = s * 0.85f; val g = s * 0.14f
                r.set(cx - a, cy - a, cx - g, cy - g); c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
                r.set(cx + g, cy - a, cx + a, cy - g); c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
                r.set(cx - a, cy + g, cx - g, cy + a); c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
                r.set(cx + g, cy + g, cx + a, cy + a); c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
            }
            LISTV -> {
                val a = s * 0.85f
                r.set(cx - a, cy - a, cx + a, cy - s * 0.1f); c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
                r.set(cx - a, cy + s * 0.1f, cx + a, cy + a); c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
            }
            SORT -> {
                c.drawLine(cx - s * 0.9f, cy - s * 0.6f, cx + s * 0.9f, cy - s * 0.6f, st)
                c.drawLine(cx - s * 0.9f, cy, cx + s * 0.4f, cy, st)
                c.drawLine(cx - s * 0.9f, cy + s * 0.6f, cx - s * 0.1f, cy + s * 0.6f, st)
            }
            REDO -> {
                // la misma flecha de Deshacer, pero al revés (como un espejo)
                c.save(); c.scale(-1f, 1f, cx, cy)
                Icons.draw(c, Icons.UNDO, cx, cy, s, fl.color, 0, android.content.res.Resources.getSystem().displayMetrics.density)
                c.restore()
            }
            RESTORE -> {
                // flecha que da la vuelta hacia atrás (recuperar)
                Icons.draw(c, Icons.UNDO, cx, cy, s, fl.color, 0, android.content.res.Resources.getSystem().displayMetrics.density)
            }
            STAR5 -> {
                // estrella de 5 puntas (favorito); [FILLED] la rellena
                path.reset()
                for (k in 0 until 10) {
                    val rad = if (k % 2 == 0) s * 0.95f else s * 0.42f
                    val a = Math.toRadians((-90 + k * 36).toDouble())
                    val x = cx + (rad * Math.cos(a)).toFloat(); val y = cy + s * 0.08f + (rad * Math.sin(a)).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, st)
            }
            STAR5_ON -> {
                path.reset()
                for (k in 0 until 10) {
                    val rad = if (k % 2 == 0) s * 0.95f else s * 0.42f
                    val a = Math.toRadians((-90 + k * 36).toDouble())
                    val x = cx + (rad * Math.cos(a)).toFloat(); val y = cy + s * 0.08f + (rad * Math.sin(a)).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, fl); c.drawPath(path, st)
            }
            BULLETS, NUMBERS -> {
                for (k in -1..1) {
                    val y = cy + k * s * 0.62f
                    if (kind == BULLETS) c.drawCircle(cx - s * 0.75f, y, s * 0.13f, fl)
                    c.drawLine(cx - s * 0.3f, y, cx + s * 0.95f, y, st)
                }
                if (kind == NUMBERS) {
                    tp.color = fl.color; tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = s * 0.6f
                    for (k in -1..1) c.drawText("${k + 2}", cx - s * 0.72f, cy + k * s * 0.62f + s * 0.21f, tp)
                }
            }
            PALETTE -> {
                c.drawCircle(cx, cy, s * 0.85f, st)
                c.drawCircle(cx - s * 0.35f, cy - s * 0.25f, s * 0.13f, fl)
                c.drawCircle(cx + s * 0.1f, cy - s * 0.45f, s * 0.13f, fl)
                c.drawCircle(cx + s * 0.45f, cy - s * 0.1f, s * 0.13f, fl)
                c.drawCircle(cx + s * 0.05f, cy + s * 0.35f, s * 0.2f, st)
            }
            CHECKBOX -> {
                val a = s * 0.8f
                r.set(cx - a, cy - a, cx + a, cy + a); c.drawRoundRect(r, s * 0.25f, s * 0.25f, st)
                path.reset()
                path.moveTo(cx - s * 0.4f, cy)
                path.lineTo(cx - s * 0.1f, cy + s * 0.32f)
                path.lineTo(cx + s * 0.45f, cy - s * 0.35f)
                c.drawPath(path, st)
            }
            BOLD -> letter(c, "B", cx, cy, s, fl.color, Typeface.DEFAULT_BOLD)
            ITALIC -> letter(c, "I", cx, cy, s, fl.color, Typeface.create(Typeface.SERIF, Typeface.ITALIC))
            UNDERLINE -> {
                letter(c, "U", cx, cy - s * 0.15f, s * 0.85f, fl.color, Typeface.DEFAULT)
                c.drawLine(cx - s * 0.6f, cy + s * 0.85f, cx + s * 0.6f, cy + s * 0.85f, st)
            }
            STRIKE -> {
                letter(c, "S", cx, cy, s, fl.color, Typeface.DEFAULT)
                c.drawLine(cx - s * 0.75f, cy, cx + s * 0.75f, cy, st)
            }
            HILITE -> {
                // marcador inclinado
                path.reset()
                path.moveTo(cx - s * 0.2f, cy + s * 0.45f)
                path.lineTo(cx + s * 0.55f, cy - s * 0.3f)
                path.lineTo(cx + s * 0.2f, cy - s * 0.65f)
                path.lineTo(cx - s * 0.55f, cy + s * 0.1f)
                path.close()
                c.drawPath(path, st)
                c.drawLine(cx - s * 0.9f, cy + s * 0.85f, cx + s * 0.9f, cy + s * 0.85f, st)
            }
            SIZE -> {
                letter(c, "A", cx + s * 0.3f, cy, s * 0.95f, fl.color, Typeface.DEFAULT)
                letter(c, "a", cx - s * 0.55f, cy + s * 0.15f, s * 0.6f, fl.color, Typeface.DEFAULT)
            }
            IMPORT -> {
                c.drawLine(cx, cy - s * 0.9f, cx, cy + s * 0.3f, st)
                c.drawLine(cx - s * 0.4f, cy - s * 0.1f, cx, cy + s * 0.3f, st)
                c.drawLine(cx + s * 0.4f, cy - s * 0.1f, cx, cy + s * 0.3f, st)
                path.reset()
                path.moveTo(cx - s * 0.9f, cy + s * 0.2f)
                path.lineTo(cx - s * 0.9f, cy + s * 0.85f)
                path.lineTo(cx + s * 0.9f, cy + s * 0.85f)
                path.lineTo(cx + s * 0.9f, cy + s * 0.2f)
                c.drawPath(path, st)
            }
            INFO -> {
                c.drawCircle(cx, cy, s * 0.9f, st)
                c.drawLine(cx, cy - s * 0.05f, cx, cy + s * 0.5f, st)
                c.drawCircle(cx, cy - s * 0.42f, s * 0.08f, fl)
            }
            DOTS_V -> {
                for (k in -1..1) c.drawCircle(cx, cy + k * s * 0.6f, s * 0.14f, fl)
            }
            OUTLINE -> {
                // "A" con borde de color por fuera
                tp.typeface = Typeface.DEFAULT_BOLD; tp.textSize = s * 1.75f
                val fm = tp.fontMetrics; val y = cy - (fm.ascent + fm.descent) / 2f
                tp.style = Paint.Style.STROKE; tp.strokeWidth = s * 0.28f; tp.strokeJoin = Paint.Join.ROUND; tp.color = 0xFFFF7A2A.toInt()
                c.drawText("A", cx, y, tp)
                tp.style = Paint.Style.FILL; tp.color = fl.color
                c.drawText("A", cx, y, tp)
            }
            PEN -> Icons.draw(c, Icons.PENCIL, cx, cy, s, fl.color, 0, android.content.res.Resources.getSystem().displayMetrics.density)
            MIC -> Icons.draw(c, Icons.MIC, cx, cy, s, fl.color, 0, android.content.res.Resources.getSystem().displayMetrics.density)
            VIDEO -> {
                r.set(cx - s * 0.95f, cy - s * 0.55f, cx + s * 0.35f, cy + s * 0.55f)
                c.drawRoundRect(r, s * 0.18f, s * 0.18f, st)
                path.reset()
                path.moveTo(cx + s * 0.35f, cy - s * 0.12f); path.lineTo(cx + s * 0.95f, cy - s * 0.45f)
                path.lineTo(cx + s * 0.95f, cy + s * 0.45f); path.lineTo(cx + s * 0.35f, cy + s * 0.12f)
                c.drawPath(path, st)
            }
            PLAY -> {
                path.reset()
                path.moveTo(cx - s * 0.5f, cy - s * 0.8f); path.lineTo(cx + s * 0.8f, cy); path.lineTo(cx - s * 0.5f, cy + s * 0.8f); path.close()
                c.drawPath(path, fl)
            }
            PAUSE -> {
                r.set(cx - s * 0.6f, cy - s * 0.75f, cx - s * 0.15f, cy + s * 0.75f); c.drawRoundRect(r, s * 0.1f, s * 0.1f, fl)
                r.set(cx + s * 0.15f, cy - s * 0.75f, cx + s * 0.6f, cy + s * 0.75f); c.drawRoundRect(r, s * 0.1f, s * 0.1f, fl)
            }
            ERASER -> {
                path.reset()
                path.moveTo(cx - s * 0.9f, cy + s * 0.25f); path.lineTo(cx - s * 0.1f, cy - s * 0.55f)
                path.lineTo(cx + s * 0.6f, cy + s * 0.15f); path.lineTo(cx + s * 0.05f, cy + s * 0.7f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.7f); path.close()
                c.drawPath(path, st)
                c.drawLine(cx - s * 0.5f, cy - s * 0.15f, cx + s * 0.2f, cy + s * 0.55f, st)
                c.drawLine(cx + s * 0.05f, cy + s * 0.7f, cx + s * 0.95f, cy + s * 0.7f, st)
            }
            TEXT_T -> {
                c.drawLine(cx - s * 0.75f, cy - s * 0.7f, cx + s * 0.75f, cy - s * 0.7f, st)
                c.drawLine(cx, cy - s * 0.7f, cx, cy + s * 0.8f, st)
            }
            FULL -> {
                val a = s * 0.8f; val b = s * 0.35f
                for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    c.drawLine(cx + x * a, cy + y * a, cx + x * a, cy + y * (a - b), st)
                    c.drawLine(cx + x * a, cy + y * a, cx + x * (a - b), cy + y * a, st)
                }
            }
            MUTE -> {
                path.reset()
                path.moveTo(cx - s * 0.9f, cy - s * 0.3f); path.lineTo(cx - s * 0.5f, cy - s * 0.3f); path.lineTo(cx - s * 0.05f, cy - s * 0.75f)
                path.lineTo(cx - s * 0.05f, cy + s * 0.75f); path.lineTo(cx - s * 0.5f, cy + s * 0.3f); path.lineTo(cx - s * 0.9f, cy + s * 0.3f); path.close()
                c.drawPath(path, st)
                c.drawLine(cx + s * 0.3f, cy - s * 0.3f, cx + s * 0.9f, cy + s * 0.3f, st)
                c.drawLine(cx + s * 0.9f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f, st)
            }
            SHIELD -> {
                // escudo con candado (la bóveda)
                path.reset()
                path.moveTo(cx, cy - s * 0.95f)
                path.lineTo(cx + s * 0.8f, cy - s * 0.65f)
                path.cubicTo(cx + s * 0.8f, cy + s * 0.15f, cx + s * 0.45f, cy + s * 0.7f, cx, cy + s * 0.98f)
                path.cubicTo(cx - s * 0.45f, cy + s * 0.7f, cx - s * 0.8f, cy + s * 0.15f, cx - s * 0.8f, cy - s * 0.65f)
                path.close()
                c.drawPath(path, st)
                r.set(cx - s * 0.3f, cy - s * 0.05f, cx + s * 0.3f, cy + s * 0.42f)
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, st)
                r.set(cx - s * 0.19f, cy - s * 0.35f, cx + s * 0.19f, cy + s * 0.05f)
                c.drawArc(r, 180f, 180f, false, st)
            }
            SUN -> {
                c.drawCircle(cx, cy, s * 0.38f, st)
                for (k in 0 until 8) {
                    val a = Math.PI * k / 4
                    val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    c.drawLine(cx + ca * s * 0.62f, cy + sa * s * 0.62f, cx + ca * s * 0.92f, cy + sa * s * 0.92f, st)
                }
            }
            MOON -> {
                path.reset()
                r.set(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f)
                path.addCircle(cx, cy, s * 0.8f, Path.Direction.CW)
                val cut = Path().apply { addCircle(cx + s * 0.45f, cy - s * 0.35f, s * 0.62f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, st)
            }
            COPY -> {
                r.set(cx - s * 0.35f, cy - s * 0.45f, cx + s * 0.75f, cy + s * 0.85f)
                c.drawRoundRect(r, s * 0.15f, s * 0.15f, st)
                path.reset()
                path.moveTo(cx - s * 0.6f, cy + s * 0.5f)
                path.lineTo(cx - s * 0.75f, cy + s * 0.5f)
                path.lineTo(cx - s * 0.75f, cy - s * 0.85f)
                path.lineTo(cx + s * 0.35f, cy - s * 0.85f)
                path.lineTo(cx + s * 0.35f, cy - s * 0.7f)
                c.drawPath(path, st)
            }
            SCISSORS -> {
                c.drawCircle(cx - s * 0.5f, cy + s * 0.55f, s * 0.28f, st)
                c.drawCircle(cx + s * 0.5f, cy + s * 0.55f, s * 0.28f, st)
                c.drawLine(cx - s * 0.32f, cy + s * 0.33f, cx + s * 0.55f, cy - s * 0.9f, st)
                c.drawLine(cx + s * 0.32f, cy + s * 0.33f, cx - s * 0.55f, cy - s * 0.9f, st)
            }
            HISTORY -> {
                // reloj con flecha que da la vuelta
                r.set(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f)
                c.drawArc(r, 200f, 290f, false, st)
                path.reset()
                path.moveTo(cx - s * 1.0f, cy - s * 0.55f)
                path.lineTo(cx - s * 0.8f, cy - s * 0.25f)
                path.lineTo(cx - s * 0.48f, cy - s * 0.45f)
                c.drawPath(path, st)
                c.drawLine(cx, cy - s * 0.45f, cx, cy, st)
                c.drawLine(cx, cy, cx + s * 0.32f, cy + s * 0.22f, st)
            }
            WALLPAPER -> {
                // marco con montañitas y esquinas abiertas
                val k = s * 0.9f
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    c.drawLine(cx + sx * k, cy + sy * k, cx + sx * k * 0.45f, cy + sy * k, st)
                    c.drawLine(cx + sx * k, cy + sy * k, cx + sx * k, cy + sy * k * 0.45f, st)
                }
                path.reset()
                path.moveTo(cx - s * 0.55f, cy + s * 0.45f)
                path.lineTo(cx - s * 0.15f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.1f, cy + s * 0.2f)
                path.lineTo(cx + s * 0.3f, cy)
                path.lineTo(cx + s * 0.55f, cy + s * 0.45f)
                c.drawPath(path, st)
                c.drawCircle(cx + s * 0.28f, cy - s * 0.38f, s * 0.11f, fl)
            }
            INSERT -> {
                // ⊞ cuadrito redondeado con un + y una chispita en la esquina (agregar cosas a la nota)
                r.set(cx - s * 0.78f, cy - s * 0.6f, cx + s * 0.62f, cy + s * 0.8f)
                c.drawRoundRect(r, s * 0.32f, s * 0.32f, st)
                c.drawLine(cx - s * 0.08f - s * 0.34f, cy + s * 0.1f, cx - s * 0.08f + s * 0.34f, cy + s * 0.1f, st)
                c.drawLine(cx - s * 0.08f, cy + s * 0.1f - s * 0.34f, cx - s * 0.08f, cy + s * 0.1f + s * 0.34f, st)
                val sx = cx + s * 0.78f; val sy = cy - s * 0.78f; val k = s * 0.3f
                path.reset()
                path.moveTo(sx, sy - k); path.quadTo(sx, sy, sx + k, sy); path.quadTo(sx, sy, sx, sy + k); path.quadTo(sx, sy, sx - k, sy); path.quadTo(sx, sy, sx, sy - k)
                path.close(); c.drawPath(path, fl)
            }
            QR -> {
                // ▦ código QR: tres cuadritos en las esquinas y puntitos
                val a = s * 0.9f; val q = s * 0.62f
                fun corner(x: Float, y: Float) { r.set(x, y, x + q, y + q); c.drawRoundRect(r, s * 0.12f, s * 0.12f, st); r.set(x + q * 0.32f, y + q * 0.32f, x + q * 0.68f, y + q * 0.68f); c.drawRect(r, fl) }
                corner(cx - a, cy - a); corner(cx + a - q, cy - a); corner(cx - a, cy + a - q)
                val d = s * 0.13f
                c.drawCircle(cx + s * 0.25f, cy + s * 0.25f, d, fl); c.drawCircle(cx + s * 0.7f, cy + s * 0.35f, d, fl)
                c.drawCircle(cx + s * 0.35f, cy + s * 0.72f, d, fl); c.drawCircle(cx + s * 0.75f, cy + s * 0.75f, d, fl)
            }
            ARROW -> {
                c.drawLine(cx - s * 0.75f, cy + s * 0.75f, cx + s * 0.75f, cy - s * 0.75f, st)
                c.drawLine(cx + s * 0.75f, cy - s * 0.75f, cx + s * 0.05f, cy - s * 0.75f, st)
                c.drawLine(cx + s * 0.75f, cy - s * 0.75f, cx + s * 0.75f, cy - s * 0.05f, st)
            }
            CIRCLE -> c.drawCircle(cx, cy, s * 0.8f, st)
            RECT -> { r.set(cx - s * 0.85f, cy - s * 0.65f, cx + s * 0.85f, cy + s * 0.65f); c.drawRoundRect(r, s * 0.18f, s * 0.18f, st) }
            MEASURE -> {
                c.drawLine(cx - s * 0.85f, cy + s * 0.2f, cx + s * 0.85f, cy + s * 0.2f, st)
                c.drawLine(cx - s * 0.85f, cy - s * 0.15f, cx - s * 0.85f, cy + s * 0.55f, st)
                c.drawLine(cx + s * 0.85f, cy - s * 0.15f, cx + s * 0.85f, cy + s * 0.55f, st)
                for (k in listOf(-0.42f, 0f, 0.42f)) c.drawLine(cx + s * k, cy + s * 0.2f, cx + s * k, cy - s * 0.12f, st)
            }
            MOVE -> {
                // ✋ mover: una cruz con flechitas en las cuatro puntas
                val k = s * 0.9f; val h = s * 0.28f
                c.drawLine(cx - k, cy, cx + k, cy, st); c.drawLine(cx, cy - k, cx, cy + k, st)
                for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                    val tx = cx + dx * k; val ty = cy + dy * k
                    c.drawLine(tx, ty, tx - dx * h + dy * h, ty - dy * h + dx * h, st)
                    c.drawLine(tx, ty, tx - dx * h - dy * h, ty - dy * h - dx * h, st)
                }
            }
            TEXT_SCAN -> {
                // 🔎 las esquinas de un escáner con una "T" adentro
                val k = s * 0.85f; val e = s * 0.32f
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    c.drawLine(cx + sx * k, cy + sy * k, cx + sx * (k - e), cy + sy * k, st)
                    c.drawLine(cx + sx * k, cy + sy * k, cx + sx * k, cy + sy * (k - e), st)
                }
                c.drawLine(cx - s * 0.38f, cy - s * 0.36f, cx + s * 0.38f, cy - s * 0.36f, st)
                c.drawLine(cx, cy - s * 0.36f, cx, cy + s * 0.42f, st)
            }
            DRIVE -> {
                // ☁️ nubecita con una flecha hacia arriba
                path.reset()
                path.moveTo(cx - s * 0.6f, cy + s * 0.55f)
                path.cubicTo(cx - s * 1.05f, cy + s * 0.55f, cx - s * 1.0f, cy - s * 0.15f, cx - s * 0.55f, cy - s * 0.12f)
                path.cubicTo(cx - s * 0.45f, cy - s * 0.75f, cx + s * 0.45f, cy - s * 0.8f, cx + s * 0.55f, cy - s * 0.15f)
                path.cubicTo(cx + s * 1.0f, cy - s * 0.15f, cx + s * 1.05f, cy + s * 0.55f, cx + s * 0.6f, cy + s * 0.55f)
                c.drawPath(path, st)
                c.drawLine(cx, cy + s * 0.5f, cx, cy - s * 0.15f, st)
                c.drawLine(cx, cy - s * 0.15f, cx - s * 0.25f, cy + s * 0.1f, st); c.drawLine(cx, cy - s * 0.15f, cx + s * 0.25f, cy + s * 0.1f, st)
            }
            INBOX -> {
                // 📥 bandeja (sin carpeta)
                path.reset()
                path.moveTo(cx - s * 0.9f, cy + s * 0.08f); path.lineTo(cx - s * 0.62f, cy - s * 0.62f)
                path.lineTo(cx + s * 0.62f, cy - s * 0.62f); path.lineTo(cx + s * 0.9f, cy + s * 0.08f)
                c.drawPath(path, st)
                r.set(cx - s * 0.9f, cy + s * 0.08f, cx + s * 0.9f, cy + s * 0.72f); c.drawRoundRect(r, s * 0.14f, s * 0.14f, st)
                path.reset()
                path.moveTo(cx - s * 0.9f, cy + s * 0.08f); path.lineTo(cx - s * 0.36f, cy + s * 0.08f)
                path.lineTo(cx - s * 0.24f, cy + s * 0.32f); path.lineTo(cx + s * 0.24f, cy + s * 0.32f)
                path.lineTo(cx + s * 0.36f, cy + s * 0.08f); path.lineTo(cx + s * 0.9f, cy + s * 0.08f)
                c.drawPath(path, st)
            }
            ROTATE -> {
                // ↺ flecha redonda (girar a la izquierda)
                val R = s * 0.72f
                r.set(cx - R, cy - R, cx + R, cy + R)
                c.drawArc(r, 225f, 300f, false, st)
                val th = Math.toRadians(225.0)
                val px = cx + R * Math.cos(th).toFloat(); val py = cy + R * Math.sin(th).toFloat()
                val dx = Math.sin(th).toFloat(); val dy = -Math.cos(th).toFloat()          // hacia donde apunta
                val L = s * 0.42f
                for (sg in intArrayOf(-1, 1)) {
                    val a2 = Math.toRadians(150.0 * sg)
                    val wx = dx * Math.cos(a2).toFloat() - dy * Math.sin(a2).toFloat()
                    val wy = dx * Math.sin(a2).toFloat() + dy * Math.cos(a2).toFloat()
                    c.drawLine(px, py, px + wx * L, py + wy * L, st)
                }
            }
            ATTACH -> {
                // clip
                path.reset()
                path.moveTo(cx + s * 0.45f, cy - s * 0.25f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.45f)
                r.set(cx - s * 0.62f, cy + s * 0.02f, cx - s * 0.02f, cy + s * 0.62f)
                path.arcTo(r, 45f, 180f, false)
                path.lineTo(cx + s * 0.35f, cy - s * 0.75f)
                r.set(cx + s * 0.15f, cy - s * 0.95f, cx + s * 0.75f, cy - s * 0.35f)
                path.arcTo(r, 225f, 180f, false)
                path.lineTo(cx - s * 0.15f, cy + s * 0.2f)
                c.drawPath(path, st)
            }
            UNPIN -> {
                c.drawLine(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, st)
                c.drawCircle(cx, cy - s * 0.2f, s * 0.45f, st)
                c.drawLine(cx, cy + s * 0.25f, cx, cy + s * 0.9f, st)
            }
        }
    }
}
