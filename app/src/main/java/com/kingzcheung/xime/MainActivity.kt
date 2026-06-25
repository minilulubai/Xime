package com.kingzcheung.xime

import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.SettingsScreen
import com.kingzcheung.xime.ui.settings.SetupWizardScreen
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onDestroy() {
        prewarmScope.cancel()
        super.onDestroy()
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "麦克风权限被拒绝，无法使用语音输入", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
    
    private val prewarmScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private fun prewarmRimeEngine() {
        if (RimeEngine.isInitialized()) return
        prewarmScope.launch {
            try {
                Log.d(TAG, "Pre-warming Rime engine...")
                KeysConfigHelper.loadConfig(this@MainActivity)
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@MainActivity)
                RimeEngine.getInstance().initialize(userDataDir, sharedDataDir)
                Log.d(TAG, "Rime engine pre-warmed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Rime engine pre-warm failed, will init on demand", e)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        prewarmRimeEngine()

        handleSharedIntent(intent)
        
        val requestPermission = intent?.getStringExtra("request_permission")
        if (requestPermission == PermissionHelper.PERMISSION_RECORD_AUDIO) {
            if (!PermissionHelper.hasRecordAudioPermission(this)) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        
        val openFragment = intent?.getStringExtra("open_fragment")

        setContent {
            val context = this
            val setupCompleted = SettingsPreferences.isSetupCompleted(context)
            var showWizard by remember { mutableStateOf(!setupCompleted) }
            var wizardToSettings by remember { mutableStateOf(false) }
            var darkMode by remember { mutableIntStateOf(SettingsPreferences.getDarkMode(context)) }
            var keyboardTheme by remember { mutableStateOf(SettingsPreferences.getKeyboardTheme(context)) }
            
            val isDarkTheme = when (darkMode) {
                2 -> isSystemInDarkTheme()
                1 -> true
                else -> false
            }

            XimeTheme(darkTheme = isDarkTheme, themeId = keyboardTheme) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    DisposableEffect(darkMode) {
                        val window = (view.context as? ComponentActivity)?.window
                        if (window != null) {
                            val controller = WindowInsetsControllerCompat(window, view)
                            controller.isAppearanceLightStatusBars = !isDarkTheme
                            controller.isAppearanceLightNavigationBars = !isDarkTheme
                        }
                        onDispose { }
                    }
                }

                val density = LocalDensity.current
                val navBarHeight = with(density) {
                    WindowInsets.navigationBars.getBottom(density).toDp()
                }
                val scrimHeight = if (navBarHeight > 0.dp) navBarHeight else 32.dp

                Box(modifier = Modifier.fillMaxSize()) {
                    if (showWizard) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SetupWizardScreen(
                                visible = !wizardToSettings,
                                onNavigateToSchemaSettings = { wizardToSettings = true },
                                onCompleted = { showWizard = false }
                            )
                            if (wizardToSettings) {
                                SettingsScreen(
                                    initialRoute = "schema",
                                    onThemeChanged = {
                                        darkMode = SettingsPreferences.getDarkMode(context)
                                        keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                                    },
                                    onWizardBack = { wizardToSettings = false }
                                )
                            }
                        }
                    } else {
                        SettingsScreen(
                            initialRoute = openFragment,
                            onThemeChanged = {
                                darkMode = SettingsPreferences.getDarkMode(context)
                                keyboardTheme = SettingsPreferences.getKeyboardTheme(context)
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scrimHeight)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (isDarkTheme) {
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f))
                                    } else {
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f))
                                    }
                                )
                            )
                    )
                }
            }
        }
    }
    
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    importSchema(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uris != null) {
                    for (uri in uris) {
                        importSchema(uri)
                    }
                }
            }
        }
    }
    
    private fun importSchema(uri: android.net.Uri) {
        prewarmScope.launch {
            val success = SchemaManager.importSchemaFile(this@MainActivity, uri)
            launch(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (success) "方案导入成功，请到「输入方案」页面部署" else "方案导入失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
