package com.example.bubbletranslate

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/** Menggambar hasil terjemahan tepat di atas bubble teks aslinya. */
class TranslationOverlay(ctx: Context) : View(ctx) {
    data class Item(val rect: RectF, val text: String)

    private var items: List<Item> = emptyList()
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 255, 255, 255) }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD }

    fun hasItems() = items.isNotEmpty()
    fun clear() { items = emptyList(); invalidate() }
    fun setItems(list: List<Item>) { items = list; invalidate() }

    override fun onDraw(c: Canvas) {
        val loc = IntArray(2); getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val minW = 110 * d
        for (it in items) {
            val r = RectF(it.rect).apply { inset(-6 * d, -4 * d) }
            if (r.width() < minW) { val cx = r.centerX(); r.left = cx - minW / 2; r.right = cx + minW / 2 }
            val inner = (r.width() - 10 * d).toInt().coerceAtLeast(40)
            var size = (r.height() * 0.30f).coerceIn(11 * d, 22 * d)
            val min = 9 * d
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(it.text, 0, it.text.length, tp, inner)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                if (layout.height <= r.height() - 6 * d || size <= min) break
                size -= d
            }
            // kalau masih kepanjangan, tinggi kotak ditambah
            val need = layout.height + 6 * d
            if (need > r.height()) { val extra = (need - r.height()) / 2; r.top -= extra; r.bottom += extra }
            c.drawRoundRect(r, 10 * d, 10 * d, bg)
            c.save()
            c.translate(r.left + 5 * d, r.top + (r.height() - layout.height) / 2f)
            layout.draw(c)
            c.restore()
        }
    }
}
