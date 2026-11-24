# SnapEdge

A native Android utility app combining a persistent **Edge Swipe Sidebar** + **Home Screen Quick Widget** with **Circle-to-Extract OCR**, **Long Scrolling Screenshots**, **Floating Pin Windows**, and **Privacy Auto-Redaction**.

## Core & Smart Features
1. **Swipeable Edge Sidebar (`EdgeOverlayService`)**:
   - Lives discreetly on the left or right edge over *any* app.
   - Swipe inward (or tap) to open the floating dock; drag vertically along the edge to reposition.
   - Customizable side, vertical offset, and opacity inside `MainActivity`.
2. **Circle-to-Extract Text & QR (`LassoCaptureOverlay` + `SmartTextAnalyzer`)**:
   - Captures the current screen and lets you draw a freehand **Circle/Lasso** or **Box** over any area.
   - Runs offline **Google ML Kit OCR** + **QR/Barcode decoding**.
   - Detects smart actionable entities automatically: **OTP codes**, **Inline Math calculations**, **URLs**, **Emails**, **Phone numbers**, and **QR payloads**.
3. **Long / Scrolling Screenshot (`LongScreenshotOverlay` + `BitmapStitcher`)**:
   - **Auto-Scroll Mode** via optional `AutoScrollAccessibilityService` (auto-scrolls and stops when the bottom is reached).
   - **Manual Assist Mode** (`+ Capture` pill) if Accessibility is not enabled.
   - Stitches frames seamlessly using horizontal strip SAD overlap matching while excluding static status/navigation bars.
4. **Additional Smart Features**:
   - **Floating Pin-to-Screen (`PinnedFloatingWindow`)**: Keep circled text or image crops floating in a draggable always-on-top mini window across apps.
   - **Markup & Privacy Auto-Redact Editor (`MarkupEditorActivity`)**: Highlight, manually redact/blackout, or tap **Auto-Redact** to automatically mask detected emails and phone/card numbers before saving or sharing.
   - **Clip Vault**: Saves extracted snippets locally with quick copy and pin actions.
   - **Home Screen Quick Widget (`SnapEdgeWidgetProvider`)**: Toggle the Edge Sidebar or launch Circle OCR / Long Shot directly from your launcher.

## Building the APK
- **Android Studio**: Open `/data/data/com.termux/files/home/SnapEdge` and run **Build > Build APK(s)** (uses AGP 8.5.2, Gradle 8.7, Kotlin 2.0.20, JDK 17).
- **Command line** (where Gradle/Android SDK is available):
  ```bash
  gradle assembleDebug
  ```
- **GitHub Actions**: Push to `main`/`master` to trigger `.github/workflows/build-apk.yml` and download `SnapEdge-debug-apk`.
