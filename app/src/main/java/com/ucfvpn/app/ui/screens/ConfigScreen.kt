package com.ucfvpn.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ucfvpn.app.ui.viewmodel.UiConfig
import com.ucfvpn.app.ui.viewmodel.VpnViewModel
import com.ucfvpn.app.ui.viewmodel.WstunnelMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: VpnViewModel) {
    val config by viewModel.uiConfig
    var currentConfig by remember { mutableStateOf(config) }
    var sstpExpanded by remember { mutableStateOf(true) }
    var proxyExpanded by remember { mutableStateOf(false) }
    var wstunnelExpanded by remember { mutableStateOf(false) }
    var wireguardExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Configuration") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // SSTP Section
            CollapsibleSection(
                title = "SSTP Configuration",
                expanded = sstpExpanded,
                onToggle = { sstpExpanded = !sstpExpanded }
            ) {
                OutlinedTextField(
                    value = currentConfig.sstpHost,
                    onValueChange = { currentConfig = currentConfig.copy(sstpHost = it) },
                    label = { Text("Server Host") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.sstpPort.toString(),
                    onValueChange = { value ->
                        val port = value.filter { it.isDigit() }.take(5).toIntOrNull() ?: currentConfig.sstpPort
                        if (port in 1..65535) currentConfig = currentConfig.copy(sstpPort = port)
                    },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.sstpUsername,
                    onValueChange = { currentConfig = currentConfig.copy(sstpUsername = it) },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.sstpPassword,
                    onValueChange = { currentConfig = currentConfig.copy(sstpPassword = it) },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }

            // Proxy Section
            CollapsibleSection(
                title = "Proxy Configuration",
                expanded = proxyExpanded,
                onToggle = { proxyExpanded = !proxyExpanded }
            ) {
                OutlinedTextField(
                    value = currentConfig.proxyHost,
                    onValueChange = { currentConfig = currentConfig.copy(proxyHost = it) },
                    label = { Text("Host") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.proxyPort.toString(),
                    onValueChange = { value ->
                        val port = value.filter { it.isDigit() }.take(5).toIntOrNull() ?: currentConfig.proxyPort
                        if (port in 1..65535) currentConfig = currentConfig.copy(proxyPort = port)
                    },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.proxyUsername,
                    onValueChange = { currentConfig = currentConfig.copy(proxyUsername = it) },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.proxyPassword,
                    onValueChange = { currentConfig = currentConfig.copy(proxyPassword = it) },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            }

            // wstunnel Section
            CollapsibleSection(
                title = "wstunnel Configuration",
                expanded = wstunnelExpanded,
                onToggle = { wstunnelExpanded = !wstunnelExpanded }
            ) {
                OutlinedTextField(
                    value = currentConfig.wstunnelUrl,
                    onValueChange = { currentConfig = currentConfig.copy(wstunnelUrl = it) },
                    label = { Text("Server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mode: ${currentConfig.wstunnelMode.displayName}")
                    Row {
                        OutlinedButton(onClick = {
                            currentConfig = currentConfig.copy(wstunnelMode = WstunnelMode.FIXED)
                        }) {
                            Text("Fixed")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            currentConfig = currentConfig.copy(wstunnelMode = WstunnelMode.DYNAMIC)
                        }) {
                            Text("Dynamic")
                        }
                    }
                }
            }

            // WireGuard Section
            CollapsibleSection(
                title = "WireGuard Configuration",
                expanded = wireguardExpanded,
                onToggle = { wireguardExpanded = !wireguardExpanded }
            ) {
                OutlinedTextField(
                    value = currentConfig.wireGuardEndpoint,
                    onValueChange = { currentConfig = currentConfig.copy(wireGuardEndpoint = it) },
                    label = { Text("Endpoint") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.wireGuardLocalIp,
                    onValueChange = { currentConfig = currentConfig.copy(wireGuardLocalIp = it) },
                    label = { Text("Local IP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = currentConfig.wireGuardDns,
                    onValueChange = { currentConfig = currentConfig.copy(wireGuardDns = it) },
                    label = { Text("DNS") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Toggles
            SwitchRow(
                label = "Ignore SSL Certificate Warnings",
                checked = currentConfig.ignoreSslErrors,
                onCheckedChange = { currentConfig = currentConfig.copy(ignoreSslErrors = it) }
            )
            SwitchRow(
                label = "Auto-reconnect",
                checked = currentConfig.autoReconnect,
                onCheckedChange = { currentConfig = currentConfig.copy(autoReconnect = it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            FilledTonalButton(
                onClick = {
                    if (validateConfig(currentConfig)) {
                        viewModel.updateConfig(currentConfig)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Configuration")
            }
        }
    }
}

@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun validateConfig(config: UiConfig): Boolean {
    if (config.sstpHost.isBlank()) return false
    if (config.sstpPort !in 1..65535) return false
    if (config.proxyHost.isBlank()) return false
    if (config.proxyPort !in 1..65535) return false
    return true
}
