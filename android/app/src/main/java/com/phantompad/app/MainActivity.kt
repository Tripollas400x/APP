package com.phantompad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.phantompad.app.ui.screens.ConnectScreen
import com.phantompad.app.ui.screens.DiagnosticsScreen
import com.phantompad.app.ui.screens.EditorScreen
import com.phantompad.app.ui.screens.GamepadScreen
import com.phantompad.app.ui.screens.SettingsScreen
import com.phantompad.app.ui.screens.WelcomeScreen
import com.phantompad.app.ui.theme.PhantomPadTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhantomPadTheme {
                AppRoot(viewModel)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: PadViewModel) {
    val nav = rememberNavController()
    var granted by remember { mutableStateOf(vm.bt.hasPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(vm.bt.requiredPermissions())
    }

    LaunchedEffect(granted) {
        // Con permisos concedidos, activar la supervisión HID automática.
        if (granted) vm.notifyPermissionsGranted()
    }

    NavHost(navController = nav, startDestination = "welcome") {
        composable("welcome") {
            WelcomeScreen(
                onStart = {
                    if (!granted) permissionLauncher.launch(vm.bt.requiredPermissions())
                    nav.navigate("connect")
                }
            )
        }
        composable("connect") {
            ConnectScreen(
                vm = vm,
                onEnableBluetooth = { runCatching { enableBtLauncher.launch(vm.bt.enableIntent()) } },
                onConnected = {
                    nav.navigate("gamepad") { popUpTo("welcome") }
                },
                onOpenDiagnostics = { nav.navigate("diagnostics") },
                onOpenSettings = { nav.navigate("settings") }
            )
        }
        composable("gamepad") {
            GamepadScreen(
                vm = vm,
                onExit = { nav.popBackStack("connect", inclusive = false) },
                onOpenDiagnostics = { nav.navigate("diagnostics") },
                onOpenEditor = { nav.navigate("editor") }
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("editor") {
            EditorScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("diagnostics") {
            DiagnosticsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
