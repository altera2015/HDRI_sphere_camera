// SPDX-License-Identifier: GPL-3.0-or-later
package org.hdricapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

private const val HOME_URL = "https://github.com/altera2015/HDRI_sphere_camera"

/** Small "about" box: what the app is, where it lives, and its license. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HDRI Sphere $version") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
                Text(
                    buildAnnotatedString {
                        append("Captures HDR environment maps for lighting and skies in ")
                        withLink(LinkAnnotation.Url("https://godotengine.org", linkStyle)) { append("Godot") }
                        append(", ")
                        withLink(LinkAnnotation.Url("https://www.blender.org", linkStyle)) { append("Blender") }
                        append(" and other render engines.")
                    },
                )
                Text(
                    buildAnnotatedString {
                        append("Home: ")
                        withLink(LinkAnnotation.Url(HOME_URL, linkStyle)) { append(HOME_URL.removePrefix("https://")) }
                    },
                )
                Text(
                    "License: GNU General Public License v3.0 or later. This is free software: you may " +
                        "redistribute and modify it under the terms of the GPL. It comes with no warranty.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
