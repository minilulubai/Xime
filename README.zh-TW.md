<p align="center">
  <img src="docs/logo.jpg" alt="Xime Logo" width="600">
</p>

<h1 align="center">Xime（曦碼） - 五筆/拼音輸入法</h1>

<p align="center">
  <a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a>
</p>

[Xime 輸入法 (Windows 版)](https://github.com/ximeiorg/winxime) | [Xime 輸入法 (Linux 版)](https://github.com/ximeiorg/xime-wayland) | [聯想詞預測模型](https://github.com/ximeiorg/predictive-text) | [手寫輸入法模型](https://github.com/ximeiorg/ochwpro)

一款基於 <a href="https://rime.im/">Rime</a> 引擎構建的 Android 五筆/拼音輸入法，專注於簡潔高效的中文輸入體驗。

如果你覺得 UI 或者功能不符合你的要求，你可以直接 fork 一份自行修改。

---

> 本輸入法支援五筆/拼音輸入，只是本人以五筆為主，拼音為輔，因此資源會傾向於五筆為主。

<table align="center">
  <tr>
    <td><img src="docs/Screenshot/full_keyboard_light.jpg" width="180"><br><p align="center">全鍵盤（亮色）</p></td>
    <td><img src="docs/Screenshot/full_keyboard_dark.jpg" width="180"><br><p align="center">全鍵盤（暗色）</p></td>
    <td><img src="docs/Screenshot/全键盘_下滑_light.jpg" width="180"><br><p align="center">字根下滑</p></td>
    <td><img src="docs/Screenshot/shotcut_light.jpg" width="180"><br><p align="center">快捷操作</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/floating.jpg" width="180"><br><p align="center">懸浮鍵盤</p></td>
    <td><img src="docs/Screenshot/t9_pinyin.jpg" width="180"><br><p align="center">T9 九宮格拼音</p></td>
    <td><img src="docs/Screenshot/number.jpg" width="180"><br><p align="center">數字鍵盤</p></td>
    <td><img src="docs/Screenshot/symbol.jpg" width="180"><br><p align="center">符號鍵盤</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/hw.png" width="180"><br><p align="center">手寫輸入</p></td>
    <td><img src="docs/Screenshot/hw2.png" width="180"><br><p align="center">手寫找字（候選）</p></td>
    <td><img src="docs/Screenshot/voice.jpg" width="180"><br><p align="center">語音輸入</p></td>
    <td><img src="docs/Screenshot/emoji.jpg" width="180"><br><p align="center">Emoji 鍵盤</p></td>
  </tr>
  <tr>
    <td><img src="docs/Screenshot/theme_light.jpg" width="180"><br><p align="center">主題設定（亮色）</p></td>
    <td><img src="docs/Screenshot/theme_dark.jpg" width="180"><br><p align="center">主題設定（暗色）</p></td>
    <td><img src="docs/Screenshot/plugin_light.jpg" width="180"><br><p align="center">外掛管理</p></td>
    <td><img src="docs/Screenshot/方案市场.jpg" width="180"><br><p align="center">方案市場</p></td>
  </tr>
</table>

## 功能特點

- **多種輸入方案** - 內建五筆86/98、拼音、混輸方案，支援自訂（雙拼、筆畫等），可透過方案市場下載或無線匯入
- **Rime 引擎** - 使用成熟穩定的 Rime 輸入法引擎，精準可靠的中文輸入體驗
- **豐富鍵盤佈局** - QWERTY 全鍵盤、T9 九宮格拼音、九宮格筆畫、手寫、數字（含計算機）
- **懸浮鍵盤** - 懸浮卡片樣式，支援拖拽移動、半透明圓角設計
- **語音轉文字** - 支援阿里百煉 FunAsr（線上）和 sherpa-onnx（本機離線）雙引擎
- **AI 智能增強** - 基於 Transformer 的聯想詞預測和標點預測，輸入更高效
- **簡潔介面** - Material Design 3 風格，支援淺色/深色主題及多種配色方案
- **鍵盤調節** - 支援鍵盤高度調整和位置移動
- **工具列定製** - 可自訂工具列按鈕佈局和功能
- **按鍵反饋** - 可調節音效和振動強度
- **滑動手勢** - 游標移動、刪除、符號輸入等滑動手勢操作
- **剪貼簿管理** - 剪貼簿歷史記錄，支援快捷傳送和置頂
- **候選詞編碼提示** - 候選詞顯示五筆編碼，輔助學習
- **字根顯示** - 下滑按鈕顯示五筆字根，方便健忘用戶
- **實體鍵盤支援** - 連接實體/藍牙鍵盤時顯示浮動候選欄
- **WebDAV 同步** - 透過 WebDAV 備份和還原方案與設定
- **表情外掛** - 支援擴充套件表情外掛（顏文字、表情包等）

## 系統需求

- Android 9.0 (API 28) 及以上

## 安裝

### 主程式下載

選擇對應架構的 APK：
- **arm64-v8a**: 適用於大多數現代手機（**絕大部分人的手機都是這個**）
- **armeabi-v7a**: 適用於舊款32位元手機
- **x86_64**: 適用於模擬器
- **universal**: 包含所有架構，體積較大

### 外掛下載（選用）

外掛為獨立 APK，安裝後可在主應用程式中啟用：
- **meme-bunny**: 惡搞兔表情包外掛（提供8個表情）
- **kaomoji**: 顏文字外掛（提供精選顏文字）

### 從 Release 下載

1. 在 [Releases](https://github.com/ximeiorg/Xime/releases) 頁面下載最新版本的 APK
2. 安裝應用程式
3. 在系統設定中啟用 Xime 輸入法
4. 將 Xime 設為目前輸入法

### 國內下載

由於 APK 包是透過 GitHub Actions 自動構建的，國內的倉庫沒有免費的功能使用，因此如果你覺得 GitHub Release 下載不穩定，請自行構建安裝，或者透過 [https://github.akams.cn](https://github.akams.cn) 來下載。

### 手動構建安裝

1. 克隆專案並構建 APK
2. 安裝應用程式
3. 在系統設定中啟用 Xime 輸入法
4. 將 Xime 設為目前輸入法

## 使用文件

詳細使用說明請檢視 [使用文件](https://ime.ximei.me)。

## 構建

```bash
# 克隆專案（包含子模組）
git clone --recursive https://github.com/ximeiorg/Xime.git

# 或者在已克隆的專案中初始化子模組
git submodule update --init --recursive

# 構建 Release APK
./gradlew assembleRelease
```

### 本地語音辨識構建

專案支援本地離線語音辨識（基於 sherpa-onnx）。首次構建時會自動下載並編譯 JNI 函式庫。

如果自動構建失敗，可手動執行：

```bash
# 手動構建 sherpa-onnx JNI 函式庫
./build-sherpa-onnx.sh
```

構建完成後，會在 `app/src/main/jniLibs/` 產生 `libsherpa-onnx-jni.so`。

本地 ASR 模型可在應用程式內設定頁面下載。

### AI 模型下載

#### 智慧聯想詞模型

- **專案地址**: https://github.com/ximeiorg/predictive-text
- **模型下載**: https://www.modelscope.cn/models/bikeand/predictive-text-small
- **模型檔案**: `model_int8_dynamic.onnx`（約 17MB）
- **詞表檔案**: `vocab.json`
- **存放位置**: `filesDir/` 目錄（即應用私有目錄根目錄）
- **功能**: 基於 Transformer 的中文聯想詞預測，提供智慧候選詞推薦

#### 標點預測模型

- **專案地址**: https://github.com/ximeiorg/srf-punctuation
- **線上演示**: https://srf-punctuation.ximei.me/
- **模型下載**: https://www.modelscope.cn/models/bikeand/srf-punctuation
- **模型檔案**: `punctuation_int8.onnx`（約 2.2MB）
- **詞表檔案**: `vocab.json`
- **存放位置**: `filesDir/punctuation_models/` 目錄
- **功能**: 基於 Transformer 的中文標點預測，語音辨識後自動新增標點

**注意**: 所有模型均可直接在應用程式內「設定 > 智慧聯想/語音辨識」頁面下載，無需手動放置。

## 技術棧

- Kotlin
- Jetpack Compose
- Material Design 3
- Rime (librime)
- JNI (Native C++)

## 貢獻

歡迎貢獻！在提交 PR 之前，請先閱讀 [CONTRIBUTING.md](CONTRIBUTING.md) 瞭解貢獻流程。

核心規則：
- **先提 Issue** — 所有改動必須先建立 Issue 討論
- **最小修改** — PR 只包含所需的最小改動
- **GPG 簽章** — 所有 commit 必須 GPG 簽章

## 致謝

- [Rime](https://rime.im/) - 中州韻輸入法引擎
- [Trime](https://github.com/osfans/trime) - 同文輸入法，設定參考
- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) - 鍵盤佈局參考
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - 本地語音轉文字模型支援

## Star History

<a href="https://www.star-history.com/?repos=ximeiorg/Xime&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=ximeiorg/Xime&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=ximeiorg/Xime&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=ximeiorg/Xime&type=date&legend=top-left" />
 </picture>
</a>

## 授權條款

GPLv3 License
