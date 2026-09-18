package com.destino.app.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

internal fun NavController.navigateToTopLevel(screen: Screen) {
    if (currentDestination?.route == screen.route) return

    val startDestination = graph.findStartDestination()
    // Return to the existing Home entry instead of recreating it or restoring
    // the stack of another tab that was saved against the start destination.
    if (screen.route == startDestination.route) {
        if (popBackStack(startDestination.id, inclusive = false, saveState = true)) {
            return
        }
        val fallbackRoute = startDestination.route ?: Screen.Home.route
        if (popBackStack(fallbackRoute, inclusive = false, saveState = true)) {
            return
        }
        if (popBackStack(startDestination.id, inclusive = false, saveState = false)) {
            return
        }
        if (popBackStack(fallbackRoute, inclusive = false, saveState = false)) {
            return
        }
    }

    navigate(screen.route) {
        popUpTo(startDestination.id) { saveState = true }
        launchSingleTop = true
        // If Home was absent, create it without restoring another tab's stack.
        restoreState = screen.route != startDestination.route
    }
}
