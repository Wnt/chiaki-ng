// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.ManualHost
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.databinding.ItemDisplayHostBinding
import com.metallic.chiaki.testing.ViewBounds
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PLE-523: the gated bounds assertion PLE-242 asked for. Inflates the real home layout
 * (layout/ or layout-land/activity_main.xml, picked by the window's resource qualifiers), fills
 * it the way MainActivity does, and asserts that every action the user must tap can be scrolled
 * fully on screen with nothing tappable on top of it.
 *
 * Window sizes are the test phones' default display sizes in dp. The S22 Ultra's default
 * resolution is 1080x2316 at 450 dpi (384x824 dp); the S25 Ultra's is 1440x3120 at 3.5x
 * (411x891 dp). The density bucket does not change the layout in dp.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenBoundsTest(
	private val window: String,
	private val qualifiers: String,
	private val showSummary: Boolean,
	private val consoleCount: Int
)
{
	companion object
	{
		private val WINDOWS = listOf(
			"S22 Ultra portrait" to "w384dp-h824dp-port-xxhdpi",
			"S22 Ultra landscape" to "w824dp-h384dp-land-xxhdpi",
			"S25 Ultra portrait" to "w411dp-h891dp-port-xxxhdpi",
			"S25 Ultra landscape" to "w891dp-h411dp-land-xxxhdpi"
		)

		@JvmStatic
		@ParameterizedRobolectricTestRunner.Parameters(name = "{0}, summary={2}, consoles={3}")
		fun parameters(): List<Array<Any>> = buildList {
			for((window, qualifiers) in WINDOWS)
				for(showSummary in listOf(false, true))
					for(consoleCount in listOf(0, 1, 3))
						add(arrayOf(window, qualifiers, showSummary, consoleCount))
		}

		/** One of each card shape: plain, Standby (adds Wake), manual host (adds the menu). */
		private fun consoles(count: Int): List<HomeConsole> = List(count) { index ->
			when(index % 3)
			{
				0 -> HomeConsole("on-$index", "Living Room PS5", "Astro's Playroom", HomeConsoleStatus.ON)
				1 -> HomeConsole("standby-$index", "Bedroom PS5", null, HomeConsoleStatus.STANDBY)
				else -> HomeConsole(
					"manual-$index", "Office PS5", "10.0.0.8", HomeConsoleStatus.ON,
					displayHost = ManualDisplayHost(null, ManualHost(host = "10.0.0.8", registeredHost = null))
				)
			}
		}
	}

	private val scenario get() = "$window, summary=$showSummary, consoles=$consoleCount"

	@Test
	fun everyHomeActionIsReachableAndUnobstructed()
	{
		RuntimeEnvironment.setQualifiers(qualifiers)
		val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
		controller.get().setTheme(R.style.AppTheme)
		val activity = controller.setup().get()
		val binding = ActivityMainBinding.inflate(LayoutInflater.from(activity))
		activity.setContentView(binding.root)
		HomeActionLayout.install(binding)

		// "Add by address" is the longer of the two labels MainActivity.updateHomeState() gives it.
		binding.addConsoleButton.setText(R.string.action_add_by_address)
		binding.streamSummaryCard.visibility = if(showSummary) View.VISIBLE else View.GONE
		binding.emptyInfoLayout.visibility = if(consoleCount == 0) View.VISIBLE else View.GONE
		val adapter = DisplayHostRecyclerViewAdapter({}, {}, {}, {})
		binding.hostsRecyclerView.adapter = adapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(activity)
		adapter.consoles = consoles(consoleCount)
		ViewBounds.settle()

		val root = binding.root
		val obstacles = { ViewBounds.collectClickable(root) + binding.appBarLayout }
		ViewBounds.assertReachable(root, binding.addConsoleButton, scenario, obstacles)
		if(showSummary)
			ViewBounds.assertReachable(root, binding.summaryDismissButton, scenario, obstacles)

		assertEquals("$scenario: console rows", consoleCount, adapter.itemCount)
		for(position in 0 until consoleCount)
		{
			binding.hostsRecyclerView.scrollToPosition(position)
			ViewBounds.settle()
			val holder = binding.hostsRecyclerView.findViewHolderForAdapterPosition(position)
				as? DisplayHostRecyclerViewAdapter.ConsoleViewHolder
				?: throw AssertionError("$scenario: console $position was not laid out; the console list " +
					"is ${binding.hostsRecyclerView.width}x${binding.hostsRecyclerView.height} px")
			val card: ItemDisplayHostBinding = holder.binding
			val actions = listOf(card.playButton, card.wakeButton, card.menuButton).filter { it.isShown }
			for(action in actions)
				ViewBounds.assertReachable(root, action, "$scenario, console $position", obstacles)
		}
		controller.pause().stop().destroy()
	}
}
