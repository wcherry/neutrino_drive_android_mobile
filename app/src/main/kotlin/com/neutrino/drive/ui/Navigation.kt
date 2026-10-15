package com.neutrino.drive.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.neutrino.drive.core.drive.DriveItem
import com.neutrino.drive.ui.files.Place
import java.io.File

/** Every screen below the tab bar. */
sealed interface Route {
    data class Browse(val place: Place) : Route
    data object Search : Route
    data object Offline : Route
    data class Zip(val file: File, val name: String) : Route
    data class Web(val item: DriveItem) : Route
    data class Versions(val item: DriveItem) : Route
    data class Share(val item: DriveItem) : Route
    data object Settings : Route
    data object KeyOptions : Route
    data object Pairing : Route
    data object RecoveryKit : Route
    data object KeyCode : Route
    data object Provision : Route
    data object ShowKit : Route
    data object PhotoBackup : Route
}

enum class Tab(val label: String) { FILES("Files"), RECENTS("Recents"), OFFLINE("Offline"), SETTINGS("Settings") }

/**
 * One back stack per tab, as iOS's `TabView` of `NavigationStack`s behaves: switching tabs keeps
 * where each one was. Held in a ViewModel so rotation does not lose it.
 */
class NavViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.FILES)
    private val stacks = mutableStateMapOf<Tab, List<Route>>().apply {
        put(Tab.FILES, listOf(Route.Browse(Place.root)))
        put(Tab.RECENTS, listOf(Route.Browse(Place.Recents)))
        put(Tab.OFFLINE, listOf(Route.Offline))
        put(Tab.SETTINGS, listOf(Route.Settings))
    }

    val stack: List<Route> get() = stacks.getValue(tab)
    val current: Route get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    fun push(route: Route) {
        stacks[tab] = stack + route
    }

    fun pop(): Boolean {
        if (!canPop) return false
        stacks[tab] = stack.dropLast(1)
        return true
    }

    /** Replace the whole stack — switching My Drive / Starred / Shared / Trash. */
    fun root(route: Route) {
        stacks[tab] = listOf(route)
    }

    /** Re-selecting the current tab returns it to its root. */
    fun select(t: Tab) {
        if (t == tab) stacks[t] = stacks.getValue(t).take(1) else tab = t
    }

    fun pushOn(t: Tab, route: Route) {
        tab = t
        stacks[t] = stacks.getValue(t) + route
    }
}
