// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import kotlin.math.abs

class AnalogStickView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	val radius: Float
	private val handleRadius: Float
	private val drawableBase: Drawable?
	private val drawableHandle: Drawable?

	var state = Vector(0f, 0f)
		private set(value)
		{
			field = value
			stateChangedCallback?.let { it(field) }
		}

	var stateChangedCallback: ((Vector) -> Unit)? = null

	private val touchTracker = TouchTracker().also {
		it.positionChangedCallback = this::updateState
	}

	private var center: Vector? = null

	/**
	 * Where the gate is drawn while nothing is touching it. The stick itself stays
	 * relative -- it still centres on wherever the thumb lands -- but an untouched
	 * stick used to draw nothing at all, which is why its position was
	 * undiscoverable (PLE-342). Drawing the gate and knob at the view's centre
	 * costs no per-frame work: this View is invalidated on input, not on video
	 * frames, and the overlay layer it lives in is composited every frame either
	 * way.
	 */
	private var homeCenter: Vector = Vector(0f, 0f)

	private var drawnAlpha = -1

	/**
	 * Same as state, but scaled to the circle
	 */
	private var handlePosition: Vector = Vector(0f, 0f)

	private val clipBoundsTmp = Rect()

	private val coalesceRedraw = Preferences(context).coalesceTouchRedrawEnabled
	private var redrawPending = false

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.AnalogStickView, 0, 0).apply {
			radius = getDimension(R.styleable.AnalogStickView_radius, 0f)
			handleRadius = getDimension(R.styleable.AnalogStickView_handleRadius, 0f)
			drawableBase = getDrawable(R.styleable.AnalogStickView_drawableBase)
			drawableHandle = getDrawable(R.styleable.AnalogStickView_drawableHandle)
			recycle()
		}
	}

	private fun requestRedraw()
	{
		if(!coalesceRedraw)
		{
			invalidate()
			return
		}
		if(!redrawPending)
		{
			redrawPending = true
			postInvalidateOnAnimation()
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		homeCenter = Vector(w * 0.5f, h * 0.5f)
	}

	override fun onDraw(canvas: Canvas)
	{
		redrawPending = false
		super.onDraw(canvas)

		if(radius <= 0f)
			return

		val engaged = center
		val center = engaged ?: homeCenter
		val alpha = if(engaged != null) ALPHA_ENGAGED else ALPHA_AT_REST
		if(alpha != drawnAlpha)
		{
			drawnAlpha = alpha
			drawableBase?.alpha = alpha
			drawableHandle?.alpha = alpha
		}

		val circleRadius = radius + handleRadius
		drawableBase?.setBounds((center.x - circleRadius).toInt(), (center.y - circleRadius).toInt(), (center.x + circleRadius).toInt(), (center.y + circleRadius).toInt())
		drawableBase?.draw(canvas)

		val handleX = center.x + handlePosition.x * radius
		val handleY = center.y + handlePosition.y * radius
		drawableHandle?.setBounds((handleX - handleRadius).toInt(), (handleY - handleRadius).toInt(), (handleX + handleRadius).toInt(),(handleY + handleRadius).toInt())
		drawableHandle?.draw(canvas)
	}

	private fun updateState(position: Vector?)
	{
		if(radius <= 0f)
			return

		if(position == null)
		{
			center = null
			state = Vector(0f, 0f)
			handlePosition = Vector(0f, 0f)
			requestRedraw()
			return
		}

		val center: Vector = this.center ?: position
		this.center = center

		val dir = position - center
		val length = dir.length
		if(length > 0)
		{
			val strength = if(length > radius) 1.0f else length / radius
			val dirNormalized = dir / length
			handlePosition = dirNormalized * strength
			val dirBoxNormalized =
				if(abs(dirNormalized.x) > abs(dirNormalized.y))
					dirNormalized / abs(dirNormalized.x)
				else
					dirNormalized / abs(dirNormalized.y)
			state = dirBoxNormalized * strength
		}
		else
		{
			handlePosition = Vector(0f, 0f)
			state = Vector(0f, 0f)
		}

		requestRedraw()
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if(coalesceRedraw && event.actionMasked == MotionEvent.ACTION_DOWN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
		touchTracker.touchEvent(event)
		return true
	}

	companion object
	{
		private const val ALPHA_ENGAGED = 255
		private const val ALPHA_AT_REST = 178
	}
}
