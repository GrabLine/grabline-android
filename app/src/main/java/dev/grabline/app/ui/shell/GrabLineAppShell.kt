package dev.grabline.app.ui.shell

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.grabline.app.R
import dev.grabline.app.data.AppSettings
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.add.AddSheetRoute
import dev.grabline.app.ui.details.DownloadDetailsRoute
import dev.grabline.app.ui.devices.DevicesRoute
import dev.grabline.app.ui.devices.PairingController
import dev.grabline.app.ui.downloads.DownloadsRoute
import dev.grabline.app.ui.entry.IncomingRequest
import dev.grabline.app.ui.pc.PcRoute
import dev.grabline.app.ui.settings.SettingsRoute
import dev.grabline.app.ui.welcome.WelcomeScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object Routes {
    const val DOWNLOADS = "downloads"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val DETAILS = "details/{pcId}/{jobId}"
    const val PC = "pc/{pcId}"

    fun details(pcId: String, jobId: Long) = "details/$pcId/$jobId"
    fun pc(pcId: String) = "pc/$pcId"
}

private data class Tab(val route: String, val labelRes: Int, @DrawableRes val iconRes: Int)

private val tabs = listOf(
    Tab(Routes.DOWNLOADS, R.string.tab_downloads, R.drawable.ic_tab_downloads),
    Tab(Routes.DEVICES, R.string.tab_devices, R.drawable.ic_tab_devices),
    Tab(Routes.SETTINGS, R.string.tab_settings, R.drawable.ic_tab_settings),
)

private val WideBreakpoint = 600.dp

/** A link (or links) waiting to be shown in the Add sheet. */
data class PendingAdd(val links: List<String>, val sourceTitle: String?)

/**
 * The app frame: three tabs (rail above 600 dp), the Add sheet over
 * everything, and one snackbar host. [incoming] carries shares, magnet links
 * and the copied-link offer from the activity; [onIncomingHandled] clears it.
 */
@Composable
fun GrabLineAppShell(
    remote: PcRemote,
    pairing: PairingController,
    settings: AppSettings,
    incoming: StateFlow<IncomingRequest?>,
    onIncomingHandled: () -> Unit,
    clipboardOffer: StateFlow<String?>,
    onClipboardHandled: (accepted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val welcomeDone by remember(settings) { settings.observe(AppSettings.WELCOME_DONE) }
        .collectAsState(initial = null)
    var startRoute by rememberSaveable { mutableStateOf(Routes.DOWNLOADS) }
    when (welcomeDone) {
        null -> {
            Box(modifier.fillMaxSize())
            return
        }
        false -> {
            WelcomeScreen(settings, onPairPc = { startRoute = Routes.DEVICES }, modifier = modifier)
            return
        }
        else -> Unit
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onDownloads = destination?.hierarchy?.any { it.route == Routes.DOWNLOADS || it.route == Routes.DETAILS } == true
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingAdd by remember { mutableStateOf<PendingAdd?>(null) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var addLinks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var addTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedJob by rememberSaveable { mutableStateOf<String?>(null) }

    fun openAdd(links: List<String>, title: String?) {
        addLinks = links
        addTitle = title
        addOpen = true
    }

    fun select(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Shares, magnet links and "Open with GrabLine".
    val request by incoming.collectAsState()
    val torrentLater = stringResource(R.string.shell_torrent_file_later)
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        onIncomingHandled()
        if (r.links.isNotEmpty()) openAdd(r.links, r.sourceTitle)
        else if (r.torrentFile != null) snackbar.showSnackbar(torrentLater)
    }

    // "Send the link you copied?" once per distinct copy.
    val offered by clipboardOffer.collectAsState()
    val offerText = stringResource(R.string.shell_offer_copied)
    val offerAction = stringResource(R.string.shell_offer_send)
    LaunchedEffect(offered) {
        val link = offered ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(offerText, actionLabel = offerAction, withDismissAction = true)
        val accepted = result == SnackbarResult.ActionPerformed
        onClipboardHandled(accepted)
        if (accepted) openAdd(listOf(link), null)
    }

    val addDescription = stringResource(R.string.add_a_link)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WideBreakpoint
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
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
                        onClick = { openAdd(emptyList(), null) },
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
                    composable(Routes.DOWNLOADS) {
                        if (wide) {
                            // Tablets and foldables: list left, details right (selection kept).
                            Row(Modifier.fillMaxSize()) {
                                DownloadsRoute(
                                    remote = remote,
                                    onOpenDetails = { pcId, jobId -> selectedJob = "$pcId:$jobId" },
                                    onAddClick = { openAdd(emptyList(), null) },
                                    onLinksPasted = { openAdd(it, null) },
                                    selectedRowKey = selectedJob,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    val key = selectedJob?.split(':', limit = 2)
                                    val jobId = key?.getOrNull(1)?.toLongOrNull()
                                    if (key != null && jobId != null) {
                                        DownloadDetailsRoute(remote, key[0], jobId, onBack = { selectedJob = null })
                                    } else {
                                        Text(stringResource(R.string.shell_pick_a_download))
                                    }
                                }
                            }
                        } else {
                            DownloadsRoute(
                                remote = remote,
                                onOpenDetails = { pcId, jobId -> navController.navigate(Routes.details(pcId, jobId)) },
                                onAddClick = { openAdd(emptyList(), null) },
                                onLinksPasted = { openAdd(it, null) },
                            )
                        }
                    }
                    composable(
                        Routes.DETAILS,
                        arguments = listOf(
                            navArgument("pcId") { type = NavType.StringType },
                            navArgument("jobId") { type = NavType.LongType },
                        ),
                    ) { entry ->
                        val pcId = entry.arguments?.getString("pcId").orEmpty()
                        val jobId = entry.arguments?.getLong("jobId") ?: 0L
                        DownloadDetailsRoute(remote, pcId, jobId, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.DEVICES) {
                        DevicesRoute(remote, pairing, onOpenPc = { navController.navigate(Routes.pc(it)) })
                    }
                    composable(Routes.PC, arguments = listOf(navArgument("pcId") { type = NavType.StringType })) { entry ->
                        PcRoute(remote, entry.arguments?.getString("pcId").orEmpty(), onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS) { SettingsRoute(settings, onPairPc = { select(Routes.DEVICES) }) }
                }
            }
        }
        if (addOpen) {
            AddSheetRoute(
                remote = remote,
                settings = settings,
                initialLinks = addLinks,
                sourceTitle = addTitle,
                onDismiss = { addOpen = false },
                onSent = { message -> scope.launch { snackbar.showSnackbar(message) } },
                onPairClick = {
                    addOpen = false
                    select(Routes.DEVICES)
                },
            )
        }
    }
}
