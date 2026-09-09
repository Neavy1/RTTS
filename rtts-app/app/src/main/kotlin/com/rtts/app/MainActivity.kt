package com.rtts.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rtts.app.feature.login.LoginScreen
import com.rtts.app.feature.transcript.LiveTranscriptScreen
import com.rtts.app.ui.theme.RttsTheme

class MainActivity : ComponentActivity() {

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled implicitly: capture will just fail to start if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)

        val container = (application as RttsApplication).container

        setContent {
            RttsTheme {
                val navController = rememberNavController()
                // TODO: login suspendido temporalmente para agilizar pruebas -- volver a
                // "login" cuando se retome el flujo de autenticación.
                NavHost(navController = navController, startDestination = "transcript") {
                    composable("login") {
                        LoginScreen(
                            authRepository = container.authRepository,
                            onLoginSuccess = {
                                navController.navigate("transcript") {
                                    popUpTo("login") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("transcript") {
                        LiveTranscriptScreen()
                    }
                }
            }
        }
    }
}
