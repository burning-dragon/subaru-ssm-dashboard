package kg.forester.sg.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kg.forester.sg.data.Repo
import kg.forester.sg.data.ScreenCfg
import kg.forester.sg.data.ScreenLayout
import kg.forester.sg.data.ScreenModel
import kg.forester.sg.data.ScreenPage
import kg.forester.sg.data.SensorBox
import kg.forester.sg.data.UsbScreen

/**
 * The colour screen's pages drawn into a bitmap, outside any window (the service sends them while the app is in the
 * background): the same gauge designs as the cards ([drawDial]), tiles in the app's colours, values from [Metrics].
 */
object ScreenRenderer {
    private var segment: Typeface? = null

    /** This moment's values, for drawing outside composition. */
    fun ctxNow() = MetricCtx(Repo.live.value, Repo.tcu.value, Repo.trip.value, Repo.gps.value, Repo.guard.value, Repo.rrValues.value, SensorBox.readings.value, kg.forester.sg.data.Wideband.state.value, android.os.SystemClock.elapsedRealtime())

    /** Everything a page can show: the built-in values, the ticked «Датчики» parameters, the box inputs. */
    fun catalogue(): List<Indicator> = Metrics.all(Repo.rrAvailable.value, Repo.settings.watchIds)

    fun indicator(id: String): Indicator? = Metrics.of(id, Repo.rrAvailable.value)

    /** The page that should be on now: the chosen one, or the first page showing a value past its warning. */
    fun pageToShow(cfg: ScreenCfg, chosen: Int, m: MetricCtx): Int {
        if (cfg.alert) cfg.pages.forEachIndexed { i, p ->
            if (p.items.any { id -> indicator(id)?.let { it.warn(it.read(m)) } == true }) return i
        }
        return chosen.coerceIn(0, cfg.pages.size - 1)
    }

