package com.rk.terminal.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllamaSettings() {
    var useOllama by remember { mutableStateOf(Settings.use_ollama) }
    var ollamaHost by remember { mutableStateOf(Settings.ollama_host) }
    var ollamaPort by remember { mutableStateOf(Settings.ollama_port.toString()) }
    var ollamaModel by remember { mutableStateOf(Settings.ollama_model) }
    
    PreferenceGroup(heading = "Ollama Settings") {
        SettingsCard(
            title = { Text("Use Ollama (Local AI)") },
            description = { Text("Enable local Ollama AI instead of Gemini") },
            startWidget = {
                Switch(
                    checked = useOllama,
                    onCheckedChange = {
                        useOllama = it
                        Settings.use_ollama = it
                    }
                )
            },
            onClick = {
                useOllama = !useOllama
                Settings.use_ollama = useOllama
            }
        )
        
        if (useOllama) {
            var showSettingsDialog by remember { mutableStateOf(false) }
            
            SettingsCard(
                title = { Text("Ollama Configuration") },
                description = { 
                    Text("Host: ${ollamaHost}, Port: ${ollamaPort}, Model: ${ollamaModel}")
                },
                onClick = { showSettingsDialog = true }
            )
            
            if (showSettingsDialog) {
                var tempHost by remember { mutableStateOf(ollamaHost) }
                var tempPort by remember { mutableStateOf(ollamaPort) }
                var tempModel by remember { mutableStateOf(ollamaModel) }
                
                AlertDialog(
                    onDismissRequest = { showSettingsDialog = false },
                    title = { Text("Ollama Configuration") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = tempHost,
                                onValueChange = { tempHost = it },
                                label = { Text("Host/IP Address") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("localhost") }
                            )
                            OutlinedTextField(
                                value = tempPort,
                                onValueChange = { 
                                    if (it.all { char -> char.isDigit() }) {
                                        tempPort = it
                                    }
                                },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("11434") }
                            )
                            OutlinedTextField(
                                value = tempModel,
                                onValueChange = { tempModel = it },
                                label = { Text("Model Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("llama3.2") }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                ollamaHost = tempHost
                                ollamaPort = tempPort
                                ollamaModel = tempModel
                                Settings.ollama_host = ollamaHost
                                Settings.ollama_port = ollamaPort.toIntOrNull() ?: 11434
                                Settings.ollama_model = ollamaModel
                                showSettingsDialog = false
                            }
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSettingsDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
