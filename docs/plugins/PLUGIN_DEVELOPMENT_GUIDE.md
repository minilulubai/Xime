# Xime 插件开发完整指南

## 插件系统架构

Xime 采用动态加载插件架构，表情插件只提供数据，由主应用负责展示。

```
┌─────────────────────────────────────────────┐
│          主应用 (Xime  APK)                   │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   PluginManager                      │   │
│  │   - PluginClassLoader 加载插件APK    │   │
│  │   - PluginLifecycleManager 管理生命周期 │   │
│  └─────────────────────────────────────┘   │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   ExtensionManager                   │   │
│  │   - 管理表情数据                     │   │
│  │   - 提供 emojiCategoriesFlow        │   │
│  └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
            │
            │ PluginClassLoader 加载
            ▼
┌─────────────────────────────────────────────┐
│       插件 APK (独立安装)                    │
│                                              │
│  ┌─────────────────────────────────────┐   │
│  │   EmojiPlugin 实现                   │   │
│  │   - onLoad(PluginContext)            │   │
│  │   - onUnload()                       │   │
│  │   - getEmojis() 提供表情数据         │   │
│  │   - getCategories() 提供分类         │   │
│  └─────────────────────────────────────┘   │
│                                              │
│  插件不需要 UI，不依赖 Compose              │
└─────────────────────────────────────────────┘
```

## 核心概念

### 插件类型

Xime 目前只支持表情插件类型：

| 类型 | 接口 | 用途 |
|------|------|------|
| EMOJI | EmojiPlugin | 表情输入（颜文字、贴纸等） |

**重要特性**：
- 插件只提供资源数据（EmojiItem）
- 主应用负责展示和交互
- 插件不需要 UI 代码，不依赖 Compose

## 开发插件步骤

### 1. 创建项目结构

```
my-kime-plugin/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/           # 表情资源文件（可选）
    └── java/com/example/plugin/
        ├── PluginDeclaration.kt      # 空的 Activity（用于插件发现）
        └── MyPlugin.kt               # 实现 EmojiPlugin
```

