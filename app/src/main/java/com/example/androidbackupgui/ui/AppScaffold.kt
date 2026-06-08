package com.example.androidbackupgui.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private val navItems = listOf(
    NavItem(Screen.BACKUP, Icons.Filled.Cloud, "备份"),
    NavItem(Screen.RESTORE, Icons.Filled.Restore, "恢复"),
    NavItem(Screen.LOG, Icons.Filled.Description, "日志"),
    NavItem(Screen.CONFIG, Icons.Filled.Settings, "配置"),
)

private data class NavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold() {
    var currentScreen by remember { mutableStateOf(Screen.CONFIG) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(currentScreen.label) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        bottomBar = {
            NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentScreen == item.screen,
                        onClick = { currentScreen = item.screen },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentScreen) {
                Screen.BACKUP -> BackupScreen()
                Screen.RESTORE -> RestoreScreen()
                Screen.LOG -> LogScreen()
                Screen.CONFIG -> ConfigScreen(snackbarHostState = snackbarHostState)
            }
        }
    }
}
