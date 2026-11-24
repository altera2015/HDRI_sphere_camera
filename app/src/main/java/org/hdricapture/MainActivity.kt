// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.hdricapture.ui.capture.CaptureScreen
import org.hdricapture.ui.review.SessionsScreen
import org.hdricapture.ui.viewer.ViewerScreen
import androidx.compose.runtime.mutableStateOf
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var screen by rememberSaveable { mutableStateOfScreen() }
                var viewing by rememberSaveable { mutableStateOf<String?>(null) }
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    when (screen) {
                        Screen.CAPTURE -> CaptureScreen(onOpenSessions = { screen = Screen.SESSIONS })
                        Screen.SESSIONS -> {
                            BackHandler { screen = Screen.CAPTURE }
                            SessionsScreen(
                                onBack = { screen = Screen.CAPTURE },
                                onView = { path -> viewing = path; screen = Screen.VIEWER },
                            )
                        }
                        Screen.VIEWER -> {
                            BackHandler { screen = Screen.SESSIONS }
                            viewing?.let { ViewerScreen(File(it), onBack = { screen = Screen.SESSIONS }) }
                        }
                    }
                }
            }
        }
    }
}

private enum class Screen { CAPTURE, SESSIONS, VIEWER }

private fun mutableStateOfScreen() = androidx.compose.runtime.mutableStateOf(Screen.CAPTURE)