    /** [page] upright, [w] x [h] (the panel turned as set); [round] masks it to the circle. */
    fun render(ctx: Context, page: ScreenPage, w: Int, h: Int, round: Boolean, m: MetricCtx = ctxNow()): Bitmap {
        if (segment == null) segment = runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, kg.forester.sg.R.font.dseg7_bold_italic) }.getOrNull()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(bmp.asImageBitmap())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.Black)
            val items = page.items.map { id -> indicator(id)?.let { it to it.read(m) } }
            // a round panel: the tiles and lists inside the square that fits in the circle
            val inner = if (round) Rect(Offset(w / 2f, h / 2f), minOf(w, h) * 0.354f) else Rect(0f, 0f, w.toFloat(), h.toFloat())
            val full = Rect(0f, 0f, w.toFloat(), h.toFloat())
            val r = when {
                page.layout == ScreenLayout.GAUGE -> full
                // two dials in a circle: each as wide as its radius, a little in from the glass edge
                page.layout == ScreenLayout.GAUGE2 -> if (round) full.deflate(minOf(w, h) * 0.03f) else full
                else -> inner
            }
            layout(ctx, page, items, r, round)
            if (round) {
                // outside the glass stays black
                val path = androidx.compose.ui.graphics.Path().apply {
                    addRect(full)
                    addOval(Rect(Offset(w / 2f, h / 2f), minOf(w, h) / 2f))
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                }
                drawPath(path, Color.Black)
            }
        }
        return bmp
    }

    /** [b] turned [quarter] quarter turns clockwise: from upright to how the panel stands. */
    fun toPanel(b: Bitmap, quarter: Int): Bitmap {
        if (quarter % 4 == 0) return b
        val mtx = android.graphics.Matrix().apply { postRotate(90f * (quarter % 4)) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, mtx, false)
    }

    private fun DrawScope.layout(ctx: Context, page: ScreenPage, items: List<Pair<Indicator, Float?>?>, r: Rect, round: Boolean) {
        val wide = r.width >= r.height
        fun cell(x: Float, y: Float, cw: Float, ch: Float) = Rect(r.left + x, r.top + y, r.left + x + cw, r.top + y + ch)
        val gap = minOf(r.width, r.height) * 0.025f
        when (page.layout) {
            ScreenLayout.GAUGE -> gauge(ctx, page.style, items.getOrNull(0), r)
            ScreenLayout.GAUGE2 -> if (wide) {
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, r.width / 2, r.height))
                gauge(ctx, page.style, items.getOrNull(1), cell(r.width / 2, 0f, r.width / 2, r.height))
            } else {
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, r.width, r.height / 2))
                gauge(ctx, page.style, items.getOrNull(1), cell(0f, r.height / 2, r.width, r.height / 2))
            }
            ScreenLayout.GAUGE_TILES -> if (wide) {
                val gw = minOf(r.height, r.width * 0.55f)
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, gw, r.height))
                tile(items.getOrNull(1), cell(gw + gap, 0f, r.width - gw - gap, r.height / 2 - gap / 2))
                tile(items.getOrNull(2), cell(gw + gap, r.height / 2 + gap / 2, r.width - gw - gap, r.height / 2 - gap / 2))
            } else {
                val gh = minOf(r.width, r.height * 0.6f)
                gauge(ctx, page.style, items.getOrNull(0), cell(0f, 0f, r.width, gh))
                tile(items.getOrNull(1), cell(0f, gh + gap, r.width / 2 - gap / 2, r.height - gh - gap))
                tile(items.getOrNull(2), cell(r.width / 2 + gap / 2, gh + gap, r.width / 2 - gap / 2, r.height - gh - gap))
            }
            ScreenLayout.TILES4, ScreenLayout.TILES6 -> {
                val n = page.layout.slots
                val cols = if (n == 4) 2 else if (wide) 3 else 2
                val rows = n / cols
                val cw = (r.width - gap * (cols - 1)) / cols
                val ch = (r.height - gap * (rows - 1)) / rows
                for (i in 0 until n) tile(items.getOrNull(i), cell((i % cols) * (cw + gap), (i / cols) * (ch + gap), cw, ch))
            }
            ScreenLayout.BIG -> big(items.getOrNull(0), r)
            ScreenLayout.LIST -> list(items, r)
        }
    }

    private fun paint(size: Float, color: Color, bold: Boolean = true, align: Paint.Align = Paint.Align.LEFT, face: Typeface? = null) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color.toArgb()
            textAlign = align
            typeface = face ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** A number must never lose digits: its paint made smaller until [t] fits [maxW]. */
    private fun shrink(t: String, p: Paint, maxW: Float): String {
        while (p.measureText(t) > maxW && p.textSize > 6f) p.textSize *= 0.92f
        return t
    }

    /** [t] (a name) cut to fit [maxW] with its paint. */
    private fun fit(t: String, p: Paint, maxW: Float): String {
        var s = t
        while (s.length > 1 && p.measureText(s) > maxW) s = s.dropLast(1)
        return s
    }

    private fun DrawScope.text(t: String, x: Float, y: Float, p: Paint) = drawContext.canvas.nativeCanvas.drawText(t, x, y, p)

    /** A round gauge of one value in [style], as on the cards: the dial, the number in the middle, the name under it. */
    private fun DrawScope.gauge(ctx: Context, style: String, it: Pair<Indicator, Float?>?, r: Rect) {
        val side = minOf(r.width, r.height)
        val l = r.left + (r.width - side) / 2
        val t = r.top + (r.height - side) / 2
        if (it == null) return
        val (ind, v) = it
        val st = dialStyle(style)
        val (lo, hi, step) = niceScale(ind.min, ind.max)
        val div = if (hi >= 1000f) 1000f else 1f
        val photo = st.image?.let { name -> kg.forester.sg.data.GaugeImages.bitmap(ctx, name)?.asImageBitmap() }
        inset(l, t, size.width - l - side, size.height - t - side) {
            val ro = st.readout
            val at = (v ?: lo).coerceIn(lo, hi)
            drawDial(at, lo, hi, step, div, ind.warnAbove, st, photo, layer = if (ro != null && !st.hideValue) DialLayer.FACE else DialLayer.ALL)
            val c = center
            val hot = ind.warn(v)
            if (!st.hideValue) {
                if (ro != null) {
                    val bw = side * 0.44f
                    val bh = bw * 0.38f
                    val top = c.y + side * 0.2f
                    drawRoundRect(ro.bg, Offset(c.x - bw / 2, top), Size(bw, bh), CornerRadius(bw * 0.08f))
                    drawRoundRect(ro.border, Offset(c.x - bw / 2, top), Size(bw, bh), CornerRadius(bw * 0.08f), style = Stroke(2f))
                    val p = paint(bh * 0.62f, if (hot) C.Red else ro.fg, align = Paint.Align.RIGHT, face = if (ro.segments) segment else null)
                    text(ind.text(v).replace('—', '-'), c.x + bw / 2 - bw * 0.06f, top + bh * 0.78f, p)
                    // the needle passes over the read-out window, as on the cards
                    drawDial(at, lo, hi, step, div, ind.warnAbove, st, photo, layer = DialLayer.NEEDLE)
                } else {
                    val p = paint(side * 0.13f, if (hot) C.Red else st.value, align = Paint.Align.CENTER)
                    text(shrink(ind.text(v), p, side * 0.34f), c.x, c.y + side * 0.2f, p)
                }
            }
            // the name and the unit in the opening at the bottom of the scale
            val unit = if (div > 1f) "×1000 ${ind.unit}" else ind.unit
            if (ro != null && !st.hideValue) {
                // under the read-out window: one line
                val np = paint(side * 0.042f, st.unit, false, Paint.Align.CENTER)
                text(fit("${ind.short} $unit", np, side * 0.42f), c.x, c.y + side * 0.43f, np)
            } else {
                val np = paint(side * 0.05f, st.unit, false, Paint.Align.CENTER)
                text(fit(ind.short, np, side * 0.36f), c.x, c.y + side * 0.36f, np)
                val up = paint(side * 0.042f, st.unit.copy(alpha = 0.7f), false, Paint.Align.CENTER)
                text(fit(unit, up, side * 0.3f), c.x, c.y + side * 0.42f, up)
            }
        }
    }

    /** A tile as on the home screen: the name, the number (red past its warning), the unit and a bar of the scale. */
    private fun DrawScope.tile(it: Pair<Indicator, Float?>?, r: Rect) {
        val rad = minOf(r.width, r.height) * 0.1f
        drawRoundRect(C.Card, r.topLeft, r.size, CornerRadius(rad))
        drawRoundRect(C.Border, r.topLeft, r.size, CornerRadius(rad), style = Stroke(2f))
        if (it == null) return
        val (ind, v) = it
        val hot = ind.warn(v)
        val pad = minOf(r.width, r.height) * 0.1f
        val avail = r.width - pad * 2
        val num = paint(r.height * 0.36f, if (hot) C.Red else C.Text)
        val unit = paint(r.height * 0.13f, C.Sub, false)
        val vt = ind.text(v)
        // a narrow tile: the unit goes under the number, the number keeps its size
        val inline = num.measureText(vt) + unit.measureText(ind.unit) + pad * 0.4f <= avail
        val name = paint(r.height * 0.15f, C.Sub, false)
        val title = ind.short
        while (name.measureText(title) > avail && name.textSize > r.height * 0.11f) name.textSize *= 0.94f
        text(fit(title, name, avail), r.left + pad, r.top + pad + name.textSize * 0.8f, name)
        shrink(vt, num, if (inline) avail - unit.measureText(ind.unit) - pad * 0.4f else avail)
        val baseline = r.top + r.height * (if (inline) 0.68f else 0.62f)
        text(vt, r.left + pad, baseline, num)
        if (inline) text(ind.unit, r.left + pad + num.measureText(vt) + pad * 0.3f, baseline, unit)
        else text(fit(ind.unit, unit, avail), r.left + pad, baseline + unit.textSize * 1.25f, unit)
        bar(ind, v, Rect(r.left + pad, r.bottom - pad - r.height * 0.06f, r.right - pad, r.bottom - pad), hot)
    }

    private fun DrawScope.bar(ind: Indicator, v: Float?, r: Rect, hot: Boolean) {
        val f = if (v == null || ind.max <= ind.min) 0f else ((v - ind.min) / (ind.max - ind.min)).coerceIn(0f, 1f)
        drawRoundRect(C.Border, r.topLeft, r.size, CornerRadius(r.height / 2))
        if (f > 0f) drawRoundRect(if (hot) C.Red else C.Violet, r.topLeft, Size(r.width * f, r.height), CornerRadius(r.height / 2))
    }

    private fun DrawScope.big(it: Pair<Indicator, Float?>?, r: Rect) {
        if (it == null) return
        val (ind, v) = it
        val hot = ind.warn(v)
        val cx = r.center.x
        val name = paint(r.height * 0.11f, C.Sub, false, Paint.Align.CENTER)
        text(fit(ind.title, name, r.width * 0.9f), cx, r.top + r.height * 0.18f, name)
        val num = paint(r.height * 0.42f, if (hot) C.Red else C.Text, align = Paint.Align.CENTER)
        while (num.measureText(ind.text(v)) > r.width * 0.9f && num.textSize > 10f) num.textSize *= 0.9f
        text(ind.text(v), cx, r.top + r.height * 0.62f, num)
        text(ind.unit, cx, r.top + r.height * 0.78f, paint(r.height * 0.09f, C.Sub, false, Paint.Align.CENTER))
        bar(ind, v, Rect(r.left + r.width * 0.08f, r.top + r.height * 0.86f, r.right - r.width * 0.08f, r.top + r.height * 0.9f), hot)
    }

    private fun DrawScope.list(items: List<Pair<Indicator, Float?>?>, r: Rect) {
        val shown = items.filterNotNull()
        if (shown.isEmpty()) return
        val rh = r.height / shown.size.coerceAtLeast(4)
        shown.forEachIndexed { i, (ind, v) ->
            val y = r.top + rh * i
            val hot = ind.warn(v)
            val name = paint(rh * 0.42f, C.Sub, false)
            val num = paint(rh * 0.55f, if (hot) C.Red else C.Text, align = Paint.Align.RIGHT)
            val unit = paint(rh * 0.32f, C.Sub, false)
            val right = r.right - rh * 0.2f
            val uw = unit.measureText(ind.unit) + rh * 0.15f
            text(ind.unit, right - uw + rh * 0.15f, y + rh * 0.68f, unit)
            text(shrink(ind.text(v), num, r.width * 0.45f), right - uw, y + rh * 0.7f, num)
            text(fit(ind.short, name, r.width - num.measureText(ind.text(v)) - uw - rh * 0.3f), r.left + rh * 0.15f, y + rh * 0.66f, name)
            if (i < shown.size - 1) drawLine(C.Border, Offset(r.left, y + rh), Offset(r.right, y + rh), 1.5f)
        }
    }

    /** The upright size pages are drawn at for this setting (the panel's when no board is chosen yet: a sample). */
    fun canvas(cfg: ScreenCfg): Triple<Int, Int, Boolean> {
        val m = ScreenModel.of(cfg.model) ?: ScreenModel.WS_AMOLED_143
        val (w, h) = UsbScreen.canvasSize(m, cfg.rotation)
        return Triple(w, h, m.round)
    }
}
