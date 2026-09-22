// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package fi.madekivi.pleikkari.settings

import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import fi.madekivi.pleikkari.R
import fi.madekivi.pleikkari.common.ext.applySystemBarInsets
import fi.madekivi.pleikkari.common.ext.enableAppEdgeToEdge
import fi.madekivi.pleikkari.databinding.ActivitySettingsBinding

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	private lateinit var binding: ActivitySettingsBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		enableAppEdgeToEdge()
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.applySystemBarInsets(top = false)
		binding.toolbar.applySystemBarInsets(left = false, right = false, bottom = false)
		title = ""
		setSupportActionBar(binding.toolbar)

		val rootFragment = SettingsFragment()
		replaceFragment(rootFragment, false)
		supportFragmentManager.addOnBackStackChangedListener {
			val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment ?: return@addOnBackStackChangedListener
			binding.titleTextView.text = titleFragment.getTitle(resources)
		}
		binding.titleTextView.text = rootFragment.getTitle(resources)
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference) = when(pref.fragment)
	{
		SettingsRegisteredHostsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsRegisteredHostsFragment(), true)
			true
		}
		SettingsLogsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsLogsFragment(), true)
			true
		}
		ControllerSettingsFragment::class.java.canonicalName -> {
			replaceFragment(ControllerSettingsFragment(), true)
			true
		}
		ControllerMappingSettingsFragment::class.java.canonicalName -> {
			replaceFragment(ControllerMappingSettingsFragment(), true)
			true
		}
		else -> false
	}

	fun openDeveloperSettings()
	{
		replaceFragment(DeveloperSettingsFragment(), true)
	}

	private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean)
	{
		supportFragmentManager.beginTransaction()
			.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
			.replace(R.id.settingsFragment, fragment)
			.also {
				if(addToBackStack)
					it.addToBackStack(null)
			}
			.commit()
	}
}
