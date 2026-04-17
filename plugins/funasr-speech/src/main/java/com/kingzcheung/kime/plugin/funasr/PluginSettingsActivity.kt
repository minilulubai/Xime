package com.kingzcheung.kime.plugin.funasr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kingzcheung.kime.plugin.funasr.ui.FunAsrSettingsScreen

class PluginSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface {
                    FunAsrSettingsScreen(
                        context = this,
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}