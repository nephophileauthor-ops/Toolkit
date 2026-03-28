package com.apidebug.inspector

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apidebug.inspector.ui.InspectorApp
import com.apidebug.inspector.ui.InspectorUiAction
import com.apidebug.inspector.ui.InspectorViewModel
import com.apidebug.inspector.ui.theme.ApiDebugInspectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as InspectorApplication).container
        lateinit var inspectorViewModel: InspectorViewModel
        val vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            inspectorViewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
        }

        setContent {
            inspectorViewModel = viewModel(
                factory = InspectorViewModel.factory(
                    application = this@MainActivity.application,
                    container = container
                )
            )
            val settings = inspectorViewModel.settings.collectAsStateWithLifecycle().value

            LaunchedEffect(Unit) {
                inspectorViewModel.uiActions.collect { action ->
                    when (action) {
                        InspectorUiAction.RequestVpnPermission -> {
                            val intent = VpnService.prepare(this@MainActivity)
                            if (intent == null) {
                                inspectorViewModel.onVpnPermissionResult(true)
                            } else {
                                vpnPermissionLauncher.launch(intent)
                            }
                        }
                    }
                }
            }

            ApiDebugInspectorTheme(darkTheme = settings.darkTheme) {
                InspectorApp(viewModel = inspectorViewModel)
            }
        }
    }
}
