package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.SettingsSection
import com.kingzcheung.xime.ui.SettingsToggleItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutDisplaySettingsContent(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    "布局与显示",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            ),
            windowInsets = WindowInsets(0.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "候选词", content = {
                    var showComments by remember {
                        mutableStateOf(SettingsPreferences.showCandidateComments(context))
                    }
                    SettingsToggleItem(
                        icon = Icons.TwoTone.ViewAgenda,
                        title = "显示编码注释",
                        subtitle = "在候选词旁显示对应的编码（如五笔字根）",
                        checked = showComments,
                        onCheckedChange = { newValue ->
                            showComments = newValue
                            SettingsPreferences.setShowCandidateComments(context, newValue)
                        }
                    )
                })
            }
        }
    }
}
