package app.goga.assistant.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.goga.assistant.AssistantActivity
import app.goga.assistant.R
import app.goga.calendar.CalendarSection
import app.goga.notes.NotesScreen
import app.goga.tasks.TasksScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GogaRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon(), contentDescription = tab.label()) },
                        label = { Text(tab.label()) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppTab.TODAY.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(AppTab.TODAY.route) { TodayScreen() }
            composable(AppTab.TASKS.route) { TasksScreen() }
            composable(AppTab.NOTES.route) { NotesScreen() }
            composable(AppTab.SETTINGS.route) { SettingsScreen() }
        }
    }
}

@Composable
private fun AppTab.label(): String = when (this) {
    AppTab.TODAY -> stringResource(R.string.tab_today)
    AppTab.TASKS -> stringResource(R.string.tab_tasks)
    AppTab.NOTES -> stringResource(R.string.tab_notes)
    AppTab.SETTINGS -> stringResource(R.string.tab_settings)
}

private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.TODAY -> Icons.Filled.Home
    AppTab.TASKS -> Icons.Filled.CheckCircle
    AppTab.NOTES -> Icons.Filled.Edit
    AppTab.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun TodayScreen() {
    val context = LocalContext.current
    PlaceholderPage(
        title = stringResource(R.string.today_title),
        body = stringResource(R.string.today_body),
        extra = stringResource(R.string.today_assistant),
        footer = {
            Button(
                onClick = {
                    context.startActivity(Intent(context, AssistantActivity::class.java))
                },
            ) {
                Text(stringResource(R.string.talk_button))
            }
            CalendarSection()
        },
    )
}
