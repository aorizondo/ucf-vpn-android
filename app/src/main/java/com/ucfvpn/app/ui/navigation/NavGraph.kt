package com.ucfvpn.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.ucfvpn.app.ui.screens.ConfigScreen
import com.ucfvpn.app.ui.screens.LogScreen
import com.ucfvpn.app.ui.screens.StatusScreen
import com.ucfvpn.app.ui.viewmodel.VpnViewModel

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val content: @Composable (VpnViewModel) -> Unit
)

val bottomNavItems = listOf(
    BottomNavItem(
        label = "Status",
        icon = Icons.Default.Circle,
        content = { viewModel -> StatusScreen(viewModel = viewModel) }
    ),
    BottomNavItem(
        label = "Config",
        icon = Icons.Default.Settings,
        content = { viewModel -> ConfigScreen(viewModel = viewModel) }
    ),
    BottomNavItem(
        label = "Logs",
        icon = Icons.Default.Terminal,
        content = { viewModel -> LogScreen(viewModel = viewModel) }
    )
)

@Composable
fun AppNavHost(viewModel: VpnViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            bottomNavItems[selectedTab].content(viewModel)
        }
    }
}
