package com.ucfvpn.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.ucfvpn.app.ui.navigation.AppNavHost
import com.ucfvpn.app.ui.theme.UcfVpnTheme
import com.ucfvpn.app.ui.viewmodel.VpnViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UcfVpnTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(viewModel = viewModel)
                }
            }
        }
    }
}
