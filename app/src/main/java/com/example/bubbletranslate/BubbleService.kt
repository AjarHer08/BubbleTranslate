package com.example.bubbletranslate

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class BubbleService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        private const val CH = "bubble_translate"
    }

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var overlay: TranslationOverlay
    private lateinit var overlayLp: WindowManager.LayoutParams
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private val ocr = OcrTranslator()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var autoJob: Job? = null
    private var auto = false
    private var busy = false
    private var scale = 1f
    private var started = false
    private val density get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // Android 14+: startForeground (tipe mediaProjection) HARUS sebelum getMediaProjection
        startFg()
        val data = intent?.let { IntentCompat.getParcelableExtra(it, "data", Intent::class.java) }
        if (started || data == null) { if (!started) stopSelf(); return START_NOT_STICKY }
        started = true

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(intent.getIntExtra("code", Activity.RESULT_CANCELED), data)
        // Android 14+: wajib daftar callback sebelum createVirtualDisplay
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)

        val (rw, rh) = realSize()
        scale = min(1f, 1080f / min(rw, rh))
        capturer = ScreenCapturer(projection!!, (rw * scale).toInt(), (rh * scale).toInt(), resources.displayMetrics.densityDpi)

        setupOverlay(rw, rh)
        setupBubble()
        return START_NOT_STICKY
    }

    // ---------- UI ----------
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun setupOverlay(w: Int, h: Int) {
        overlay = TranslationOverlay(this)
        overlayLp = WindowManager.LayoutParams(
            w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(overlay, overlayLp)
    }

    private fun setupBubble() {
        val size = (56 * density).toInt()
        bubble = TextView(this).apply {
            alpha = 0.92f
            layoutParams = android.view.ViewGroup.LayoutParams(size, size)
        }
        updateRing()
        bubbleLp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (200 * density).toInt() }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false; var longFired = false
            val longRun = Runnable { if (!moved) { longFired = true; onLong() } }
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = bubbleLp.x; sy = bubbleLp.y; tx = e.rawX; ty = e.rawY
                        moved = false; longFired = false; handler.postDelayed(longRun, 600)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - tx; val dy = e.rawY - ty
                        if (!moved && hypot(dx, dy) > 12 * density) { moved = true; handler.removeCallbacks(longRun) }
                        if (moved) {
                            bubbleLp.x = (sx + dx).toInt(); bubbleLp.y = (sy + dy).toInt()
                            wm.updateViewLayout(bubble, bubbleLp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longRun)
                        if (e.action == MotionEvent.ACTION_UP && !moved && !longFired) onTap()
                    }
                }
                return true
            }
        })
        wm.addView(bubble, bubbleLp)
    }

    /** Wajah bubble = gambar pilihan; warna cincin: biru = siaga, hijau = otomatis ON, oranye = sedang menerjemahkan. */
    private fun updateRing() {
        val color = Color.parseColor(if (busy) "#FB8C00" else if (auto) "#43A047" else "#1E88E5")
        val face = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(
            resources, android.graphics.BitmapFactory.decodeResource(resources, R.drawable.bubble_face)
        ).apply { isCircular = true }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke((3 * density).toInt(), color)
        }
        bubble.background = android.graphics.drawable.LayerDrawable(arrayOf(face, ring))
    }

    // ---------- Aksi ----------
    /** Ketuk bubble: nyalakan / matikan mode otomatis. */
    private fun onTap() { setAuto(!auto) }

    /** Tekan lama bubble: terjemahkan ulang halaman saat ini (manual). */
    private fun onLong() {
        if (busy) return
        scope.launch { doTranslate(false) }
    }

    private fun setAuto(on: Boolean) {
        auto = on
        updateRing()
        autoJob?.cancel()
        if (on) {
            toast("Otomatis ON: halaman/chapter baru diterjemahkan sendiri")
            autoJob = scope.launch { autoLoop() }
        } else {
            toast("Otomatis OFF")
            overlay.clear()
        }
    }

    /** Terjemahkan sekarang, lalu pantau layar. Saat layar berubah lalu diam, terjemahkan ulang. */
    private suspend fun autoLoop() {
        while (busy) delay(100)
        translateWithRetry()
        var baseline: IntArray? = null
        var prev: IntArray? = null
        while (currentCoroutineContext().isActive) {
            delay(600)
            if (busy) continue
            val bmp = withContext(Dispatchers.Default) { capturer?.grab() } ?: continue
            val sig = withContext(Dispatchers.Default) { signature(bmp) }
            if (baseline == null || prev == null) { baseline = sig; prev = sig; continue }
            val changed = diff(sig, baseline) > 6.0
            val settled = diff(sig, prev) < 3.0
            prev = sig
            if (changed) {
                if (!settled) {
                    // sedang scroll/pindah halaman: hapus terjemahan lama supaya tidak menempel di tempat salah
                    if (overlay.hasItems()) overlay.clear()
                } else {
                    translateWithRetry()
                    baseline = null; prev = null
                }
            }
        }
    }

    private suspend fun translateWithRetry() {
        var tries = 0
        while (!doTranslate(true) && tries++ < 3) delay(400)
    }

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 24, 48, true)
        val px = IntArray(24 * 48); s.getPixels(px, 0, 24, 0, 0, 24, 48)
        return IntArray(px.size) { val c = px[it]; (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10 }
    }
    private fun diff(a: IntArray, b: IntArray): Double { var t = 0L; for (i in a.indices) t += abs(a[i] - b[i]); return t.toDouble() / a.size }

    /** Return false = layar berubah saat OCR berjalan (hasil basi, tidak digambar) sehingga perlu diulang. */
    private suspend fun doTranslate(checkStale: Boolean): Boolean {
        val cap = capturer ?: return true
        busy = true
        try {
            overlay.clear()
            bubble.visibility = View.INVISIBLE      // supaya bubble & hasil lama tidak ikut di-OCR
            delay(300)
            val bmp = withContext(Dispatchers.Default) { cap.grab() }
            bubble.visibility = View.VISIBLE; updateRing()
            if (bmp == null) { toast("Gagal menangkap layar"); return true }
            val sigBefore = if (checkStale) withContext(Dispatchers.Default) { signature(bmp) } else null

            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            // Abaikan bagian atas (bar status/alamat browser) & bawah supaya tidak ikut diterjemahkan
            val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val sbPx = if (sbId > 0) (resources.getDimensionPixelSize(sbId) * scale).toInt() else 0
            val top = maxOf((bmp.height * prefs.getInt("cropTop", 10) / 100f).toInt(), sbPx)
            val bottom = (bmp.height * prefs.getInt("cropBottom", 0) / 100f).toInt()
            val ch = bmp.height - top - bottom
            val area = if (top >= 0 && ch > 100) Bitmap.createBitmap(bmp, 0, top, bmp.width, ch) else bmp
            val offY = if (area === bmp) 0 else top

            val boxes = ocr.run(area, prefs.getString("src", "auto")!!, prefs.getString("tgt", "id")!!) { toast(it) }

            if (sigBefore != null) {
                val now = withContext(Dispatchers.Default) { cap.grab()?.let { signature(it) } }
                if (now != null && diff(now, sigBefore) > 6.0) return false
            }
            overlay.setItems(boxes.map {
                TranslationOverlay.Item(it.cx / scale, (it.cy + offY) / scale, it.w / scale, it.h / scale, it.angle, it.text)
            })
            if (boxes.isEmpty() && !auto) toast("Tidak ada teks yang perlu diterjemahkan")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Error: ${e.message}")
            return true
        } finally {
            busy = false; bubble.visibility = View.VISIBLE; updateRing()
        }
    }

    // ---------- Util ----------
    private fun realSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!started) return
        val (rw, rh) = realSize()
        scale = min(1f, 1080f / min(rw, rh))
        capturer?.resize((rw * scale).toInt(), (rh * scale).toInt(), resources.displayMetrics.densityDpi)
        overlayLp.width = rw; overlayLp.height = rh
        overlay.clear()
        wm.updateViewLayout(overlay, overlayLp)
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Bubble Translate", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Bubble Translate aktif")
            .setContentText("Ketuk bubble = otomatis ON/OFF • tekan lama = terjemah ulang")
            .setOngoing(true)
            .addAction(0, "Berhenti", stop)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        autoJob?.cancel(); scope.cancel()
        if (started) {
            try { wm.removeView(bubble) } catch (_: Exception) {}
            try { wm.removeView(overlay) } catch (_: Exception) {}
            capturer?.release()
            try { projection?.stop() } catch (_: Exception) {}
        }
        ocr.close()
        super.onDestroy()
    }
}
