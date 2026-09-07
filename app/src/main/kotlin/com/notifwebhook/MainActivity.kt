package com.notifwebhook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.notifwebhook.ui.MainScreen
import com.notifwebhook.ui.NotifWebhookTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPrefs.get(this)
        setContent {
            NotifWebhookTheme {
                MainScreen(prefs)
            }
        }
    }
}
