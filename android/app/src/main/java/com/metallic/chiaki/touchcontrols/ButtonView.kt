// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import com.metallic.chiaki.R
import kotlin.math.min

class ButtonView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	private val haptics = ButtonHaptics(context)

	var buttonPressed = false
		private set(value)
		{
			val diff = field != value
			field = value
			if(diff)
			{
				if(value)
					haptics.trigger()
				invalidate()
				buttonPressedCallback?.let { it(field) }
			}
		}

	var buttonPressedCallback: ((Boolean) -> Unit)? = null

	private val drawableIdle: Drawable?
	private val drawablePressed: Drawable?

	/**
	 * Draw the glyph at its own aspect ratio, centred, instead of stretching it to
	 * fill the content box. A hit target has to stay at least 48dp and may be a
	 * capsule; the mark inside it does not have to be stretched to match, which is
	 * what made Create and Options read as squashed letters (PLE-342).
	 */
	private val fitCenter: Boolean

	/** Cached in [onSizeChanged] so [onDraw] stays a setBounds plus a draw. */
	private val contentBounds = Rect()

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.ButtonView, 0, 0).apply {
			drawableIdle = getDrawable(R.styleable.ButtonView_drawableIdle)
			drawablePressed = getDrawable(R.styleable.ButtonView_drawablePressed)
			fitCenter = getBoolean(R.styleable.ButtonView_drawableFitCenter, false)
			recycle()
		}

		isClickable = true
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		updateContentBounds()
	}

	private fun updateContentBounds()
	{
		val left = paddingLeft
		val top = paddingTop
		val right = width - paddingRight
		val bottom = height - paddingBottom
		val drawable = drawableIdle ?: drawablePressed
		val intrinsicWidth = drawable?.intrinsicWidth ?: 0
		val intrinsicHeight = drawable?.intrinsicHeight ?: 0
		if(!fitCenter || intrinsicWidth <= 0 || intrinsicHeight <= 0 || right <= left || bottom <= top)
		{
			contentBounds.set(left, top, right, bottom)
			return
		}
		val scale = min(
			(right - left).toFloat() / intrinsicWidth,
			(bottom - top).toFloat() / intrinsicHeight
		)
		val width = (intrinsicWidth * scale).toInt()
		val height = (intrinsicHeight * scale).toInt()
		val centerX = (left + right) / 2
		val centerY = (top + bottom) / 2
		contentBounds.set(
			centerX - width / 2,
			centerY - height / 2,
			centerX - width / 2 + width,
			centerY - height / 2 + height
		)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val drawable = if(buttonPressed) drawablePressed else drawableIdle
		drawable?.bounds = contentBounds
		drawable?.draw(canvas)
	}

	/**
	 * If this button overlaps with others in the same layout,
	 * let the one whose center is closest to the touch handle it.
	 */
	private fun bestFittingTouchView(x: Float, y: Float): View
	{
		val loc = locationOnScreen + Vector(x, y)
		return (parent as? ViewGroup)?.children?.filter {
			it is ButtonView
		}?.filter {
			val pos = it.locationOnScreen
			loc.x >= pos.x && loc.x < pos.x + it.width && loc.y >= pos.y && loc.y < pos.y + it.height
		}?.sortedBy {
			(loc - (it.locationOnScreen + Vector(it.width.toFloat(), it.height.toFloat()) * 0.5f)).lengthSq
		}?.firstOrNull() ?: this
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				if(bestFittingTouchView(event.getX(event.actionIndex), event.getY(event.actionIndex)) != this)
					return false
				buttonPressed = true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
				buttonPressed = false
			}
		}
		return true
	}
}