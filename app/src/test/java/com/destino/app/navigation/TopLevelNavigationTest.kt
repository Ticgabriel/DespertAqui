package com.destino.app.navigation

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TopLevelNavigationTest {
    private fun controller(savedState: Bundle? = null): TestNavHostController =
        TestNavHostController(RuntimeEnvironment.getApplication()).apply {
            setViewModelStore(ViewModelStore())
            navigatorProvider.addNavigator(ComposeNavigator())
            savedState?.let { restoreState(it) }
            graph = createGraph(startDestination = Screen.Home.route) {
                bottomNavScreens.forEach { screen -> composable(screen.route) {} }
                composable(Screen.AudioSettings.route) {}
                composable(Screen.AlarmEditor.route) {}
            }
        }

    @Test
    fun homeReturnsToTheExistingHomeEntryFromEveryTab() {
        bottomNavScreens.drop(1).forEach { tab ->
            val navController = controller()
            val homeEntry = navController.currentBackStackEntry

            navController.navigateToTopLevel(tab)
            navController.navigateToTopLevel(Screen.Home)

            assertEquals(Screen.Home.route, navController.currentDestination?.route)
            assertSame(homeEntry, navController.currentBackStackEntry)
            assertNull(navController.previousBackStackEntry)
        }
    }

    @Test
    fun repeatedTabSwitchesAlwaysReachTheSelectedTab() {
        val navController = controller()
        repeat(3) {
            listOf(Screen.Alarms, Screen.Library, Screen.Settings, Screen.Home).forEach { tab ->
                navController.navigateToTopLevel(tab)
                assertEquals(tab.route, navController.currentDestination?.route)
            }
        }
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun reselectingATabKeepsItsEntryAndBackReturnsHome() {
        val navController = controller()
        navController.navigateToTopLevel(Screen.Alarms)
        val alarmEntry = navController.currentBackStackEntry
        repeat(5) { navController.navigateToTopLevel(Screen.Alarms) }
        assertSame(alarmEntry, navController.currentBackStackEntry)
        assertTrue(navController.popBackStack())
        assertEquals(Screen.Home.route, navController.currentDestination?.route)
    }

    @Test
    fun returningHomePreservesOtherTabsSavedState() {
        val navController = controller()
        navController.navigateToTopLevel(Screen.Alarms)
        navController.currentBackStackEntry!!.savedStateHandle["scrollPosition"] = 12
        navController.navigateToTopLevel(Screen.Home)
        navController.navigateToTopLevel(Screen.Alarms)
        assertEquals(12, navController.currentBackStackEntry!!.savedStateHandle.get<Int>("scrollPosition"))
    }

    @Test
    fun homeWorksAfterControllerStateIsRestored() {
        val original = controller()
        original.navigateToTopLevel(Screen.Alarms)
        original.navigateToTopLevel(Screen.Library)
        val restored = controller(original.saveState())
        restored.navigateToTopLevel(Screen.Home)
        assertEquals(Screen.Home.route, restored.currentDestination?.route)
        assertNull(restored.previousBackStackEntry)
    }

    @Test
    fun homeWorksAfterOpeningATabThroughTheOldShortcut() {
        val navController = controller()
        navController.navigate(Screen.Alarms.route)
        navController.navigateToTopLevel(Screen.Library)
        navController.navigateToTopLevel(Screen.Home)
        assertEquals(Screen.Home.route, navController.currentDestination?.route)
    }

    @Test
    fun settingsDetailBackReturnsToSettingsThenHome() {
        val navController = controller()
        navController.navigateToTopLevel(Screen.Settings)
        navController.navigate(Screen.AudioSettings.route)
        assertTrue(navController.popBackStack())
        assertEquals(Screen.Settings.route, navController.currentDestination?.route)
        navController.navigateToTopLevel(Screen.Home)
        assertEquals(Screen.Home.route, navController.currentDestination?.route)
    }
}
