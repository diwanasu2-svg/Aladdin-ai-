# Aladdin Vision System — Integration Guide

## Module Structure

```
android/vision/
├── build.gradle.kts                          ← Gradle dependencies
├── src/main/
│   ├── AndroidManifest.xml                   ← Permissions & service declarations
│   ├── res/
│   │   ├── values/strings.xml
│   │   └── xml/accessibility_service_config.xml
│   └── java/com/aladdin/vision/
│       ├── VisionModule.kt                   ← MAIN ENTRY POINT (singleton)
│       ├── camera/
│       │   ├── CameraService.kt              ← CameraX: preview, photo, video, flash
│       │   └── CameraModels.kt               ← FlashMode, PhotoResult, VideoResult
│       ├── ocr/
│       │   ├── OCRService.kt                 ← ML Kit Text Recognition (multi-language)
│       │   └── OCRResult.kt                  ← TextBlock, TextLine, TextElement
│       ├── understanding/
│       │   ├── GeminiVisionService.kt        ← Gemini Vision API integration
│       │   └── VisionUnderstandingResult.kt
│       ├── screenshot/
│       │   ├── ScreenshotService.kt          ← MediaProjection screen capture
│       │   ├── ScreenCaptureService.kt       ← Foreground service (required)
│       │   └── ScreenshotResult.kt
│       ├── detection/
│       │   ├── ObjectDetectionService.kt     ← ML Kit Object Detection
│       │   └── DetectedObject.kt             ← Bounding boxes + labels
│       ├── document/
│       │   ├── DocumentScannerService.kt     ← ML Kit Document Scanner
│       │   └── DocumentScanResult.kt
│       ├── barcode/
│       │   ├── BarcodeService.kt             ← ML Kit Barcode/QR Scanner
│       │   └── BarcodeResult.kt              ← Structured data (URL/contact/calendar/WiFi)
│       └── accessibility/
│           ├── AladdinAccessibilityService.kt ← Android Accessibility API
│           └── ScreenContent.kt              ← UIElement, ScreenContent
```

---

## 1. Add to your app's build.gradle.kts

```kotlin
dependencies {
    implementation(project(":vision"))
}
```

---

## 2. Initialize

```kotlin
// In Application.onCreate() or wherever you initialize Aladdin:
val vision = VisionModule.getInstance(applicationContext)
vision.configureGemini("YOUR_GEMINI_API_KEY")  // Get at aistudio.google.com/app/apikey
```

---

## 3. Camera (CameraX)

```kotlin
// In Fragment/Activity with a PreviewView:
vision.startCameraPreview(previewView, viewLifecycleOwner)

// Capture photo
lifecycleScope.launch {
    val result = vision.capturePhoto()
    if (result is PhotoResult.Success) {
        val uri = result.uri  // Content URI to the saved photo
    }
}

// Video recording
vision.startVideoRecording { /* onStart callback */ }
// ... later:
lifecycleScope.launch {
    val result = vision.stopVideoRecording()
}

// Flash
vision.setFlashMode(FlashMode.AUTO)   // or ON, OFF, TORCH
vision.toggleTorch()                   // toggle continuous torch
vision.flipCamera()                    // front/rear switch
```

---

## 4. OCR (ML Kit Text Recognition)

```kotlin
// From a Bitmap:
lifecycleScope.launch {
    val result = vision.recognizeText(
        bitmap = myBitmap,
        language = RecognitionLanguage.LATIN,       // or CHINESE, DEVANAGARI, JAPANESE, KOREAN
        includeHandwritten = true
    )
    println(result.fullText)
    result.blocks.forEach { block ->
        println("Block: ${block.text}, bounds: ${block.boundingBox}")
    }
}

// From a URI:
val result = vision.recognizeTextFromUri(imageUri)
```

---

## 5. Gemini Vision API

```kotlin
lifecycleScope.launch {
    // Caption
    val caption = vision.captionImage(bitmap)
    println(caption.text)

    // Object list
    val objects = vision.recognizeObjects(bitmap)
    println(objects.text)

    // Scene analysis
    val scene = vision.understandScene(bitmap)
    println(scene.text)

    // Custom question
    val answer = vision.askAboutImage(bitmap, "What brand is on the label?")
    println(answer.text)
}
```

