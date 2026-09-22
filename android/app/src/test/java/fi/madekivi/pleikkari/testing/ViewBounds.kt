// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.testing

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * PLE-523: a reusable bounds assertion for Robolectric view tests. The rule it checks is the
 * one from PLE-242: nothing the user must tap may sit outside the window, be clipped by an
 * ancestor, or overlap any other tappable view.
 *
 * Positions come from the real measure/layout pass of an attached window, so real layout
 * resources are exercised (layout-land, dimens, the window-size qualifiers set by the test).
 */
object ViewBounds
{
	/** Runs pending traversals (layout, scroll, adapter updates) on the paused main looper. */
	fun settle()
	{
		repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
	}

	fun View.boundsInWindow(): Rect
	{
		val location = IntArray(2)
		getLocationInWindow(location)
		return Rect(location[0], location[1], location[0] + width, location[1] + height)
	}

	/** Every visible descendant of [root] (including [root]) matching [predicate], depth first. */
	fun collect(root: View, predicate: (View) -> Boolean): List<View> = buildList {
		fun visit(view: View)
		{
			if(!view.isShown)
				return
			if(predicate(view))
				add(view)
			if(view is ViewGroup)
				for(index in 0 until view.childCount)
					visit(view.getChildAt(index))
		}
		visit(root)
	}

	fun collectClickable(root: View): List<View> =
		collect(root) { it.isClickable && it.width > 0 && it.height > 0 }

	private fun View.isAncestorOf(other: View): Boolean
	{
		var parent: ViewParent? = other.parent
		while(parent != null)
		{
			if(parent === this)
				return true
			parent = parent.parent
		}
		return false
	}

	/**
	 * Scrolls [target] into view the way keyboard or accessibility focus would
	 * ([View.requestRectangleOnScreen] through every scrolling ancestor), then fails unless it is
	 * entirely inside [root]'s bounds, not clipped by any ancestor, and not intersecting the
	 * visible part of any view in [obstacles] other than itself, its ancestors and descendants.
	 *
	 * @param obstacles evaluated after the scroll, so list positions are current.
	 */
	fun assertReachable(root: View, target: View, scenario: String, obstacles: () -> List<View>)
	{
		val name = target.describe()
		if(!target.isShown || target.width == 0 || target.height == 0)
			fail("$scenario: $name is not laid out (shown=${target.isShown}, ${target.width}x${target.height})")
		target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
		settle()

		val viewport = root.boundsInWindow()
		val bounds = target.boundsInWindow()
		if(!viewport.contains(bounds))
			fail("$scenario: $name at $bounds is not fully inside the window $viewport")

		val visible = Rect()
		if(!target.getGlobalVisibleRect(visible) || visible.width() != bounds.width() || visible.height() != bounds.height())
			fail("$scenario: $name at $bounds is clipped by an ancestor to $visible")

		for(other in obstacles())
		{
			if(other === target || other.isAncestorOf(target) || target.isAncestorOf(other))
				continue
			// Only the part an ancestor does not clip can be seen or receive a touch.
			val otherBounds = Rect()
			if(other.getGlobalVisibleRect(otherBounds) && Rect.intersects(bounds, otherBounds))
				fail("$scenario: $name at $bounds overlaps ${other.describe()} visible at $otherBounds")
		}
	}

	fun View.describe(): String =
		if(id != View.NO_ID)
			runCatching { resources.getResourceEntryName(id) }.getOrDefault("#$id")
		else
			javaClass.simpleName
}
