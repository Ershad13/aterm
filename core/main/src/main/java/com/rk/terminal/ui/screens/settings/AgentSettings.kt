package com.rk.terminal.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettings() {
    var enableStreaming by remember { mutableStateOf(Settings.enable_streaming) }
    var useApiSearch by remember { mutableStateOf(Settings.use_api_search) }
    var recursiveCurls by remember { mutableStateOf(Settings.custom_search_recursive_curls) }
    
    PreferenceGroup(heading = "Agent Settings") {
        SettingsCard(
            title = { Text("Enable Streaming Mode") },
            description = { 
                Text(
                    if (enableStreaming) {
                        "Agent responses stream in real-time. Disable for metadata-first approach."
                    } else {
                        "Metadata-first mode: generates file structure first, then code. Better for complex projects."
                    }
                )
            },
            startWidget = {
                Switch(
                    checked = enableStreaming,
                    onCheckedChange = {
                        enableStreaming = it
                        Settings.enable_streaming = it
                    }
                )
            },
            onClick = {
                enableStreaming = !enableStreaming
                Settings.enable_streaming = enableStreaming
            }
        )
        
        SettingsCard(
            title = { Text("Use API Search") },
            description = { 
                Text(
                    if (useApiSearch) {
                        "Uses Gemini API's built-in Google Search. Faster but requires API support."
                    } else {
                        "Uses custom search engine with direct API calls and AI analysis. More control and recursive searches."
                    }
                )
            },
            startWidget = {
                Switch(
                    checked = useApiSearch,
                    onCheckedChange = {
                        useApiSearch = it
                        Settings.use_api_search = it
                    }
                )
            },
            onClick = {
                useApiSearch = !useApiSearch
                Settings.use_api_search = useApiSearch
            }
        )
        
        if (!useApiSearch) {
            SettingsCard(
                title = { Text("Custom Search Recursive Levels") },
                description = { 
                    Column {
                        Text(
                            "Number of recursive search levels (1-8). Higher values gather more comprehensive information but take longer. Current: $recursiveCurls"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = recursiveCurls.toFloat(),
                            onValueChange = { newValue ->
                                recursiveCurls = newValue.toInt().coerceIn(1, 8)
                                Settings.custom_search_recursive_curls = recursiveCurls
                            },
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Levels: $recursiveCurls",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                onClick = { /* No action needed, slider handles interaction */ }
            )
        }
    }
}
