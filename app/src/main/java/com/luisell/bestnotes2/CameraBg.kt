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
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var size: Size? = null
    private var opening = false

    /** La última imagen de la cámara: se ve mientras la cámara vuelve a prender (así no parpadea en negro). */
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
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                if (tex.alpha == 0f) tex.animate().alpha(1f).setDuration(if (last != null) 200 else 500).start()      // aparece suavecito
            }
        }
    }

    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); sync() }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }

    /** Prende la cámara si la app se ve; la apaga si no. */
    private fun sync() {
        val want = isAttachedToWindow && windowVisibility == View.VISIBLE && tex.isAvailable &&
            context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (want) open() else close()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (cam != null || opening) return
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try { mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: mgr.cameraIdList.firstOrNull() } catch (_: Exception) { null } ?: return
        val map = try { mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) } catch (_: Exception) { null } ?: return
        // un tamaño mediano (no hace falta 4K para un fondo): lo más cercano a 1280×720
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return
        size = sizes.filter { it.width <= 1920 && it.height <= 1080 }.minByOrNull { Math.abs(it.width * it.height - 1280 * 720) } ?: sizes.last()
        thread = HandlerThread("camara-fondo").also { it.start() }
        bg = Handler(thread!!.looper)
        opening = true
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { opening = false; cam = d; startPreview() }
                override fun onDisconnected(d: CameraDevice) { opening = false; d.close(); cam = null }
                override fun onError(d: CameraDevice, e: Int) { opening = false; d.close(); cam = null }
            }, bg)
        } catch (_: Exception) { opening = false; stopThread() }
    }

    private fun startPreview() {
        val d = cam ?: return
        val st = tex.surfaceTexture ?: return
        val sz = size ?: return
        st.setDefaultBufferSize(sz.width, sz.height)
        post { fit() }
        val surface = Surface(st)
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cam == null) return
                    session = s
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        s.setRepeatingRequest(req.build(), null, bg)
                    } catch (_: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bg)
        } catch (_: Exception) {}
    }

    /** Que llene toda la pantalla sin estirarse (se recorta lo que sobra, como una foto de fondo). */
    private fun fit() {
        val sz = size ?: return
        val vw = tex.width.toFloat(); val vh = tex.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val portrait = vh > vw
        val cw = if (portrait) sz.height.toFloat() else sz.width.toFloat()
        val ch = if (portrait) sz.width.toFloat() else sz.height.toFloat()
        val s = maxOf(vw / cw, vh / ch)
        val m = Matrix(); m.setScale(cw * s / vw, ch * s / vh, vw / 2f, vh / 2f)
        tex.setTransform(m)
    }

    private fun close() {
        if (cam != null && tex.isAvailable && tex.alpha > 0.5f) try {
            tex.getBitmap(maxOf(1, tex.width / 3), maxOf(1, tex.height / 3))?.let { last = it; still.setImageBitmap(it) }
        } catch (_: Throwable) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cam?.close() } catch (_: Exception) {}
        cam = null
        tex.alpha = 0f
        stopThread()
    }
    private fun stopThread() { thread?.quitSafely(); thread = null; bg = null }

    /** Lo que se ve ahora mismo: la cámara de verdad (la foto normal de la pantalla no la captura). */
    fun frame(): android.graphics.Bitmap? = try { if (tex.isAvailable && tex.alpha > 0.5f) tex.getBitmap() else null } catch (_: Throwable) { null }
    fun stillHidden(h: Boolean) { still.visibility = if (h) View.INVISIBLE else View.VISIBLE }

    companion object {
        @Volatile private var last: android.graphics.Bitmap? = null
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
