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
import android.view.TextureView
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
    private val tex = TextureView(ctx)
    private val dim = View(ctx).apply { setBackgroundColor(if (T.light) 0x80FFFFFF.toInt() else 0x80000000.toInt()) }
    /** La última imagen de la cámara: se ve mientras la cámara pasa a esta pantalla (así no parpadea en negro). */
    private val still = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; last?.let { setImageBitmap(it) } }

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(still, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(tex, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tex.alpha = 0f
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { sync() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { fit() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { Hub.release(this@CameraBg); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                if (tex.alpha == 0f) tex.animate().alpha(1f).setDuration(if (last != null) 200 else 500).start()      // aparece suavecito
            }
        }
    }

    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); sync() }
    override fun onWindowFocusChanged(has: Boolean) { super.onWindowFocusChanged(has); if (has) sync() }
    override fun onDetachedFromWindow() { Hub.release(this); super.onDetachedFromWindow() }

    /** Si esta pantalla se ve, la cámara se viene para acá; si no, la suelta. */
    private fun sync() {
        val want = isAttachedToWindow && windowVisibility == View.VISIBLE && tex.isAvailable &&
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (want) Hub.claim(this) else Hub.release(this)
    }

    /** Que llene toda la pantalla sin estirarse (se recorta lo que sobra, como una foto de fondo). */
    internal fun fit() {
        val sz = Hub.size ?: return
        val vw = tex.width.toFloat(); val vh = tex.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val portrait = vh > vw
        val cw = if (portrait) sz.height.toFloat() else sz.width.toFloat()
        val ch = if (portrait) sz.width.toFloat() else sz.height.toFloat()
        val s = maxOf(vw / cw, vh / ch)
        val m = Matrix(); m.setScale(cw * s / vw, ch * s / vh, vw / 2f, vh / 2f)
        tex.setTransform(m)
    }

    /** Cada poquito se guarda una imagen chiquita de lo que se ve (para que al cambiar de pantalla nunca se vea negro). */
    private val keep = object : Runnable { override fun run() {
        if (Hub.owner !== this@CameraBg) return
        grab()
        postDelayed(this, 4000)
    } }
    internal fun grab() {
        if (tex.isAvailable && tex.alpha > 0.5f) try { tex.getBitmap(maxOf(1, tex.width / 4), maxOf(1, tex.height / 4))?.let { last = it; still.setImageBitmap(it) } } catch (_: Throwable) {}
    }
    internal fun started() { post { removeCallbacks(keep); postDelayed(keep, 800); fit() } }
    internal fun stopped() { removeCallbacks(keep); grab(); post { tex.alpha = 0f } }
    internal fun surface(): SurfaceTexture? = if (tex.isAvailable) tex.surfaceTexture else null

    /** Lo que se ve ahora mismo: la cámara de verdad (la foto normal de la pantalla no la captura). */
    fun frame(): android.graphics.Bitmap? = try { if (tex.isAvailable && tex.alpha > 0.5f) tex.getBitmap() else null } catch (_: Throwable) { null }
    /** Lo que se ve, en chiquito (para leer códigos QR). */
    fun frame(w: Int, h: Int): android.graphics.Bitmap? = try { if (tex.isAvailable && tex.alpha > 0.5f) tex.getBitmap(w, h) else null } catch (_: Throwable) { null }
    /** Sin la capa oscura (para el lector de QR). */
    fun clear() { dim.visibility = View.GONE }
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
            val st = v.surface() ?: return
            val sz = size ?: return
            try { session?.close() } catch (_: Exception) {}
            session = null
            st.setDefaultBufferSize(sz.width, sz.height)
            val sf = Surface(st); surf = sf
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
                                s.setRepeatingRequest(req.build(), null, bg)
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
            if (a !is LockActivity && Wall.current(a) == "phone" && Translucent.camera(a) && !MainActivity.sharesCamera(a)) { if (has == null) content.addView(CameraBg(a), 0, ViewGroup.LayoutParams(-1, -1)) }
            else if (has != null) content.removeView(has)
        }
    }
}
