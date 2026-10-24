package vn.scamshield
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/** Rung + viền màn hình: level 1 = vàng, 2 = đỏ. */
object Alerter {
    fun fire(c: Context, level: Int) { buzz(c, level); Overlay.show(c, level) }
    @Suppress("DEPRECATION")
    private fun buzz(c: Context, level: Int) {
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pat = if (level >= 2) longArrayOf(0, 700, 200, 700, 200, 1000) else longArrayOf(0, 250, 150, 250)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pat, -1)) else v.vibrate(pat, -1)
    }
}

object Overlay {
    private var view: View? = null
    private val h = Handler(Looper.getMainLooper())
    @Suppress("DEPRECATION")
    fun show(c: Context, level: Int) {
        if (!Settings.canDrawOverlays(c)) return
        val ctx = c.applicationContext
        h.post {
            hide(ctx)
            val lp = WindowManager.LayoutParams(-1, -1,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            val v = BorderView(ctx, if (level >= 2) 0xFFFF5A5F.toInt() else 0xFFFFC94D.toInt(), level >= 2)
            try { ctx.getSystemService(WindowManager::class.java).addView(v, lp); view = v } catch (_: Exception) {}
            h.postDelayed({ hide(ctx) }, if (level >= 2) 30000L else 10000L)
        }
    }
    fun hide(c: Context) {
        h.removeCallbacksAndMessages(null)
        view?.let { try { c.applicationContext.getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        view = null
    }
}

class BorderView(c: Context, col: Int, fast: Boolean) : View(c) {
    private val d = resources.displayMetrics.density
    private val layers = 20                 // nhiều lớp mảnh chồng lên nhau -> viền mờ dần vào trong, không còn cứng
    private val step = 3f * d
    private val radius = 32f * d            // bo góc gần giống góc màn hình
    private val cr = Color.red(col); private val cg = Color.green(col); private val cb = Color.blue(col)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = step + 0.8f * d }
    private val rect = RectF()
    private var k = 1f
    private val anim = ValueAnimator.ofFloat(0.55f, 1f).apply {
        duration = if (fast) 750 else 1500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { k = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        for (i in 0 until layers) {
            val t = i / (layers - 1f)
            val a = (1f - t) * (1f - t) * 0.72f * k          // đậm ở mép, nhạt dần (đường cong bậc hai)
            paint.color = Color.argb((a * 255).toInt(), cr, cg, cb)
            val inset = step * (i + 0.5f)
            rect.set(inset, inset, width - inset, height - inset)
            val r = maxOf(radius - inset * 0.6f, 8f * d)
            cv.drawRoundRect(rect, r, r, paint)
        }
    }
}
