// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.remote

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import fi.madekivi.pleikkari.main.MainActivity
import fi.madekivi.pleikkari.regist.PsnBrowserRecovery
import fi.madekivi.pleikkari.regist.PsnLoginActivity
import fi.madekivi.pleikkari.regist.PsnRedirectReceiver
import java.util.WeakHashMap

/**
 * PLE-302: puts back, on request, one of the onboarding defects users found, so the onboarding
 * driver (android/psn-mock/onboarding_test.py --fault) can show that it catches each of them.
 * Only a -PchiakiPsnMock build runs this (from [PsnMockBanner]), and only while the device property
 * `debug.pleikkari.psnmock.fault` names a fault; `adb shell setprop` sets it and the next app start
 * reads it. No fault, the default, leaves the app exactly as it ships.
 */
internal enum class PsnMockFault(val id: String)
{
	/** The tab's Finish sign-in does nothing: the user is left on Sony's blank redirect page. */
	REDIRECT_DEAD_END("redirect-dead-end"),
	/** Coming back from the browser sends the user to Android's link settings (PLE-245, PLE-246). */
	SETTINGS_REDIRECT("settings-redirect"),
	/** An instruction paragraph on the sign-in path (PLE-279 removed the last one). */
	INSTRUCTION_PARAGRAPH("instruction-paragraph"),
	/** Leaving the tab by its X, back or "Open in browser" strands the user on Continue signing in (PLE-323). */
	EXIT_LOSES_CODE("exit-loses-code");

	companion object
	{
		const val PROPERTY = "debug.pleikkari.psnmock.fault"
		private const val TAG = "PsnMock"

		fun install(context: Context)
		{
			val fault = read()
			// A disabled receiver outlives the process, so a run without the fault must turn it back on.
			context.packageManager.setComponentEnabledSetting(
				ComponentName(context, PsnRedirectReceiver::class.java),
				if(fault == REDIRECT_DEAD_END) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
				else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
				PackageManager.DONT_KILL_APP
			)
			PsnBrowserRecovery.enabled = fault != EXIT_LOSES_CODE
			if(fault == null)
				return
			Log.w(TAG, "PSN MOCK FAULT ${fault.id}: this build is deliberately broken for the onboarding driver")
			(context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(Callbacks(fault))
		}

		private fun read(): PsnMockFault?
		{
			val value = try
			{
				Runtime.getRuntime().exec(arrayOf("getprop", PROPERTY)).inputStream.bufferedReader().use { it.readText() }.trim()
			}
			catch(e: Exception)
			{
				Log.w(TAG, "cannot read $PROPERTY", e)
				""
			}
			return values().firstOrNull { it.id == value }
		}
	}

	private class Callbacks(private val fault: PsnMockFault) : Application.ActivityLifecycleCallbacks
	{
		private val resumes = WeakHashMap<Activity, Int>()
		private val paused = WeakHashMap<Activity, Boolean>()

		override fun onActivityResumed(activity: Activity)
		{
			val count = (resumes[activity] ?: 0) + 1
			resumes[activity] = count
			when(fault)
			{
				REDIRECT_DEAD_END, EXIT_LOSES_CODE -> Unit
				// Resumed after a pause: back from the browser tab.
				SETTINGS_REDIRECT -> if(activity is PsnLoginActivity && paused[activity] == true)
					activity.startActivity(linkSettings(activity))
				INSTRUCTION_PARAGRAPH -> if((activity is MainActivity || activity is PsnLoginActivity) && count == 1)
					addParagraph(activity)
			}
		}

		private fun linkSettings(activity: Activity): Intent
		{
			val uri = Uri.parse("package:${activity.packageName}")
			val action = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
				else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
			return Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}

		private fun addParagraph(activity: Activity)
		{
			val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
			content.addView(TextView(activity).apply {
				text = "To sign in, open Settings, choose Apps, find this app, tap Open by default and add the PlayStation link before you continue."
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
		}

		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
		override fun onActivityStarted(activity: Activity) {}
		override fun onActivityPaused(activity: Activity)
		{
			paused[activity] = true
		}

		override fun onActivityStopped(activity: Activity) {}
		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
		override fun onActivityDestroyed(activity: Activity) {}
	}
}
