package com.example.bubbletranslate

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/** Menggambar hasil terjemahan tepat di atas teks aslinya (mendukung teks miring/vertikal). */
class TranslationOverlay(ctx: Context) : View(ctx) {
    data class Item(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float, val text: String)

    private var items: List<Item> = emptyList()
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD }

    fun hasItems() = items.isNotEmpty()
    fun clear() { items = emptyList(); invalidate() }
    fun setItems(list: List<Item>) { items = list; invalidate() }

    override fun onDraw(c: Canvas) {
        val loc = IntArray(2); getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val pad = 5 * d
        val minW = 110 * d
        for (item in items) {
            var cx = item.cx; val cy = item.cy
            var w = item.w + 2 * pad
            var h = item.h + 2 * pad
            if (item.angle == 0f) {
                // teks horizontal/vertikal-CJK: lebarkan secukupnya, tapi jangan keluar layar
                if (w < minW) w = minW
                if (w > width) w = width.toFloat()
                cx = cx.coerceIn(loc[0] + w / 2, loc[0] + width - w / 2)
            }
            val inner = (w - 2 * pad).toInt().coerceAtLeast(40)
            var size = (h * 0.30f).coerceIn(11 * d, 22 * d)
            val min = 9 * d
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, inner)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                if (layout.height <= h - 2 * pad || size <= min) break
                size -= d
            }
            if (layout.height + 2 * pad > h) h = layout.height + 2 * pad
            c.save()
            c.translate(cx, cy)
            c.rotate(item.angle)
            c.drawRoundRect(-w / 2, -h / 2, w / 2, h / 2, 10 * d, 10 * d, bg)
            c.translate(-w / 2 + pad, -layout.height / 2f)
            layout.draw(c)
            c.restore()
        }
    }
}
