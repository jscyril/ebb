package com.jscyril.ebb.ui.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.jscyril.ebb.R
import com.jscyril.ebb.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Theme colours as ARGB ints, read from the app theme when the user taps Share. */
class ShareColors(val bg: Int, val card: Int, val text: Int, val muted: Int, val accent: Int)

/** Draws this week's summary as a PNG and opens the share sheet. No network: the user picks where it goes. */
suspend fun shareWeek(ctx: Context, c: ShareColors) {
    val repo = Repo.get(ctx)
    val week = repo.dailyTotals(7).first()
    val perApp = week.flatMap { repo.perApp(it.dayKey).first() }.groupBy { it.pkg }
        .map { (pkg, l) -> pkg to l.sumOf { it.seconds } }.filter { it.second >= 60 }.sortedByDescending { it.second }.take(3)
    val file = withContext(Dispatchers.Default) {
        val bmp = drawWeek(ctx, c, week.map { it.dayKey to it.seconds }, week.sumOf { it.blocked },
            perApp.map { Apps.label(ctx, it.first) to it.second })
        File(ctx.cacheDir, "share").apply { mkdirs() }.resolve("ebb-week.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(null, uri) } // the share sheet's preview and the read grant
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_week_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun dur(ctx: Context, sec: Long): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) ctx.getString(R.string.dur_hours_min, m / 60, m % 60) else ctx.getString(R.string.dur_min, m)
}

private fun date(key: Int) = LocalDate.of(key / 10000, key / 100 % 100, key % 100)

private fun drawWeek(ctx: Context, c: ShareColors, days: List<Pair<Int, Long>>, blocked: Int, top: List<Pair<String, Long>>): Bitmap {
    val w = 1080; val h = 1350; val pad = 72f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(c.bg)
    fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val range = if (days.isEmpty()) "" else "${fmt.format(date(days.first().first))} – ${fmt.format(date(days.last().first))}"
    cv.drawText(ctx.getString(R.string.share_week_heading), pad, 150f, paint(c.accent, 44f, true))
    cv.drawText(range, pad, 210f, paint(c.muted, 36f))
    val total = days.sumOf { it.second }
    cv.drawText(dur(ctx, total), pad, 340f, paint(c.text, 110f, true))
    cv.drawText(ctx.getString(R.string.share_week_total, dur(ctx, total / 7), blocked), pad, 405f, paint(c.muted, 36f))

    // Bars, one per day, scaled to the busiest day.
    val card = RectF(pad - 24, 460f, w - pad + 24, 900f)
    cv.drawRoundRect(card, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.card })
    val maxSec = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(60)
    val slot = (card.width() - 96f) / 7f
    val barW = slot * 0.55f
    val base = card.bottom - 90f
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent }
    val dayLabel = paint(c.muted, 32f).apply { textAlign = Paint.Align.CENTER }
    days.forEachIndexed { i, (key, sec) ->
        val cx = card.left + 48f + slot * i + slot / 2
        val bh = ((base - card.top - 60f) * sec / maxSec).coerceAtLeast(8f)
        cv.drawRoundRect(RectF(cx - barW / 2, base - bh, cx + barW / 2, base), 16f, 16f, barPaint)
        cv.drawText(date(key).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), cx, base + 52f, dayLabel)
    }

    cv.drawText(ctx.getString(R.string.share_week_top), pad, 990f, paint(c.text, 40f, true))
    if (top.isEmpty()) cv.drawText(ctx.getString(R.string.widget_no_use), pad, 1060f, paint(c.muted, 36f))
    top.forEachIndexed { i, (name, sec) ->
        val y = 1060f + i * 70f
        cv.drawText(name, pad, y, paint(c.text, 38f))
        cv.drawText(dur(ctx, sec), w - pad, y, paint(c.muted, 38f).apply { textAlign = Paint.Align.RIGHT })
    }
    cv.drawText(ctx.getString(R.string.share_week_footer), pad, h - 70f, paint(c.muted, 30f))
    return bmp
}
