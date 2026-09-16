// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.remote

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Puts a red "PSN MOCK" strip over the status bar of every activity, so a build pointed at the
 * mock can never be mistaken for one that talks to Sony. Registered only by the manifest a
 * -PchiakiPsnMock build merges (src/psnMock/AndroidManifest.xml); a provider starts before any activity.
 */
class PsnMockBanner : ContentProvider()
{
	override fun onCreate(): Boolean
	{
		val host = psnMockHost() ?: return true
		Log.w("PsnMock", "PSN MOCK BUILD: sign-in and console list go to https://$host, not Sony")
		(context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(Callbacks("PSN MOCK · $host"))
		return true
	}

	private class Callbacks(private val label: String) : Application.ActivityLifecycleCallbacks
	{
		override fun onActivityResumed(activity: Activity)
		{
			val decor = activity.window?.decorView as? ViewGroup ?: return
			if(decor.findViewWithTag<View>(TAG) != null)
				return
			val banner = TextView(activity).apply {
				tag = TAG
				text = label
				setTextColor(Color.WHITE)
				setBackgroundColor(Color.rgb(0xC6, 0x28, 0x28))
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				gravity = Gravity.CENTER
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
				isClickable = false
				isFocusable = false
			}
			val minHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, activity.resources.displayMetrics).toInt()
			decor.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minHeight, Gravity.TOP))
			ViewCompat.setOnApplyWindowInsetsListener(banner) { view, insets ->
				val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
				view.layoutParams = view.layoutParams.apply { height = maxOf(top, minHeight) }
				insets
			}
			ViewCompat.requestApplyInsets(banner)
		}

		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
		override fun onActivityStarted(activity: Activity) {}
		override fun onActivityPaused(activity: Activity) {}
		override fun onActivityStopped(activity: Activity) {}
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
		override fun onActivityDestroyed(activity: Activity) {}
	}

	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
	override fun getType(uri: Uri): String? = null
	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

	private companion object
	{
		const val TAG = "psn-mock-banner"
	}
}