### 2. 配置 build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.kime.plugin"
    compileSdk = 36
    
    defaultConfig {
        applicationId = "com.example.kime.plugin.myplugin"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    
    buildTypes {
        release {
            // 推荐禁用混淆，避免 Kotlin stdlib 方法丢失
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // compileOnly 依赖，插件运行时由 PluginClassLoader 加载
    compileOnly(project(":plugin-core"))
    
    // 如果需要加载图片，添加 coil（implementation）
    implementation("io.coil-kt:coil-compose:2.5.0")
}
```

**关键点**：
- `compileOnly(project(":plugin-core"))` - 插件核心接口
- `isMinifyEnabled = false` - 避免 Kotlin stdlib 方法丢失
- 不需要 Compose 依赖（插件无 UI）

### 3. 配置 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true">
        
        <!-- 插件声明 Activity（必须） -->
        <activity
            android:name=".PluginDeclaration"
            android:exported="true">
            <intent-filter>
                <action android:name="com.kingzcheung.xime.plugin.EXTENSION" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
        
        <!-- 插件元数据 -->
        <meta-data
            android:name="plugin.entryClass"
            android:value="com.example.plugin.MyPlugin" />
        
        <meta-data
            android:name="plugin.description"
            android:value="提供精选表情" />
        
        <meta-data
            android:name="plugin.type"
            android:value="emoji" />
        
    </application>

</manifest>
```

**关键点**：
- `PluginDeclaration` Activity 用于插件发现
- `plugin.entryClass` 指定插件入口类
- `plugin.type` 必须是 `emoji`

### 4. 实现插件入口类

```kotlin
package com.example.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.EmojiItem
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginContext
import java.io.File
import java.util.zip.ZipFile

class MyPlugin : EmojiPlugin {
    
    private var pluginContext: PluginContext? = null
    private var emojiList: List<EmojiItem> = emptyList()
    
    companion object {
        private const val TAG = "MyPlugin"
    }
    
    override fun onLoad(context: PluginContext) {
        this.pluginContext = context
        Log.d(TAG, "Plugin loaded: ${context.pluginInfo.id}")
        
        val filesDir = context.application.filesDir
        
        // 加载表情数据
        loadEmojis(filesDir, context.pluginInfo.path)
        
        Log.d(TAG, "Loaded ${emojiList.size} emojis")
    }
    
    override fun onUnload() {
        emojiList = emptyList()
        pluginContext = null
        Log.d(TAG, "Plugin unloaded")
    }
    
    private fun loadEmojis(filesDir: File, apkPath: String?) {
        val emojis = mutableListOf<EmojiItem>()
        
        // 从 APK assets 加载表情
        val actualApkPath = apkPath ?: pluginContext?.application?.applicationInfo?.sourceDir
        if (actualApkPath != null) {
            try {
                ZipFile(File(actualApkPath)).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("assets/emojis/") && !it.isDirectory }
                        .forEach { entry ->
                            val fileName = entry.name.substringAfter("assets/emojis/")
                            emojis.add(
                                EmojiItem(
                                    id = "emoji_$fileName",
                                    displayText = fileName,
                                    insertText = fileName,
                                    imageUrl = null, // 或本地文件路径
                                    category = "默认"
                                )
                            )
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load emojis", e)
            }
        }
        
        emojiList = emojis
    }
    
    override suspend fun getEmojis(
        category: String?, 
        searchText: String?, 
        topK: Int
    ): List<EmojiItem> {
        val filtered = if (searchText.isNullOrEmpty()) emojiList
        else emojiList.filter { 
            it.displayText.contains(searchText) || it.insertText.contains(searchText)
        }
        return filtered.take(topK)
    }
    
    override suspend fun getCategories(): List<String> {
        return emojiList.map { it.category }.distinct()
    }
    
    override fun getIcon(): PluginIcon? = PluginIcon(assetName = "icon.webp")
    
    // 或者使用文本图标：PluginIcon(text = "🐰")
    
    // hasSettings() 默认返回 false，不需要设置界面
    // openSettings() 默认空实现
}
```

### 5. 实现空的 PluginDeclaration

```kotlin
package com.example.plugin

import android.app.Activity
import android.os.Bundle

class PluginDeclaration : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 空实现，用于插件发现
    }
}
```

### 6. ProGuard 规则（可选）

如果启用混淆，需要添加规则：

```proguard
# Plugin ProGuard rules
-dontobfuscate
-optimizations !class/merging/*

-keep class kotlin.** { *; }
-keepnames class kotlin.** { *; }

-keep class com.example.plugin.** { *; }
-keepattributes SourceFile,LineNumberTable
```

**注意**：推荐禁用混淆（`isMinifyEnabled = false`），避免 Kotlin stdlib 方法丢失问题。

## 插件数据结构

### EmojiItem

```kotlin
data class EmojiItem(
    val id: String,          // 唯一标识
    val displayText: String, // 显示文本
    val insertText: String,  // 插入文本
    val imageUrl: String?,   // 图片 URL（本地路径或网络 URL）
    val category: String     // 分类名称
)
```

### PluginIcon

```kotlin
data class PluginIcon(
    val text: String? = null,    // 表情符号文本（如 "🐰"）
    val assetName: String? = null // assets 中的图标文件名（如 "icon.png"）
)
```

插件图标两种方式：
1. **文本图标**：`PluginIcon(text = "🐰")`
2. **图片图标**：`PluginIcon(assetName = "icon.webp")` - 将图标放在 `assets/icon.webp`

主应用自动从插件 APK 提取图片图标。

### PluginContext

```kotlin
data class PluginContext(
    val application: Application,    // 宿主 Application
    val pluginInfo: PluginInfo,      // 插件信息
    val dataDir: File                // 插件数据目录
)
```

## 安装和测试

### 构建

```bash
./gradlew assembleDebug
```

### 清除插件数据（调试用）

```bash
./gradlew clearPlugins      # 清除插件文件
./gradlew uninstallApp      # 完全卸载主应用
```

### 安装顺序

```bash
# 1. 卸载旧版本
adb uninstall com.kingzcheung.xime
adb uninstall com.example.kime.plugin.myplugin

# 2. 安装新版本
adb install app/build/outputs/apk/debug/Xime-xxx.apk
adb install my-plugin/build/outputs/apk/debug/my-plugin-xxx.apk
```

## 常见问题

### 1. ClassNotFoundException 或 NoSuchMethodError

**原因**：ProGuard 混淆导致 Kotlin 方法丢失

**解决**：
1. 禁用混淆：`isMinifyEnabled = false`（推荐）
2. 或添加 ProGuard 规则：`-keep class kotlin.** { *; }`

### 2. 插件无法发现

**原因**：AndroidManifest intent-filter 配置错误

**解决**：检查 `<action android:name="com.kingzcheung.xime.plugin.EXTENSION" />`

### 3. 插件加载失败

**原因**：插件入口类路径错误

**解决**：
- 检查 `plugin.entryClass` 元数据
- 确保类名完整：`com.example.plugin.MyPlugin`

### 4. 表情数据未显示

**原因**：
- `getEmojis()` 返回空列表
- 主应用未启用插件

**解决**：
- 检查 `loadEmojis()` 实现
- 在主应用设置中启用插件

## 现有插件示例

| 插件 | 类型 | 特点 |
|------|------|------|
| kaomoji | EMOJI | 预定义颜文字数据 |
| meme-bunny | EMOJI | 恶搞兔表情包（从 APK assets 加载） |

## 参考文档

- [plugin-core 源码](https://github.com/ximeiorg/Xime/tree/main/plugin-core) - 核心实现
- [现有插件实现](https://github.com/ximeiorg/Xime/tree/main/plugins) - 学习最佳实践

## 版本兼容

- 插件 compileSdk 应与主应用一致（36）
- 插件 targetSdk 应 ≥ 主应用 minSdk（28）
- Kotlin 版本应与主应用一致（2.3.20）