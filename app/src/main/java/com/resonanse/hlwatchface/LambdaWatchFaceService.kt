package com.resonanse.hlwatchface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.Typeface
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import androidx.wear.watchface.style.UserStyleSchema
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lambda HUD — Half-Life themed Wear OS watch face.
 *
 * Circle-geometry aware bounds (at extreme y positions the valid x range narrows):
 *   TOP  x≥0.20 (at y=0.13 the circle's left edge is at x≈0.18)
 *   BOT  x≥0.20 (symmetric)
 *   L1/L2/L3  x≥0.08 (safe at mid-screen heights)
 *
 * L1/L2/L3 are rendered MANUALLY (large fonts, reference-style layout):
 *   ┌──────────────────────────────────┐
 *   │ [icon] large-value  [ LABEL pill ]│
 *   │ ████████░░  segmented bar         │
 *   └──────────────────────────────────┘
 *
 * TOP uses the system ComplicationDrawable renderer.
 * On-watch editor: long-press → Customize  (via WatchFaceConfigActivity).
 */
class LambdaWatchFaceService : WatchFaceService() {

    companion object {
        // IDs are public so WatchFaceConfigActivity can reference them
        const val TOP_ID = 100
        const val L1_ID  = 101
        const val L2_ID  = 102
        const val L3_ID  = 103
        const val BOT_ID = 104

        val ORANGE      = Color.parseColor("#FF9C2E")
        val ORANGE_MED  = Color.parseColor("#BBFF9C2E")
        val ORANGE_DIM  = Color.parseColor("#40FF9C2E")
        val ORANGE_GLOW = Color.parseColor("#55FF9C2E")

        /** Every colour the face draws with, so a theme is one swap. */
        data class Palette(
            val accent: Int,
            val med: Int,
            val dim: Int,
            val glow: Int,
            val hourGlow: Int,
            val bg: Int,
            val ring: Int,
            val emblem: Emblem
        )

        enum class Emblem { LAMBDA, COMBINE }

        /** Half-Life 1 HEV suit HUD: amber phosphor. */
        val LAMBDA_PALETTE = Palette(
            accent   = ORANGE,
            med      = ORANGE_MED,
            dim      = ORANGE_DIM,
            glow     = ORANGE_GLOW,
            hourGlow = Color.parseColor("#99FF9C2E"),
            bg       = Color.parseColor("#050505"),
            ring     = Color.parseColor("#1A1A1A"),
            emblem   = Emblem.LAMBDA
        )

        /** Half-Life 2 Combine displays: the pale neon blue of their console screens. */
        val COMBINE_PALETTE = Palette(
            accent   = Color.parseColor("#5FD8FF"),
            med      = Color.parseColor("#BB5FD8FF"),
            dim      = Color.parseColor("#405FD8FF"),
            glow     = Color.parseColor("#555FD8FF"),
            hourGlow = Color.parseColor("#995FD8FF"),
            bg       = Color.parseColor("#04070A"),
            ring     = Color.parseColor("#12222C"),
            emblem   = Emblem.COMBINE
        )

        /**
         * Interactive frame interval. 50 ms is 20 fps.
         *
         * Measured on-watch, a frame costs ~33 ms once the raster and vector art are
         * cached (it was ~65 ms before). At 33 ms/30 fps the renderer is exactly at
         * budget with no headroom and the CPU never idles while the screen is on; at
         * 50 ms it has room to spare and the sweep still reads as smooth.
         *
         * Raise to 1000L for a one-frame-per-second face and a fraction of the
         * battery draw — nothing breaks, the animations step instead of glide.
         */
        const val FRAME_MS = 50L

        const val THEME_LAMBDA  = "lambda"
        const val THEME_COMBINE = "combine"

        /** Held as one instance so the schema and the renderer agree on identity. */
        val THEME_SETTING = ListUserStyleSetting(
            UserStyleSetting.Id("theme"),
            "Theme",
            "Faction styling",
            null,
            listOf(
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id(THEME_LAMBDA), "Lambda", "Lambda", null
                ),
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id(THEME_COMBINE), "Combine", "Combine", null
                )
            ),
            listOf(
                WatchFaceLayer.BASE,
                WatchFaceLayer.COMPLICATIONS,
                WatchFaceLayer.COMPLICATIONS_OVERLAY
            )
        )

        fun paletteFor(themeId: String?): Palette =
            if (themeId == THEME_COMBINE) COMBINE_PALETTE else LAMBDA_PALETTE
    }

    override fun createUserStyleSchema() = UserStyleSchema(listOf(THEME_SETTING))

    override fun createComplicationSlotsManager(
        repo: CurrentUserStyleRepository
    ): ComplicationSlotsManager {
        // TOP: starts at x=0.40 to stay inside the circle at y≈0.13; day-of-week
        // is drawn manually to its left.
        // L1-L3: start at x=0.08 (safe at mid-screen heights y=0.27-0.74).
        val top = mkSlot(TOP_ID,  0.40f, 0.13f, 0.79f, 0.23f, SystemDataSources.DATA_SOURCE_DATE)
        val l1  = mkSlot(L1_ID,  0.08f, 0.27f, 0.46f, 0.41f, SystemDataSources.DATA_SOURCE_WATCH_BATTERY)
        val l2  = mkSlot(L2_ID,  0.08f, 0.44f, 0.46f, 0.58f, SystemDataSources.DATA_SOURCE_SUNRISE_SUNSET)
        val l3  = mkSlot(L3_ID,  0.08f, 0.61f, 0.46f, 0.75f, SystemDataSources.DATA_SOURCE_STEP_COUNT)
        return ComplicationSlotsManager(listOf(top, l1, l2, l3), repo)
    }

    /** Kept so a theme change can recolour the system-rendered slots. */
    private val slotDrawables = mutableListOf<ComplicationDrawable>()

    private fun mkSlot(
        id: Int, l: Float, t: Float, r: Float, b: Float, src: Int
    ): ComplicationSlot {
        val drawable = ComplicationDrawable(applicationContext).apply {
            activeStyle.textColor       = ORANGE
            activeStyle.titleColor      = ORANGE_MED
            activeStyle.iconColor       = ORANGE
            activeStyle.backgroundColor = Color.TRANSPARENT
            activeStyle.borderColor     = Color.TRANSPARENT
            ambientStyle.textColor       = ORANGE_MED
            ambientStyle.titleColor      = ORANGE_DIM
            ambientStyle.iconColor       = ORANGE_DIM
            ambientStyle.backgroundColor = Color.TRANSPARENT
            ambientStyle.borderColor     = Color.TRANSPARENT
        }
        slotDrawables += drawable
        return ComplicationSlot.createRoundRectComplicationSlotBuilder(
            id = id,
            canvasComplicationFactory = { ws, listener ->
                CanvasComplicationDrawable(drawable, ws, listener)
            },
            supportedTypes = listOf(ComplicationType.GOAL_PROGRESS, ComplicationType.RANGED_VALUE, ComplicationType.SHORT_TEXT),
            defaultDataSourcePolicy = DefaultComplicationDataSourcePolicy(
                src, ComplicationType.SHORT_TEXT),
            bounds = ComplicationSlotBounds(RectF(l, t, r, b))
        ).build()
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        slots: ComplicationSlotsManager,
        repo: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = HudRenderer(surfaceHolder, watchState, slots, repo, applicationContext, slotDrawables)
        return WatchFace(WatchFaceType.DIGITAL, renderer).apply {
            setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(
                    tapType: Int,
                    tapEvent: TapEvent,
                    complicationSlot: ComplicationSlot?
                ) {
                    if (tapType == 2 && complicationSlot != null) {
                        val action = when (val d = complicationSlot.complicationData.value) {
                            is ShortTextComplicationData   -> d.tapAction
                            is RangedValueComplicationData -> d.tapAction
                            is LongTextComplicationData    -> d.tapAction
                            else                           -> null
                        }
                        try { action?.send() } catch (e: Exception) { /* no-op */ }
                    }
                }
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private class HudRenderer(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        private val slots: ComplicationSlotsManager,
        private val styleRepo: CurrentUserStyleRepository,
        private val ctx: Context,
        private val slotDrawables: List<ComplicationDrawable>
    ) : Renderer.CanvasRenderer2<HudRenderer.Assets>(
        surfaceHolder, styleRepo, watchState, CanvasType.HARDWARE, FRAME_MS, true
    ) {
        private fun p(init: Paint.() -> Unit) = Paint(Paint.ANTI_ALIAS_FLAG).apply(init)

        private val pBg       = p { color = Color.parseColor("#050505"); style = Paint.Style.FILL }
        private val pRing     = p { color = Color.parseColor("#1A1A1A"); style = Paint.Style.STROKE; strokeWidth = 6f }
        private val pArc      = p { color = ORANGE; style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND; strokeWidth = 6f
                                    setShadowLayer(12f, 0f, 0f, ORANGE_GLOW) }
        private val pDivH     = p { color = ORANGE_MED; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        // Left-column custom rendering
        private val pValue    = p { color = ORANGE; textAlign = Paint.Align.LEFT
                                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                                    setShadowLayer(8f, 0f, 0f, ORANGE_GLOW) }
        private val pLabelBox = p { color = ORANGE_MED; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        private val pLabelTxt = p { color = ORANGE; textAlign = Paint.Align.CENTER
                                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        private val pBarTrack = p { color = ORANGE_DIM; style = Paint.Style.FILL }
        private val pBarFill  = p { color = ORANGE; style = Paint.Style.FILL
                                    setShadowLayer(5f, 0f, 0f, ORANGE_GLOW) }
        // Time + lambda
        private val pHour     = p { color = ORANGE; textAlign = Paint.Align.CENTER
                                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                                    setShadowLayer(22f, 0f, 0f, Color.parseColor("#99FF9C2E")) }
        private val pMin      = p { color = ORANGE_MED; textAlign = Paint.Align.CENTER
                                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                                    setShadowLayer(12f, 0f, 0f, ORANGE_GLOW) }
        private val pEmblem   = p { color = ORANGE; textAlign = Paint.Align.CENTER
                                    typeface = Typeface.MONOSPACE
                                    setShadowLayer(20f, 0f, 0f, ORANGE_GLOW) }
        private val pDayLbl   = p { color = ORANGE_MED; textAlign = Paint.Align.RIGHT
                                    typeface = Typeface.MONOSPACE }
        private val pScan     = p { style = Paint.Style.FILL }
        private val pMark     = p { style = Paint.Style.FILL }
        // Sprite paints: SRC_ATOP recolours the white glyph, paint alpha sets weight.
        private val pGun      = p { isFilterBitmap = true
                                    colorFilter = PorterDuffColorFilter(ORANGE, PorterDuff.Mode.SRC_ATOP) }
        private val pIcon     = p { isFilterBitmap = true
                                    colorFilter = PorterDuffColorFilter(ORANGE, PorterDuff.Mode.SRC_ATOP) }

        private val iconCache = HashMap<String, Drawable?>()
        private val emblemPath = Path()

        private var palette: Palette = LAMBDA_PALETTE

        /** Reads the theme the user picked in Customize. */
        private fun selectedPalette(): Palette {
            val opt = styleRepo.userStyle.value[THEME_SETTING] as? ListUserStyleSetting.ListOption
            return paletteFor(opt?.id?.value?.let { String(it) })
        }

        /**
         * Repaints every brush. Cheap, and a no-op unless the theme actually changed.
         *
         * Two things are easy to miss when adding a theme: [tintedIcon] must tint from
         * `palette.accent` rather than the ORANGE constant, or complication icons stay
         * amber on a blue face; and the tinted-drawable cache has the old colour baked
         * in, so it is cleared here.
         */
        private fun applyPalette(pal: Palette) {
            if (pal == palette && paletteApplied) return
            palette = pal
            paletteApplied = true
            pBg.color = pal.bg
            pRing.color = pal.ring
            pArc.color = pal.accent; pArc.setShadowLayer(12f, 0f, 0f, pal.glow)
            pDivH.color = pal.med
            pValue.color = pal.accent; pValue.setShadowLayer(8f, 0f, 0f, pal.glow)
            pLabelBox.color = pal.med
            pLabelTxt.color = pal.accent
            pBarTrack.color = pal.dim
            pBarFill.color = pal.accent; pBarFill.setShadowLayer(5f, 0f, 0f, pal.glow)
            pHour.color = pal.accent; pHour.setShadowLayer(22f, 0f, 0f, pal.hourGlow)
            pMin.color = pal.med; pMin.setShadowLayer(12f, 0f, 0f, pal.glow)
            pEmblem.color = pal.accent; pEmblem.setShadowLayer(20f, 0f, 0f, pal.glow)
            pDayLbl.color = pal.med
            pGun.colorFilter = PorterDuffColorFilter(pal.accent, PorterDuff.Mode.SRC_ATOP)
            pIcon.colorFilter = PorterDuffColorFilter(pal.accent, PorterDuff.Mode.SRC_ATOP)
            // Cached complication drawables carry the previous tint baked in.
            iconCache.clear()
            gridCache = null
            rasterCache.clear()
            for (d in slotDrawables) {
                d.activeStyle.textColor   = pal.accent
                d.activeStyle.titleColor  = pal.med
                d.activeStyle.iconColor   = pal.accent
                d.ambientStyle.textColor  = pal.med
                d.ambientStyle.titleColor = pal.dim
                d.ambientStyle.iconColor  = pal.dim
            }
        }

        private var paletteApplied = false

        private val hourFmt = DateTimeFormatter.ofPattern("HH")
        private val minFmt  = DateTimeFormatter.ofPattern("mm")

        // ── Complication data helpers ─────────────────────────────────────────

        private fun compProgress(slot: ComplicationSlot): Float =
            when (val d = slot.complicationData.value) {
                is GoalProgressComplicationData ->
                    if (d.targetValue > 0f) (d.value / d.targetValue).coerceIn(0f, 1f) else -1f
                is RangedValueComplicationData -> {
                    val range = d.max - d.min
                    // range=0 means data not yet available
                    if (range > 0f) ((d.value - d.min) / range).coerceIn(0f, 1f) else -1f
                }
                is ShortTextComplicationData -> {
                    // Parse "100%" style text (e.g. battery)
                    val text = d.text.getTextAt(ctx.resources, Instant.now()).toString()
                    Regex("""(\d+)\s*%""").find(text)
                        ?.groupValues?.get(1)?.toFloatOrNull()
                        ?.div(100f)?.coerceIn(0f, 1f)
                        ?: -1f
                }
                else -> -1f
            }

        private fun compTexts(slot: ComplicationSlot): Pair<String, String?> {
            val now = Instant.now()
            return when (val d = slot.complicationData.value) {
                is GoalProgressComplicationData -> Pair(
                    d.text?.getTextAt(ctx.resources, now)?.toString() ?: "${d.value.toInt()}",
                    d.title?.getTextAt(ctx.resources, now)?.toString()
                )
                is RangedValueComplicationData -> Pair(
                    d.text?.getTextAt(ctx.resources, now)?.toString() ?: "${d.value.toInt()}",
                    d.title?.getTextAt(ctx.resources, now)?.toString()
                )
                is ShortTextComplicationData -> Pair(
                    d.text.getTextAt(ctx.resources, now).toString(),
                    d.title?.getTextAt(ctx.resources, now)?.toString()
                )
                else -> Pair("---", null)
            }
        }

        // ── Assets ────────────────────────────────────────────────────────────

        override suspend fun createSharedAssets(): Assets {
            // Every sprite is optional and looked up by name. The repo ships no game
            // artwork; run setup_assets.py to import your own. Anything missing simply
            // is not drawn — see the Assets docs below.
            fun bmp(name: String): Bitmap? {
                val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
                return if (id != 0) BitmapFactory.decodeResource(ctx.resources, id) else null
            }
            return Assets(
                egonBmp     = bmp("hl_egon"),
                iconSuit    = bmp("hl_icon_suit"),
                iconHealth  = bmp("hl_icon_health"),
                iconBattery = bmp("hl_icon_battery"),
                // HL1 damage-indicator set, in the sheet's own order.
                damage = listOfNotNull(
                    bmp("hl_dmg_chem"),
                    bmp("hl_dmg_o2"),
                    bmp("hl_dmg_bio"),
                    bmp("hl_dmg_shock"),
                    bmp("hl_dmg_poison"),
                    bmp("hl_dmg_freeze"),
                    bmp("hl_dmg_fire"),
                    bmp("hl_dmg_rad")
                )
            )
        }

        // ── Render ────────────────────────────────────────────────────────────

        override fun render(canvas: Canvas, bounds: Rect, t: ZonedDateTime, a: Assets) {
            val W  = bounds.width().toFloat()
            val H  = bounds.height().toFloat()
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val R  = W * 0.48f
            val ambient = renderParameters.drawMode == DrawMode.AMBIENT
            applyPalette(selectedPalette())

            // Fractional seconds: without this every animation is quantised to whole
            // seconds and a higher frame rate buys nothing but battery drain.
            val secF = t.second + t.nano / 1_000_000_000f

            // 1 ── background
            canvas.drawColor(Color.BLACK)
            canvas.drawCircle(cx, cy, R, pBg)

            // 2 ── gluon gun watermark with its charge sweep
            if (!ambient) {
                if (palette.emblem == Emblem.LAMBDA)
                    drawGluonGun(canvas, a.egonBmp, cx, cy, R, secF)
                else
                    drawCombineWatermark(canvas, cx, cy, R, secF)
            }

            // 3 ── CRT grid (scanlines + aperture grille)
            if (!ambient) canvas.drawBitmap(gridBitmap(cx, cy, R, W, H), 0f, 0f, null)

            // 4 ── outer ring + seconds arc (full 360° from 12-o'clock)
            canvas.drawCircle(cx, cy, R - 4f, pRing)
            if (!ambient) canvas.drawArc(
                RectF(cx - R + 8f, cy - R + 8f, cx + R - 8f, cy + R - 8f),
                -90f, secF / 60f * 360f, false, pArc
            )

            // 5 ── lambda badge, sitting in the corridor right of centre
            if (palette.emblem == Emblem.LAMBDA) {
                // Black Mesa logo when it has been imported; the lambda glyph is the
                // fallback, so an artwork-free build still has a centre mark.
                val bm = art("hl_blackmesa")
                if (bm != null) {
                    bm.setTint(palette.accent)
                    bm.setTintMode(PorterDuff.Mode.SRC_IN)
                    drawDrawable(canvas, bm, cx + R * 0.10f, cy, R * 0.30f, 255)
                } else {
                    pEmblem.textSize = R * 0.28f
                    canvas.drawText("λ", cx + R * 0.10f, cy + pEmblem.textSize * 0.38f, pEmblem)
                }
            } else {
                drawCombineEmblem(canvas, cx + R * 0.10f, cy, R * 0.115f)
            }

            // 6 ── TOP via system renderer; day-of-week drawn manually to its left
            slots.complicationSlots[TOP_ID]?.let { topSlot ->
                val topSb = topSlot.computeBounds(bounds)
                val dayStr = t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).uppercase()
                pDayLbl.textSize = R * 0.080f
                canvas.drawText(
                    dayStr,
                    topSb.left.toFloat() - R * 0.03f,
                    (topSb.top + topSb.bottom) / 2f + pDayLbl.textSize * 0.36f,
                    pDayLbl
                )
                topSlot.render(canvas, t, renderParameters)
            }

            // 7 ── L1/L2/L3: custom large-font rendering, each with its HL1 glyph
            val leftRows = listOf(
                Triple(L1_ID, "SUIT",  a.iconBattery),
                Triple(L2_ID, "PULSE", a.iconHealth),
                Triple(L3_ID, "MOVE",  a.iconSuit)
            )
            for ((id, defaultLabel, icon) in leftRows) {
                val slot = slots.complicationSlots[id] ?: continue
                renderLeftRow(canvas, slot.computeBounds(bounds), slot, defaultLabel, icon)
            }

            // Subscribe once, the first time a slot actually asks for heart rate. The
            // subscription lives in Health Services and keeps delivering in ambient, so
            // there is nothing to tear down when the screen goes off.
            val wantsHeartRate = leftRows.any { (id, _, _) ->
                slots.complicationSlots[id]?.let { isHeartRateSource(it) } == true
            }
            if (wantsHeartRate) {
                // Passive keeps a last-known reading across ambient and reboots; the
                // live callback only runs while the face is awake and on screen.
                HeartRateSource.ensureRegistered(ctx)
                // Duty-cycled rather than stopped on ambient: re-acquiring costs
                // ~20 s, longer than the screen stays on, so a burst has to outlast
                // the screen. See HeartRateSource for the timings.
                HeartRateSource.tick(ctx, faceVisible = !ambient)
            } else {
                HeartRateSource.stopLive(ctx)
            }

            // 8 ── damage-indicator strip along the bottom
            if (!ambient) drawDamageStrip(canvas, a.damage, cx, cy, R, t.toEpochSecond())

            // 9 ── large split time — on top
            val timeX = cx + R * 0.52f

            pHour.textSize = R * 0.40f
            canvas.drawText(t.format(hourFmt), timeX, cy - R * 0.07f, pHour)

            // Divider spans only the digit width, clearing the lambda to its left.
            canvas.drawLine(timeX - R * 0.28f, cy + R * 0.065f,
                            timeX + R * 0.28f, cy + R * 0.065f, pDivH)

            pMin.textSize = R * 0.38f
            canvas.drawText(t.format(minFmt), timeX, cy + R * 0.46f, pMin)

        }

        /** Draw a white-alpha sprite tinted orange, fitted into a box, aspect preserved. */
        private fun drawGlyph(
            canvas: Canvas, bmp: Bitmap?, x: Float, y: Float,
            boxW: Float, boxH: Float, alpha: Int
        ) {
            if (bmp == null) return
            val s = minOf(boxW / bmp.width, boxH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            pIcon.alpha = alpha
            canvas.drawBitmap(bmp, null,
                RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f), pIcon)
        }

        /**
         * Gluon gun watermark: a faint full-width silhouette, with a bright band of
         * "charge" travelling along the barrel once per minute so the sprite reads as
         * powered rather than as a dead background stamp.
         */
        private fun drawGluonGun(
            canvas: Canvas, bmp: Bitmap?, cx: Float, cy: Float, R: Float, second: Float
        ) {
            if (bmp == null) return
            val bw  = R * 1.55f
            val bh  = bw * bmp.height / bmp.width
            val dst = RectF(cx - bw * 0.52f, cy - bh / 2f, cx + bw * 0.48f, cy + bh / 2f)

            pGun.alpha = 36
            canvas.drawBitmap(bmp, null, dst, pGun)

            // Sweep head runs from just off the left edge to just off the right edge.
            val band   = bw * 0.20f
            val head   = dst.left - band + (second / 60f) * (bw + 2f * band)
            val slices = 9
            for (i in 0 until slices) {
                val x0 = head - band + (2f * band) * i / slices
                val x1 = head - band + (2f * band) * (i + 1) / slices
                // Raised cosine so the band fades in and out instead of hard-edging.
                val u  = ((x0 + x1) * 0.5f - head) / band
                val f  = (Math.cos(u * Math.PI).toFloat() + 1f) * 0.5f
                pGun.alpha = (26f + f * 108f).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.clipRect(x0, dst.top, x1, dst.bottom)
                canvas.drawBitmap(bmp, null, dst, pGun)
                canvas.restore()
            }
        }

        /**
         * Combine insignia.
         *
         * APPROXIMATION — drawn from the emblem's silhouette rather than traced from a
         * reference, because the wiki blocks scripted fetches. Drop a white-on-
         * transparent `hl2_combine.png` into `res/drawable/` and swap this for
         * `drawGlyph` if you want the exact mark.
         */
        private fun buildCombineEmblem(cx: Float, cy: Float, s: Float) {
            emblemPath.reset()
            // Outer shoulders
            emblemPath.moveTo(cx - 1.00f * s, cy - 1.15f * s)
            emblemPath.lineTo(cx - 0.52f * s, cy - 1.15f * s)
            // Inner claw, cut deep so the notch reads at watch size
            emblemPath.lineTo(cx - 0.52f * s, cy + 0.34f * s)
            emblemPath.lineTo(cx - 0.20f * s, cy + 0.60f * s)
            emblemPath.lineTo(cx + 0.20f * s, cy + 0.60f * s)
            emblemPath.lineTo(cx + 0.52f * s, cy + 0.34f * s)
            emblemPath.lineTo(cx + 0.52f * s, cy - 1.15f * s)
            emblemPath.lineTo(cx + 1.00f * s, cy - 1.15f * s)
            emblemPath.lineTo(cx + 1.00f * s, cy + 0.52f * s)
            emblemPath.lineTo(cx + 0.46f * s, cy + 1.15f * s)
            emblemPath.lineTo(cx - 0.46f * s, cy + 1.15f * s)
            emblemPath.lineTo(cx - 1.00f * s, cy + 0.52f * s)
            emblemPath.close()
        }

        /**
         * Draws the Combine mark. Prefers a real `hl2_combine.png` dropped into
         * `res/drawable/` (white on transparent) and falls back to the built-in path,
         * which is an approximation — see [buildCombineEmblem].
         */
        private fun drawCombineEmblem(canvas: Canvas, cx: Float, cy: Float, s: Float) {
            val mark = combineDrawable()
            if (mark != null) {
                mark.setTint(palette.accent)
                mark.setTintMode(PorterDuff.Mode.SRC_IN)
                drawDrawable(canvas, mark, cx, cy, s * 2.3f, 255)
            } else {
                buildCombineEmblem(cx, cy, s)
                canvas.drawPath(emblemPath, pEmblem)
            }
        }

        private val artCache = HashMap<String, Drawable?>()

        /** Optional vector art, looked up by name so the build stays green. */
        private fun art(name: String): Drawable? = artCache.getOrPut(name) {
            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
            if (id != 0) ctx.getDrawable(id) else null
        }

        /**
         * The Combine insignia as a vector drawable, so it stays sharp at any size.
         * BitmapFactory cannot decode a VectorDrawable, hence getDrawable. Looked up
         * by name so the resource stays optional and the path fallback still applies.
         */
        private fun combineDrawable(): Drawable? = art("hl2_combine")

        /**
         * Combine counterpart to the gluon gun watermark: the insignia held faint
         * behind the dial with a bright band sweeping across it once a minute.
         */
        private fun drawCombineWatermark(
            canvas: Canvas, cx: Float, cy: Float, R: Float, second: Float
        ) {
            val ar = Color.red(palette.accent)
            val ag = Color.green(palette.accent)
            val ab = Color.blue(palette.accent)

            // The CMB glyph strip is wide and short (749x242), so it lies across the
            // dial the way the gluon gun does on the Lambda face.
            val bw  = R * 1.55f
            val bh  = bw * 242f / 749f
            val s   = R * 0.60f
            val cmb = raster("hl2_cmb", bw.toInt(), bh.toInt())
            if (cmb == null) buildCombineEmblem(cx, cy, s)

            fun paint(alpha: Int) {
                if (cmb != null) {
                    pRaster.alpha = alpha
                    canvas.drawBitmap(cmb, cx - bw / 2f, cy - bh / 2f, pRaster)
                } else {
                    pMark.color = Color.argb(alpha, ar, ag, ab)
                    canvas.drawPath(emblemPath, pMark)
                }
            }

            paint(42)

            val half   = if (cmb != null) bw / 2f else s * 1.05f
            val tall   = if (cmb != null) bh / 2f else s * 1.3f
            val band   = half * 0.42f
            val head   = cx - half - band + (second / 60f) * (2f * half + 2f * band)
            val slices = 9
            for (i in 0 until slices) {
                val x0 = head - band + (2f * band) * i / slices
                val x1 = head - band + (2f * band) * (i + 1) / slices
                val u  = ((x0 + x1) * 0.5f - head) / band
                val f  = (Math.cos(u * Math.PI).toFloat() + 1f) * 0.5f
                canvas.save()
                canvas.clipRect(x0, cy - tall, x1, cy + tall)
                paint((30f + f * 120f).toInt().coerceIn(0, 255))
                canvas.restore()
            }
        }

        /**
         * HL1 damage-indicator icons in a row along the bottom, mostly dim, with the
         * lit one stepping along once per second — a HUD running its self-test.
         */
        private fun drawDamageStrip(
            canvas: Canvas, icons: List<Bitmap>, cx: Float, cy: Float, R: Float, epochSec: Long
        ) {
            if (icons.isEmpty()) return
            val n     = icons.size
            val size  = R * 0.122f
            val gap   = R * 0.024f
            val yMid  = cy + R * 0.70f
            val total = n * size + (n - 1) * gap
            val active = (epochSec.mod(n.toLong())).toInt()
            var x = cx - total / 2f + size / 2f
            for (i in 0 until n) {
                val on = i == active
                drawGlyph(canvas, icons[i], x, yMid, size, size, if (on) 235 else 52)
                if (on) canvas.drawRect(
                    x - size * 0.5f, yMid + size * 0.64f,
                    x + size * 0.5f, yMid + size * 0.64f + 2f, pBarFill
                )
                x += size + gap
            }
        }

        /**
         * Custom renderer for a single left-column row:
         *
         *  ┌──────────────────────────────────┐  ← sb.top
         *  │ [icon] VALUE     ┌─ LABEL ─┐     │
         *  │        (large)   └─────────┘     │
         *  │ ███████░░░░  10 seg bar          │  ← sb.bottom
         *  └──────────────────────────────────┘
         */
        private fun renderLeftRow(
            canvas: Canvas,
            sb: Rect,
            slot: ComplicationSlot,
            defaultLabel: String,
            fallbackIcon: Bitmap?
        ) {
            val slotW = sb.width().toFloat()
            val slotH = sb.height().toFloat()

            val (rawValue, compLabel) = compTexts(slot)
            val labelStr = compLabel?.uppercase() ?: defaultLabel

            // A heart rate slot shows our own Health Services reading: the complication
            // itself is only allowed to hand us the provider's name. When there is no
            // fresh reading show "--" rather than that placeholder, and never a stale
            // number — the sensor goes quiet off-wrist, on charge, and in ambient.
            val isHr      = isHeartRateSource(slot)
            val liveBpm   = if (isHr) HeartRateSource.bpm(ctx) else 0
            val valueStr  = when {
                liveBpm > 0 -> liveBpm.toString()
                isHr        -> "--"
                else        -> rawValue
            }

            // -1f means the data source carries no range at all (most SHORT_TEXT
            // sources). Showing a permanently empty bar for those reads as "zero"
            // rather than "not applicable", so the row drops the bar entirely and
            // gives the height back to the value instead.
            val prog   = when {
                liveBpm > 0 -> ((liveBpm - 40f) / 140f).coerceIn(0f, 1f)
                isHr        -> -1f
                else        -> compProgress(slot)
            }
            val hasBar = prog >= 0f
            val dataH  = if (hasBar) slotH * 0.62f else slotH * 0.90f
            val midY   = sb.top + dataH / 2f

            // Icon: the data source's own monochrome glyph, tinted orange, so it
            // tracks whatever the user assigns. HL1 sprite only when it has none.
            val iconBox = minOf(dataH * 0.62f, slotH * 0.46f)
            val sysIcon = tintedIcon(compIcon(slot))
            if (sysIcon != null) {
                drawDrawable(canvas, sysIcon, sb.left + iconBox * 0.5f, midY, iconBox, 215)
            } else {
                drawGlyph(canvas, fallbackIcon,
                    sb.left + iconBox * 0.5f, midY, iconBox, iconBox, 205)
            }

            // Label pill, right-aligned and only as wide as its text needs, so the
            // value keeps every pixel the label does not use.
            val pillH = slotH * 0.40f
            pLabelTxt.textSize = pillH * 0.52f
            val boxR  = sb.right.toFloat() - 2f
            val pillW = minOf(pLabelTxt.measureText(labelStr) + pillH * 0.9f, slotW * 0.46f)
            val boxL  = boxR - pillW
            val boxT  = midY - pillH / 2f
            val boxB  = boxT + pillH
            canvas.drawRoundRect(RectF(boxL, boxT, boxR, boxB), 4f, 4f, pLabelBox)
            while (pLabelTxt.measureText(labelStr) > pillW - 6f &&
                   pLabelTxt.textSize > pillH * 0.28f)
                pLabelTxt.textSize -= 1f
            canvas.drawText(labelStr, (boxL + boxR) / 2f,
                midY + pLabelTxt.textSize * 0.36f, pLabelTxt)

            // Value: shrink to fit, but stop at a legible floor and ellipsize past it
            // rather than dwindling to unreadable (placeholder strings like the heart
            // rate source's "Heart rate" are long).
            val valueX    = sb.left + iconBox + slotW * 0.03f
            val maxValueW = boxL - valueX - 6f
            val minSize   = dataH * 0.34f
            pValue.textSize = dataH * 0.74f
            while (pValue.measureText(valueStr) > maxValueW && pValue.textSize > minSize)
                pValue.textSize -= 1f
            var shown = valueStr
            if (pValue.measureText(shown) > maxValueW) {
                while (shown.length > 1 && pValue.measureText(shown + "\u2026") > maxValueW)
                    shown = shown.dropLast(1)
                shown += "\u2026"
            }
            canvas.drawText(shown, valueX, midY + pValue.textSize * 0.36f, pValue)

            // 10-segment bar — only when the value genuinely sits in a range, where
            // filled segments mean value/(max-min).
            if (hasBar) {
                val nSegs = 10
                val gap   = 2.5f
                val barT  = sb.top + slotH * 0.70f
                val barB  = sb.bottom.toFloat()
                val segW  = (slotW - (nSegs - 1) * gap) / nSegs
                val filled = (prog * nSegs).toInt().coerceIn(0, nSegs)
                for (i in 0 until nSegs) {
                    val sx = sb.left + i * (segW + gap)
                    canvas.drawRoundRect(RectF(sx, barT, sx + segW, barB), 2f, 2f,
                        if (i < filled) pBarFill else pBarTrack)
                }
            }
        }

        /**
         * True when the slot's assigned data source is a heart rate provider. Matched
         * on the provider's class name because the gated providers give us nothing
         * else to go on — their payload is indistinguishable from any other short text.
         */
        private fun isHeartRateSource(slot: ComplicationSlot): Boolean {
            val cn = when (val d = slot.complicationData.value) {
                is ShortTextComplicationData    -> d.dataSource
                is RangedValueComplicationData  -> d.dataSource
                is GoalProgressComplicationData -> d.dataSource
                else -> null
            }
            return cn?.className?.contains("heartrate", ignoreCase = true) == true
        }

        /** The data source's monochrome glyph, if it supplies one. */
        private fun compIcon(slot: ComplicationSlot): Icon? =
            when (val d = slot.complicationData.value) {
                is ShortTextComplicationData    -> d.monochromaticImage?.image
                is RangedValueComplicationData  -> d.monochromaticImage?.image
                is GoalProgressComplicationData -> d.monochromaticImage?.image
                else -> null
            }

        /**
         * Loads a complication icon and tints it orange. Cached by the Icon's
         * identity string: loadDrawable crosses a package boundary, far too slow to
         * repeat every frame. Returns null if the owning package is not readable,
         * which lets the row fall back to its HL1 sprite.
         */
        private fun tintedIcon(icon: Icon?): Drawable? {
            if (icon == null) return null
            return iconCache.getOrPut(icon.toString()) {
                try {
                    icon.loadDrawable(ctx)?.apply {
                        setTint(palette.accent)
                        setTintMode(PorterDuff.Mode.SRC_IN)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }

        /** Draws a drawable into a w x h box, ignoring its intrinsic aspect. */
        private fun drawDrawableRect(
            canvas: Canvas, d: Drawable, x: Float, y: Float,
            w: Float, h: Float, alpha: Int
        ) {
            d.alpha = alpha
            d.setBounds(
                (x - w / 2f).toInt(), (y - h / 2f).toInt(),
                (x + w / 2f).toInt(), (y + h / 2f).toInt()
            )
            d.draw(canvas)
        }

        private fun drawDrawable(
            canvas: Canvas, d: Drawable, x: Float, y: Float, box: Float, alpha: Int
        ) {
            val h = box / 2f
            d.alpha = alpha
            d.setBounds((x - h).toInt(), (y - h).toInt(), (x + h).toInt(), (y + h).toInt())
            d.draw(canvas)
        }

        /**
         * CRT raster: thin, densely pitched phosphor lines in both axes, with a
         * sinusoidal brightness envelope rolling down the horizontal ones and an
         * extra lift where the content rows sit.
         */
        private var gridCache: Bitmap? = null
        private var gridKey = ""
        private val rasterCache = HashMap<String, Bitmap>()
        private val pRaster = p { isFilterBitmap = true }

        /**
         * The raster is identical every frame for a given size and palette but costs
         * ~375 alpha-blended rects. Drawing it once into a bitmap and blitting that
         * took the frame from ~65 ms to ~22 ms.
         */
        private fun gridBitmap(cx: Float, cy: Float, R: Float, W: Float, H: Float): Bitmap {
            val key = "${W.toInt()}x${H.toInt()}:${palette.accent}"
            gridCache?.let { if (key == gridKey) return it }
            val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
            drawGrid(Canvas(bmp), cx, cy, R, H)
            gridCache = bmp
            gridKey = key
            return bmp
        }

        /**
         * A VectorDrawable re-rasterises its paths on every draw, and the watermark
         * sweep draws the same art ten times a frame. Rasterise once at the size and
         * tint we need, then blit.
         */
        private fun raster(name: String, w: Int, h: Int): Bitmap? {
            if (w <= 0 || h <= 0) return null
            val key = "$name:${w}x$h:${palette.accent}"
            rasterCache[key]?.let { return it }
            val d = art(name) ?: return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setTint(palette.accent)
            d.setTintMode(PorterDuff.Mode.SRC_IN)
            d.alpha = 255
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            rasterCache[key] = bmp
            return bmp
        }

        private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, R: Float, H: Float) {
            val ar = Color.red(palette.accent)
            val ag = Color.green(palette.accent)
            val ab = Color.blue(palette.accent)
            val cycles    = 26.0
            val textBands = floatArrayOf(0.18f, 0.34f, 0.51f, 0.68f, 0.82f)
            val bandR     = 0.05f   // ±5% of H ≈ ±20 px around each text row

            // Scanlines: 1 px line on a 2 px pitch, alpha 0.035–0.14 from the sine.
            var y = 0f
            while (y < H) {
                val dy   = y - cy
                val disc = R * R - dy * dy
                if (disc > 0f) {
                    val half  = sqrt(disc)
                    val yn    = y / H
                    val wave  = (Math.sin(yn * 2.0 * Math.PI * cycles).toFloat() + 1f) * 0.5f
                    val base  = 0.030f + wave * 0.125f
                    val dist  = textBands.minOfOrNull { abs(it - yn) } ?: 1f
                    val glow  = if (dist < bandR) (1f - dist / bandR) * 0.14f else 0f
                    val alpha = (base + glow).coerceAtMost(0.32f)
                    pScan.color = Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255), ar, ag, ab)
                    canvas.drawRect(cx - half, y, cx + half, y + 1f, pScan)
                }
                y += 2f
            }

            // Aperture grille: 1 px line on a 3 px pitch, flat and very faint, so the
            // crossings read as a grid without competing with the scanline wave.
            pScan.color = Color.argb(11, ar, ag, ab)
            var x = cx - R
            while (x < cx + R) {
                val dx   = x - cx
                val disc = R * R - dx * dx
                if (disc > 0f) {
                    val half = sqrt(disc)
                    canvas.drawRect(x, cy - half, x + 1f, cy + half, pScan)
                }
                x += 3f
            }
        }

        override fun renderHighlightLayer(
            canvas: Canvas, bounds: Rect, t: ZonedDateTime, a: Assets
        ) {
            for (s in slots.complicationSlots.values)
                s.renderHighlightLayer(canvas, t, renderParameters)
        }

        /**
         * Optional game artwork. All of it may be absent — the repo ships none — in
         * which case the face degrades rather than breaks: rows fall back to the data
         * source's own icon, and the watermark and damage strip are simply not drawn.
         */
        class Assets(
            val egonBmp: Bitmap?,
            val iconSuit: Bitmap?,
            val iconHealth: Bitmap?,
            val iconBattery: Bitmap?,
            val damage: List<Bitmap>
        ) : Renderer.SharedAssets {
            /**
             * Deliberately empty. One Assets instance is shared by every renderer in
             * the process, and opening the on-watch editor creates a second renderer;
             * when that one goes away this fires while the live renderer is still
             * drawing from the same instance. Recycling or nulling the bitmaps here
             * blanks every sprite on the face until the process restarts. They are
             * ordinary heap allocations — GC reclaims them along with this object.
             */
            override fun onDestroy() {}
        }
    }
}
