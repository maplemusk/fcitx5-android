/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wubi

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import kotlin.math.min
import kotlin.math.roundToInt

/** Passive image hints from the original chart; no text reconstruction or input handling. */
@SuppressLint("ViewConstructor")
class WubiHintView(context: Context, private val theme: Theme) : View(context) {
    var onHidden: (() -> Unit)? = null

    private data class PendingHint(val selection: WubiHintSelection, val keyboardBounds: Rect)

    private val chartBitmap: Bitmap? = ChartBitmapCache.get(resources)
    private val imageShader = chartBitmap?.let {
        BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        shader = imageShader
    }
    private val imageMatrix = Matrix()
    private val sourceRect = RectF()
    private var selection: WubiHintSelection? = null
    private var pendingHint: PendingHint? = null
    private val card = RectF()
    private val centerPanel = RectF()
    private val leftTile = RectF()
    private val centerTile = RectF()
    private val rightTile = RectF()
    private val localLocation = IntArray(2)
    private val windowLocation = IntArray(2)
    private var animationGeneration = 0L
    private val outerShape = MaterialShapeDrawable(
        ShapeAppearanceModel.builder().setAllCornerSizes(dp(20f)).build()
    ).apply {
        fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.popupBackgroundColor, 219))
        setStroke(dp(1f), ColorUtils.setAlphaComponent(theme.popupTextColor, 32))
    }
    private val centerShape = MaterialShapeDrawable(
        ShapeAppearanceModel.builder().setAllCornerSizes(dp(8f)).build()
    ).apply {
        fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 36))
        setStroke(dp(1f), ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 72))
    }

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Unlike GONE, INVISIBLE measures the overlay before the first key press.
        visibility = INVISIBLE
        alpha = 0f
    }

    /** [keyboardBounds] is in window coordinates, including its existing toolbar. */
    fun showHint(selection: WubiHintSelection, keyboardBounds: Rect) {
        animationGeneration++
        animate().cancel()
        pendingHint = null
        if (chartBitmap == null || keyboardBounds.isEmpty || !hasValidImages(selection)) {
            clearHint()
            return
        }
        if (width <= 0 || height <= 0) {
            clearHint()
            pendingHint = PendingHint(selection, Rect(keyboardBounds))
            return
        }
        if (!positionCard(keyboardBounds, selection)) {
            clearHint()
            return
        }
        this.selection = selection
        contentDescription = context.getString(R.string.wubi_hint_popup_key, selection.center.key.toString())
        visibility = VISIBLE
        alpha = 1f
        invalidate()
    }

    fun hideHint(immediate: Boolean = true) {
        val generation = ++animationGeneration
        pendingHint = null
        animate().cancel()
        if (immediate || selection == null) {
            clearHint()
        } else {
            animate().alpha(0f).setDuration(120L).withEndAction {
                if (generation == animationGeneration) clearHint()
            }.start()
        }
    }

    private fun clearHint() {
        val wasShowing = selection != null
        pendingHint = null
        selection = null
        visibility = INVISIBLE
        alpha = 0f
        invalidate()
        if (wasShowing) onHidden?.invoke()
    }

    private fun hasValidImages(selection: WubiHintSelection): Boolean {
        val bitmap = chartBitmap ?: return false
        return listOfNotNull(selection.left, selection.center, selection.right).all { hint ->
            val image = hint.image
            image.left >= 0 && image.top >= 0 && image.width > 0 && image.height > 0 &&
                image.right <= bitmap.width && image.bottom <= bitmap.height
        }
    }

    private fun positionCard(keyboardBounds: Rect, selection: WubiHintSelection): Boolean {
        getLocationInWindow(localLocation)
        val windowRoot = rootView
        windowRoot.getLocationInWindow(windowLocation)
        val insets = ViewCompat.getRootWindowInsets(this)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val safeLeft = maxOf(0f, (windowLocation[0] + (insets?.left ?: 0) - localLocation[0]).toFloat()) + dp(8f)
        val safeRight = minOf(width.toFloat(),
            (windowLocation[0] + windowRoot.width - (insets?.right ?: 0) - localLocation[0]).toFloat()) - dp(8f)
        val safeTop = maxOf(0f, (windowLocation[1] + (insets?.top ?: 0) - localLocation[1]).toFloat()) + dp(4f)
        // Leave the ordinary preedit line between the card and the toolbar.
        val bottom = min(height.toFloat(), keyboardBounds.top - localLocation[1] - dp(32f))
        val availableHeight = bottom - safeTop
        val availableWidth = safeRight - safeLeft
        if (availableHeight < dp(60f) || availableWidth < dp(120f)) return false
        val cardWidth = min(dp(252f), min(availableWidth, keyboardBounds.width().toFloat() - dp(8f)))
        if (cardWidth < dp(120f)) return false
        val cardHeight = min(dp(96f), availableHeight)
        val centerX = keyboardBounds.exactCenterX() - localLocation[0]
        val left = WubiPopupGeometry.clampLeft(centerX - cardWidth / 2f, cardWidth, safeLeft, safeRight)
        card.set(left, bottom - cardHeight, left + cardWidth, bottom)

        // Three stable slots keep the pressed tile centered even at a QWERTY row edge.
        val centerSlot = RectF(card.left + card.width() * 0.28f, card.top + dp(6f),
            card.right - card.width() * 0.28f, card.bottom - dp(6f))
        fitImage(selection.center.image, centerSlot, dp(84f), centerTile)
        centerPanel.set(centerTile)
        centerPanel.inset(-dp(3f), -dp(3f))
        centerShape.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(imageCornerRadius(selection.center.image, centerTile) + dp(3f))
            .build()
        val leftSlot = RectF(card.left + dp(8f), card.top + dp(8f),
            centerSlot.left - dp(6f), card.bottom - dp(8f))
        val rightSlot = RectF(centerSlot.right + dp(6f), card.top + dp(8f),
            card.right - dp(8f), card.bottom - dp(8f))
        leftTile.setEmpty()
        rightTile.setEmpty()
        selection.left?.let { fitImage(it.image, leftSlot, dp(64f), leftTile) }
        selection.right?.let { fitImage(it.image, rightSlot, dp(64f), rightTile) }
        return !centerTile.isEmpty && (selection.left == null || !leftTile.isEmpty) &&
            (selection.right == null || !rightTile.isEmpty)
    }

    private fun fitImage(image: WubiImageRect, slot: RectF, maxHeight: Float, destination: RectF) {
        destination.setEmpty()
        if (slot.width() <= 0 || slot.height() <= 0) return
        val scale = min(slot.width() / image.width, min(slot.height(), maxHeight) / image.height)
        if (!scale.isFinite() || scale <= 0f) return
        val tileWidth = image.width * scale
        val tileHeight = image.height * scale
        destination.set(slot.centerX() - tileWidth / 2f, slot.centerY() - tileHeight / 2f,
            slot.centerX() + tileWidth / 2f, slot.centerY() + tileHeight / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = selection ?: return
        if (imageShader == null) return
        outerShape.setBounds(card.left.roundToInt(), card.top.roundToInt(),
            card.right.roundToInt(), card.bottom.roundToInt())
        outerShape.draw(canvas)
        centerShape.setBounds(centerPanel.left.roundToInt(), centerPanel.top.roundToInt(),
            centerPanel.right.roundToInt(), centerPanel.bottom.roundToInt())
        centerShape.draw(canvas)
        current.left?.let { drawImage(canvas, it.image, leftTile) }
        drawImage(canvas, current.center.image, centerTile)
        current.right?.let { drawImage(canvas, it.image, rightTile) }
    }

    private fun drawImage(canvas: Canvas, image: WubiImageRect, destination: RectF) {
        val shader = imageShader ?: return
        sourceRect.set(image.left.toFloat(), image.top.toFloat(), image.right.toFloat(), image.bottom.toFloat())
        imageMatrix.setRectToRect(sourceRect, destination, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(imageMatrix)
        // An anti-aliased shape follows the pictured border, excluding the white corner gutters.
        // The shader samples the original JPEG directly; roots are never redrawn or recolored.
        val radius = imageCornerRadius(image, destination)
        canvas.drawRoundRect(destination, radius, radius, imagePaint)
    }

    private fun imageCornerRadius(image: WubiImageRect, destination: RectF): Float =
        Wubi06Chart.TILE_CORNER_RADIUS * destination.width() / image.width

    override fun onDetachedFromWindow() {
        hideHint()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pending = pendingHint
        if (pending != null && w > 0 && h > 0) {
            val generation = animationGeneration
            post {
                if (generation == animationGeneration && pendingHint === pending) {
                    pendingHint = null
                    showHint(pending.selection, pending.keyboardBounds)
                }
            }
        } else if (w != oldw || h != oldh) {
            hideHint()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        hideHint()
        super.onConfigurationChanged(newConfig)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** One shared, unscaled decode across input-view recreations; never decoded while drawing. */
    private object ChartBitmapCache {
        @Volatile private var attempted = false
        private var bitmap: Bitmap? = null

        fun get(resources: Resources): Bitmap? {
            if (!attempted) synchronized(this) {
                if (!attempted) {
                    val candidate = runCatching {
                        BitmapFactory.decodeResource(resources, R.drawable.wubi06_root_chart,
                            BitmapFactory.Options().apply {
                                inScaled = false
                                inDensity = 0
                                inTargetDensity = 0
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            })
                    }.getOrNull()
                    if (candidate != null && candidate.width == Wubi06Chart.IMAGE_WIDTH &&
                        candidate.height == Wubi06Chart.IMAGE_HEIGHT) {
                        bitmap = candidate
                    } else {
                        candidate?.recycle()
                    }
                    attempted = true
                }
            }
            return bitmap
        }
    }
}
