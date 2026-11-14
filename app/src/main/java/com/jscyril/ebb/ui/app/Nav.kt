package com.jscyril.ebb.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jscyril.ebb.R
import com.jscyril.ebb.ui.theme.EbbMotion
import com.jscyril.ebb.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: Int, val icon: Int) {
    HOME("home", R.string.tab_home, R.drawable.ic_home),
    INSIGHTS("insights", R.string.tab_insights, R.drawable.ic_insights),
    RULES("rules", R.string.tab_rules, R.drawable.ic_tune),
    SETTINGS("settings", R.string.tab_settings, R.drawable.ic_settings),
}

/** Onboarding until it is done, then the four-tab app. */
@Composable
fun EbbRoot(onboardingDone: Boolean) {
    val reduced = rememberReducedMotion()
    AnimatedContent(onboardingDone, transitionSpec = { EbbMotion.enter(reduced) togetherWith EbbMotion.exit(reduced) }, label = "root") { done ->
        if (done) MainShell() else Onboarding()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainShell() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val onMessage: (String) -> Unit = { m -> scope.launch { snackbar.showSnackbar(m) } }

    Scaffold(
        Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { t ->
                    ShortNavigationBarItem(
                        selected = current == t.route,
                        onClick = { nav.go(t.route) },
                        icon = { Icon(painterResource(t.icon), null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav, Tab.HOME.route,
            enterTransition = { EbbMotion.enter(reduced) }, exitTransition = { EbbMotion.exit(reduced) },
            popEnterTransition = { EbbMotion.enter(reduced) }, popExitTransition = { EbbMotion.exit(reduced) },
        ) {
            composable(Tab.HOME.route) { Home({ nav.go(Tab.RULES.route) }, { nav.go(Tab.SETTINGS.route) }, padding) }
            composable(Tab.INSIGHTS.route) { DashboardPlaceholder(padding) }
            composable(Tab.RULES.route) { RulesTab(padding, onMessage) }
            composable(Tab.SETTINGS.route) { SettingsTab(padding, onMessage) }
        }
    }
}

/** Tab switch: one copy of each tab, state kept, Back from any tab returns Home. */
private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
