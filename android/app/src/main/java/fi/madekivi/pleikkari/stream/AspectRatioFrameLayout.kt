package fi.madekivi.pleikkari.stream

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

// see ExoPlayer's AspectRatioFrameLayout
class AspectRatioFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): FrameLayout(context, attrs)
{
	companion object
	{
		private const val MAX_ASPECT_RATIO_DEFORMATION_FRACTION = 0.01f
	}

	var aspectRatio = 0f
		set(value)
		{
			if(field != value)
			{
				field = value
				requestLayout()
			}
		}

	var mode: TransformMode = TransformMode.FIT
		set(value)
		{
			if(field != value)
			{
				field = value
				requestLayout()
			}
		}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
	{
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		if(aspectRatio <= 0)
		{
			// Aspect ratio not set.
			return
		}
		var width = measuredWidth
		var height = measuredHeight
		val viewAspectRatio = width.toFloat() / height
		val aspectDeformation = aspectRatio / viewAspectRatio - 1
		if(Math.abs(aspectDeformation) <= MAX_ASPECT_RATIO_DEFORMATION_FRACTION)
			return
		// A portrait window always fits the video, whatever the transform mode: the video keeps a
		// finite height at the top and the touch controls own the rest of the window (PLE-341).
		val measureMode = if(height > width) TransformMode.FIT else mode
		when(measureMode)
		{
			TransformMode.ZOOM -> 
				if(aspectDeformation > 0)
					width = (height * aspectRatio).toInt()
				else
					height = (width / aspectRatio).toInt()
			TransformMode.FIT ->
				if(aspectDeformation > 0)
					height = (width / aspectRatio).toInt()
				else
					width = (height * aspectRatio).toInt()
			TransformMode.STRETCH -> {}
		}
		super.onMeasure(
			MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
		)
	}
}
