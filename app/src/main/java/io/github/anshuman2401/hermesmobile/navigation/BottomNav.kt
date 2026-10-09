package io.github.anshuman2401.hermesmobile.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.github.anshuman2401.hermesmobile.design.AppThemePreference
import io.github.anshuman2401.hermesmobile.design.AtelierTheme
import io.github.anshuman2401.hermesmobile.design.atelierSelectionColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.R

/**
 * Exactly three bottom navigation tabs in specified order: Chat, Work, Bot.
 * Chats (Sessions) is selected by default.
 */
enum class BottomNavTab(
    val route: String,
    @StringRes val labelRes: Int
) {
    CHATS("chats", R.string.nav_chats),
    WORK("work", R.string.nav_work),
    BOTS("bots", R.string.nav_bot);

    companion object {
        val DEFAULT: BottomNavTab = CHATS
    }
}

/**
 * Maps each [BottomNavTab] to its corresponding top-level [Destination].
 */
fun BottomNavTab.toDestination(): Destination = when (this) {
    BottomNavTab.CHATS -> Destination.Sessions
    BottomNavTab.WORK -> Destination.Work
    BottomNavTab.BOTS -> Destination.Bots
}

/**
 * State holder for bottom navigation tab selection.
 */
class BottomNavState(initialTab: BottomNavTab = BottomNavTab.DEFAULT) {
    var currentTab: BottomNavTab = initialTab
        private set

    val currentDestination: Destination
        get() = currentTab.toDestination()

    fun selectTab(tab: BottomNavTab) {
        currentTab = tab
    }
}

/**
 * Minimal icons for bottom navigation bar items avoiding external dependencies.
 */
object BottomNavIcons {
    val Chats: ImageVector by lazy {
        ImageVector.Builder("Chats", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z"
                ).toNodes(),
                fill = SolidColor(Color.Black)
            )
        }.build()
    }

    val Work: ImageVector by lazy {
        ImageVector.Builder("Work", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M20 6h-4V4c0-1.11-.89-2-2-2h-4c-1.11 0-2 .89-2 2v2H4c-1.11 0-1.99.89-1.99 2L2 19c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2zm-6 0h-4V4h4v2z"
                ).toNodes(),
                fill = SolidColor(Color.Black)
            )
        }.build()
    }

    val Bots: ImageVector by lazy {
        ImageVector.Builder("Bots", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    "M12 2c.55 0 1 .45 1 1v2h4c1.1 0 2 .9 2 2v10c0 1.1-.9 2-2 2H7c-1.1 0-2-.9-2-2V7c0-1.1.9-2 2-2h4V3c0-.55.45-1 1-1zM8.5 9.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm7 0c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zM8 15h8v1.5H8z"
                ).toNodes(),
                fill = SolidColor(Color.Black)
            )
        }.build()
    }

    fun iconFor(tab: BottomNavTab): ImageVector = when (tab) {
        BottomNavTab.CHATS -> Chats
        BottomNavTab.WORK -> Work
        BottomNavTab.BOTS -> Bots
    }
}

/**
 * Minimal bottom navigation bar with icons only (no labels).
 *
 * Owner-requested compact sizing (2026-09-27): 20% below the Material3
 * defaults — NavigationBar 80dp → 64dp, tab icon 24dp → 19dp. The explicit
 * [Modifier.height] is required because the library applies its 80dp default
 * via defaultMinSize only when the caller has not already constrained height
 * (verified against material3 bytecode); the icon override scales the icon
 * render inside the unchanged NavigationBarItem slot/indicator geometry.
 */
@Composable
fun HermesBottomNavBar(
    selectedTab: BottomNavTab,
    onTabSelected: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier,
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM
) {
    AtelierTheme(currentTheme, updateSystemBars = false) {
        NavigationBar(
            modifier = modifier.height(64.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            BottomNavTab.entries.forEach { tab ->
                val label = stringResource(tab.labelRes)
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            imageVector = BottomNavIcons.iconFor(tab),
                            contentDescription = label,
                            modifier = Modifier.size(19.dp)
                        )
                    },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = atelierSelectionColor(),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    alwaysShowLabel = false
                )
            }
        }
    }
}

/**
 * Simple placeholder Compose screen showing only its title text.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) =
    PlaceholderScreen(title = stringResource(R.string.nav_home), modifier = modifier)
