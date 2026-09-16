// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

class ControlsBackgroundView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
	private var downX = 0f
	private var downY = 0f
	private var moved = false

	init
	{
		isClickable = true
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				downX = event.x
				downY = event.y
				moved = false
			}
			MotionEvent.ACTION_MOVE ->
				moved = moved || abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
			MotionEvent.ACTION_UP -> if(!moved) performClick()
		}
		return true
	}

	override fun performClick(): Boolean
	{
		super.performClick()
		return true
	}
}