---

## 6. Screenshot (MediaProjection)

```kotlin
// Step 1 — Request permission (in your Activity):
val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)

// Step 2 — Also start foreground service BEFORE calling captureScreen (Android 10+):
startService(Intent(this, ScreenCaptureService::class.java))

// Step 3 — In onActivityResult:
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_SCREEN_CAPTURE) {
        lifecycleScope.launch {
            val screenshot = vision.captureScreen(resultCode, data!!)
            if (screenshot.success) {
                imageView.setImageBitmap(screenshot.bitmap)
                // Optionally analyze with Gemini:
                val analysis = vision.understandScene(screenshot.bitmap!!)
            }
        }
    }
}
```

---

## 7. Object Detection (ML Kit)

```kotlin
lifecycleScope.launch {
    val objects = vision.detectObjects(bitmap)
    objects.forEach { obj ->
        println("${obj.displayName} (${(obj.confidence * 100).toInt()}%) at ${obj.boundingBox}")
    }
}
```

---

## 8. Document Scanning (ML Kit)

```kotlin
// Register launcher in Fragment/Activity:
val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    if (result.resultCode == RESULT_OK) {
        val scanResult = documentScannerService.handleScanResult(result.data)
        scanResult.pages.forEach { page ->
            // page.imageUri — JPEG URI for each scanned page
        }
        // page.pdfUri — PDF URI (all pages combined)
    }
}

// Launch scanner:
lifecycleScope.launch {
    vision.scanDocument(scanLauncher)
}

// Or process an existing bitmap:
val result = vision.processDocumentBitmap(bitmap)
```

---

## 9. QR Code / Barcode

```kotlin
// Single image:
lifecycleScope.launch {
    val barcodes = vision.scanBarcodes(bitmap)
    barcodes.forEach { barcode ->
        when (barcode.valueType) {
            BarcodeValueType.URL      -> openUrl(barcode.url!!)
            BarcodeValueType.CONTACT  -> saveContact(barcode.contactInfo!!)
            BarcodeValueType.CALENDAR -> addCalendarEvent(barcode.calendarEvent!!)
            BarcodeValueType.WIFI     -> connectToWifi(barcode.wifiInfo!!)
            else                      -> println(barcode.rawValue)
        }
    }
}

// Live camera:
vision.startLiveBarcodeScanning { barcodes ->
    barcodes.firstOrNull()?.let { handleBarcode(it) }
}
// Stop:
vision.stopLiveBarcodeScanning()
```

---

## 10. Screen Understanding (Accessibility API)

```kotlin
// First: guide user to Settings → Accessibility → Aladdin Vision → Enable

// Check if enabled:
if (vision.isAccessibilityServiceEnabled()) {
    val screen = vision.getScreenContent()
    println("App: ${screen.packageName}")
    println("All text: ${screen.allText}")

    // Find elements:
    val buttons = screen.findByClassName("Button")
    val searchBox = vision.findElementById("com.example.app:id/search_input")

    // Click a node programmatically:
    vision.findElementsByText("Submit").firstOrNull()?.let { node ->
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
```

---

## Required Permissions Summary

Add to your **app's** AndroidManifest.xml (the vision module's manifest merges automatically):

```xml
<!-- These are already in the vision module manifest and will merge -->
<!-- Only needed if you override the manifest merger -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

Runtime permissions to request at runtime:
- `CAMERA`
- `RECORD_AUDIO` (for video)
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (Android 13+)
- `READ_EXTERNAL_STORAGE` (Android ≤ 12)
- Screen capture: via `MediaProjectionManager.createScreenCaptureIntent()`

---

## Gemini API Key

Get your free API key at: https://aistudio.google.com/app/apikey

Store it securely — never commit it to source control. Use:
- Android `local.properties` → `GEMINI_API_KEY=...`
- `BuildConfig` field via `buildConfigField` in your app's `build.gradle.kts`
- Or pass it at runtime from your backend
