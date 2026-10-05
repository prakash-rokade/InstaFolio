package com.prakash.instafolio.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.prakash.instafolio.core.model.AppTheme

fun main() = application {
    val settings = remember { DesktopSettings() }
    var selectedTheme by remember { mutableStateOf(settings.theme) }
    val useDarkTheme = when (selectedTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }

    val windowState = rememberWindowState(
        width = 540.dp,
        height = 780.dp
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "InstaFolio",
        icon = painterResource("icon.png"),
        state = windowState,
        resizable = true
    ) {
        window.minimumSize = java.awt.Dimension(440, 600)
        DesktopInstaFolioTheme(darkTheme = useDarkTheme) {
            DesktopDownloaderScreen(
                settings = settings,
                useDarkTheme = useDarkTheme,
                onThemeChanged = { selectedTheme = it }
            )
        }
    }
}
