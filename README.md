# InstaFolio

A modern, fast, and privacy-focused universal media downloader and carousel album binder for Instagram reels, carousels, photos, videos, and stories — available for **Android** and **macOS Desktop**.

**Zero ads. Zero tracking. Lossless media quality. Direct on-device processing.**

---

## ✨ Features

- ⚡ **Universal Media Extraction**: Download Reels, Videos, Photos, Stories, full multi-slide Carousels (tested up to 20+ slides), and HD Profile Pictures.
- 📑 **Instant PDF Export**: Automatically compile multi-photo carousels into a clean, multi-page PDF document.
- 🎨 **Adaptive Responsive UI**:
  - **Desktop Split Studio Mode**: Fluid 2-column workspace for wide / large screens (`>= 860.dp`), automatically switching to a centered compact column on smaller windows.
  - **Mobile & Tablet Optimized**: Smooth portrait and landscape adaptation with centered constraints and scaled hero headers.
- 🔐 **Dual Mode (Public & Authenticated)**:
  - **Guest / Public Mode**: No login or credentials needed for public posts, reels, and profiles.
  - **Authenticated Studio Mode**: Connect your session token with 1-click clipboard paste. Automatically validates cookies, resolves your `@username`, full name, and avatar, and unlocks private posts & Stories.
- 📁 **Custom Storage Routing**: Save directly to your preferred folder with full scoped storage support.
- 📳 **Tactile Haptic Feedback**: Crisp haptic responses for extraction and completion events.
- 🌓 **Dynamic Theming**: True dark mode (OLED optimized) and light mode.

---

## 🚀 Supported Links

- `instagram.com/reel/...` & `instagram.com/reels/...`
- `instagram.com/p/...` (Single posts and multi-item carousels)
- `instagram.com/tv/...`
- `instagram.com/stories/...` (With active session connected)
- `instagram.com/<username>/` (HD Profile Picture & account metadata)
- `instagram.com/share/...` direct share links

---

## 🛠️ Usage

1. Copy any Instagram post, reel, or story URL.
2. Open **InstaFolio** (or share directly from Instagram).
3. The app automatically fetches the media and presents a live preview.
4. Select your preferred format (**JPG Images**, **Multi-page PDF**, or **Both**).
5. Tap **Download** to save directly to your storage folder.

---

## 💻 Build & Development Guide

### Prerequisites
- **JDK 17+** (Required for Kotlin & Compose Desktop / Android Gradle Plugin)
  ```bash
  # macOS (Homebrew or system JDK)
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  ```
- **Android SDK** (API 35+) with `ANDROID_HOME` configured (for Android builds)

All Gradle commands should be executed from the project root:
```bash
cd InstaDownload
```

---

### 📱 Android (`:app`)

- **Package**: `com.prakash.instafolio`

#### 1. Build Debug APK
```bash
./gradlew :app:assembleDebug
```
> **Output location**: `/tmp/instafolio_app_build/outputs/apk/debug/instafolio-debug.apk`

#### 2. Build Release APK
```bash
./gradlew :app:assembleRelease
```
> **Output location**: `/tmp/instafolio_app_build/outputs/apk/release/app-release-unsigned.apk`

#### 3. Build Google Play Bundle (AAB)
```bash
./gradlew :app:bundleRelease
```
> **Output location**: `/tmp/instafolio_app_build/outputs/bundle/release/app-release.aab`

#### 4. Install Directly to Connected Device / Emulator
```bash
./gradlew :app:installDebug
```

---

### 🖥️ Desktop (`:desktop`)

- **Package**: `com.prakash.instafolio.desktop`

#### 1. Run Locally (Development Mode)
```bash
./gradlew :desktop:run
```

#### 2. Build macOS DMG Installer (`.dmg`)
```bash
./gradlew :desktop:packageDmg
```
> **Output location**: `/tmp/instafolio_desktop_build/compose/binaries/main/dmg/InstaFolio-2.6.0.dmg`

#### 3. Build macOS PKG Installer (`.pkg`)
```bash
./gradlew :desktop:packagePkg
```

#### 4. Build Standalone Universal Uber JAR
```bash
./gradlew :desktop:packageUberJarForCurrentOS
```

#### 5. Build Native App Image Folder (`.app`)
```bash
./gradlew :desktop:packageDistributionForCurrentOS
```

---

### 🧪 Testing

#### Run All Unit Tests
```bash
./gradlew test
```

#### Run Desktop Tests Only
```bash
./gradlew :desktop:test
```

#### Run Android Unit Tests Only
```bash
./gradlew :app:testDebugUnitTest
```

---

### 🧹 Cleaning & Repository Maintenance

#### 1. Clean Gradle Build Artifacts
```bash
./gradlew clean
```

#### 2. Deep Clean (Stops Daemons & Wipes Transient Caches)
```bash
./gradlew clean --no-daemon
rm -rf /tmp/instafolio_*_build .gradle build app/build desktop/build desktop/bin
```

#### 3. Clean macOS AppleDouble Metadata (`._*` files)
When working on external drives, USBs, or macOS exFAT/HFS volumes:
```bash
# From workspace root
dot_clean -m .
```

---

## 🔒 Privacy & Security

- **100% On-Device**: All media parsing, network requests, and PDF rendering occur directly on your local device.
- **No Analytics or Trackers**: Zero telemetry, advertising SDKs, or third-party intermediary servers.
- **Direct Instagram Stream**: Files are streamed directly from Instagram's CDN servers to your local filesystem.

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).