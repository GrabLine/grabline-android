package dev.grabline.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.grabline.app.R
import dev.grabline.app.data.AppSettings
import dev.grabline.app.ui.settings.SettingsRoute
import dev.grabline.app.ui.welcome.WelcomeScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object Routes {
    const val DOWNLOADS = "downloads"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
}

private data class Tab(val route: String, val labelRes: Int, @DrawableRes val iconRes: Int)

private val tabs = listOf(
    Tab(Routes.DOWNLOADS, R.string.tab_downloads, R.drawable.ic_tab_downloads),
    Tab(Routes.DEVICES, R.string.tab_devices, R.drawable.ic_tab_devices),
    Tab(Routes.SETTINGS, R.string.tab_settings, R.drawable.ic_tab_settings),
)

private val WideBreakpoint = 600.dp

@Composable
fun GrabLineAppShell(
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    settings: AppSettings = AppSettings(LocalContext.current),
) {
    // Welcome shows on first run; null means the flag has not loaded yet.
    val welcomeDone by remember(settings) { settings.observe(AppSettings.WELCOME_DONE) }
        .collectAsState(initial = null)
    var startRoute by remember { mutableStateOf(Routes.DOWNLOADS) }
    if (welcomeDone == null) {
        Box(modifier.fillMaxSize())
        return
    }
    if (welcomeDone == false) {
        WelcomeScreen(settings, onPairPc = { startRoute = Routes.DEVICES }, modifier = modifier)
        return
    }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onDownloads = destination?.hierarchy?.any { it.route == Routes.DOWNLOADS } == true

    fun select(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val addDescription = stringResource(R.string.add_a_link)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WideBreakpoint
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val label = stringResource(tab.labelRes)
                            NavigationBarItem(
                                selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                                onClick = { select(tab.route) },
                                icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
                                label = { Text(label) },
                                modifier = Modifier.semantics { contentDescription = label },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (onDownloads) {
                    FloatingActionButton(
                        onClick = onAddClick,
                        modifier = Modifier.semantics { contentDescription = addDescription },
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail {
                        tabs.forEach { tab ->
                            val label = stringResource(tab.labelRes)
                            NavigationRailItem(
                                selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                                onClick = { select(tab.route) },
                                icon = { Icon(painterResource(tab.iconRes), contentDescription = null) },
                                label = { Text(label) },
                                modifier = Modifier.semantics { contentDescription = label },
                            )
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = startRoute,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                ) {
                    composable(Routes.DOWNLOADS) { PlaceholderScreen(R.string.tab_downloads) }
                    composable(Routes.DEVICES) { PlaceholderScreen(R.string.tab_devices) }
                    composable(Routes.SETTINGS) { SettingsRoute(settings, onPairPc = { select(Routes.DEVICES) }) }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(titleRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(titleRes))
    }
}
