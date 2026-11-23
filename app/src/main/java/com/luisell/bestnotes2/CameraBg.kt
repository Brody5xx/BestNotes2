package com.luisell.bestnotes2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/* ============================================================
   CameraBg.kt — 📷 Fondo "Cámara en vivo": detrás de la app se
   ve lo que ve la cámara de atrás, en vivo (con una capa oscura
   o clara encima para que se lea la letra). Solo se enciende
   mientras la app está en la pantalla; al salir se apaga.
   No graba ni guarda nada.
   ============================================================ */
class CameraBg(ctx: Context) : FrameLayout(ctx) {
    /**
     * ⚡ SurfaceView (y no TextureView): la cámara se pinta en su propia capa, por fuera de la app.
     * Así la app NO tiene que volver a dibujar las notas 30 veces por segundo → todo se mueve fluido.
     */
    private val sv = SurfaceView(ctx)
    private val dim = View(ctx).apply { setBackgroundColor(if (T.light) 0x80FFFFFF.toInt() else 0x80000000.toInt()) }
    /** La última imagen de la cámara: tapa mientras la cámara llega a esta pantalla (así no parpadea en negro). */
    private val still = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; last?.let { setImageBitmap(it) } }
    private var ready = false

    init {
        isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = true
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
        addView(still, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (last == null) still.setBackgroundColor(0xFF000000.toInt())
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { ready = true; sync() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { ready = false; Hub.release(this@CameraBg) }
        })
    }

    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); sync() }
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has); if (has) sync() }
    override fun onDetachedFromWindow() { Hub.release(this); super.onDetachedFromWindow() }

    /** Si esta pantalla se ve, la cámara se viene para acá; si no, la suelta. */
    private fun sync() {
        val want = isAttachedToWindow && windowVisibility == View.VISIBLE && ready &&
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (want) Hub.claim(this) else Hub.release(this)
    }

    /** Que llene toda la pantalla sin estirarse: la capa de la cámara se hace más grande que la pantalla y se centra. */
    internal fun fit() {
        val sz = Hub.size ?: return
        val vw = width; val vh = height
        if (vw <= 0 || vh <= 0) return
        val portrait = vh > vw
        val cw = if (portrait) sz.height.toFloat() else sz.width.toFloat()
        val ch = if (portrait) sz.width.toFloat() else sz.height.toFloat()
        val s = maxOf(vw / cw, vh / ch)
        val w = (cw * s).toInt(); val h = (ch * s).toInt()
        val lp = sv.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = android.view.Gravity.CENTER; sv.layoutParams = lp }
    }

    /** Cada poquito se guarda una imagen chiquita de lo que se ve (se copia aparte, sin frenar la app). */
    private val keep = object : Runnable { override fun run() {
        if (Hub.owner !== this@CameraBg) return
        grab()
        postDelayed(this, 3000)
    } }
    internal fun grab() {
        if (!ready || sv.width <= 0 || android.os.Build.VERSION.SDK_INT < 24) return
        val bm = android.graphics.Bitmap.createBitmap(maxOf(1, sv.width / 4), maxOf(1, sv.height / 4), android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(sv, bm, { r -> if (r == android.view.PixelCopy.SUCCESS) { last = bm } }, Handler(android.os.Looper.getMainLooper()))
        } catch (_: Throwable) {}
    }
    /** Ya llegan imágenes de la cámara: se quita la tapa suavecito. */
    internal fun live() { post { if (still.alpha > 0f) still.animate().alpha(0f).setDuration(220).start() } }
    internal fun started() { post { fit(); removeCallbacks(keep); postDelayed(keep, 1000) } }
    internal fun stopped() {
        removeCallbacks(keep); grab()
        post { still.animate().cancel(); last?.let { still.setImageBitmap(it) }; still.alpha = 1f }
    }
    internal fun surfaceObj(): Surface? = if (ready && sv.holder.surface.isValid) sv.holder.surface else null
    internal fun setBuffer(sz: Size) { try { sv.holder.setFixedSize(sz.width, sz.height) } catch (_: Exception) {}; post { fit() } }

    /** Lo que se ve ahora mismo (la última imagen guardada de la cámara). */
    fun frame(): android.graphics.Bitmap? = last
    fun stillHidden(h: Boolean) { still.visibility = if (h) View.INVISIBLE else View.VISIBLE }

    /**
     * 🎥 UNA sola cámara para toda la app: se queda prendida y solo cambia de pantalla.
     * (Antes cada pantalla abría su propia cámara y se peleaban: una se quedaba congelada.)
     */
    @SuppressLint("MissingPermission")
    internal object Hub {
        var owner: CameraBg? = null
        var size: Size? = null
        private var cam: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var surf: Surface? = null
        private var thread: HandlerThread? = null
        private var bg: Handler? = null
        private var opening = false
        private var tries = 0
        private var fps: android.util.Range<Int>? = null
        private val main = Handler(android.os.Looper.getMainLooper())
        private val closeLater = Runnable { if (owner == null) shutdown() }

        fun claim(v: CameraBg) {
            main.removeCallbacks(closeLater)
            if (owner === v && session != null) return
            owner?.takeIf { it !== v }?.stopped()
            owner = v
            if (cam == null) open(v.context) else startSession()
        }

        fun release(v: CameraBg) {
            if (owner !== v) return
            v.stopped()
            owner = null
            try { session?.close() } catch (_: Exception) {}
            session = null
            main.removeCallbacks(closeLater); main.postDelayed(closeLater, 700)      // si otra pantalla la pide enseguida, no se apaga
        }

        private fun open(ctx: Context) {
            if (opening) return
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = try { mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: mgr.cameraIdList.firstOrNull() } catch (_: Exception) { null } ?: return
            val map = try { mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) } catch (_: Exception) { null } ?: return
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return
            // ⚡ un tamaño liviano (detrás de una capa oscura no se nota): cerca de 960×540 → menos trabajo, más fluido
            size = sizes.filter { it.width <= 1920 && it.height <= 1080 && it.width * 9 == it.height * 16 }.minByOrNull { Math.abs(it.width * it.height - 960 * 540) }
                ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }.minByOrNull { Math.abs(it.width * it.height - 960 * 540) } ?: sizes.last()
            // ⚡ siempre 30 cuadros por segundo (con poca luz algunos teléfonos bajan a 15 y se ve a saltos)
            fps = try { mgr.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == 30 }?.maxByOrNull { it.lower } } catch (_: Exception) { null }
            if (thread == null) { thread = HandlerThread("camara-fondo").also { it.start() }; bg = Handler(thread!!.looper) }
            opening = true
            try {
                mgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) { main.post { opening = false; tries = 0; cam = d; if (owner != null) startSession() else main.post(closeLater) } }
                    override fun onDisconnected(d: CameraDevice) { main.post { opening = false; d.close(); if (cam === d) cam = null; session = null; retry(ctx) } }
                    override fun onError(d: CameraDevice, e: Int) { main.post { opening = false; d.close(); if (cam === d) cam = null; session = null; retry(ctx) } }
                }, bg)
            } catch (_: Exception) { opening = false; retry(ctx) }
        }

        private fun retry(ctx: Context) { if (owner != null && tries++ < 15) main.postDelayed({ owner?.let { if (cam == null) open(it.context) } }, 300) }

        private fun startSession() {
            val d = cam ?: return
            val v = owner ?: return
            val sz = size ?: return
            try { session?.close() } catch (_: Exception) {}
            session = null
            v.setBuffer(sz)
            val sf = v.surfaceObj() ?: return
            surf = sf
            try {
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(sf), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        main.post {
                            if (cam !== d || owner !== v || surf !== sf) { try { s.close() } catch (_: Exception) {}; return@post }
                            session = s
                            try {
                                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(sf)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                    // ⚡ lo más rápido: sin limpiar ruido ni bordes de más (es solo un fondo)
                                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                }
                                var first = true
                                s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, res: android.hardware.camera2.TotalCaptureResult) {
                                        if (first) { first = false; v.live() }
                                    }
                                }, bg)
                                v.started()
                            } catch (_: Exception) {}
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }, bg)
            } catch (_: Exception) {}
        }

        private fun shutdown() {
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { cam?.close() } catch (_: Exception) {}
            cam = null
            thread?.quitSafely(); thread = null; bg = null
        }
    }

    companion object {
        @Volatile private var last: android.graphics.Bitmap? = null
        /** La última imagen de la cámara (para el fondo de la ventana mientras la pantalla nueva se arma). */
        fun lastFrame(ctx: Context): android.graphics.drawable.Drawable? = last?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it) }
        fun of(a: Activity): CameraBg? { val c = a.findViewById<ViewGroup>(android.R.id.content) ?: return null; return (0 until c.childCount).map { c.getChildAt(it) }.firstOrNull { it is CameraBg } as? CameraBg }
        /** Pone (o quita) la cámara detrás de toda la pantalla de [a]. */
        fun attach(a: Activity) {
            val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
            val has = (0 until content.childCount).map { content.getChildAt(it) }.firstOrNull { it is CameraBg }
            if (Wall.current(a) == "phone" && Translucent.camera(a)) { if (has == null) content.addView(CameraBg(a), 0, ViewGroup.LayoutParams(-1, -1)) }
            else if (has != null) content.removeView(has)
        }
    }
}
