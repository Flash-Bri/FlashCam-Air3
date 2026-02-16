# FlashCam-Air3

**Unlock the full 16MP camera on your INMO Air3 AR glasses — no root required.**

FlashCam-Air3 is an open-source camera app that bypasses the INMO Air3's firmware-imposed 3MP capture limit by using the Android Camera2 `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` API. The Air3's 16MP sensor (4656×3496) is fully accessible to standard apps through the max-resolution stream map — INMO just never exposed it in their default camera app.

## Key Discovery

The INMO Air3 (IMA301, Android 14) advertises `ULTRA_HIGH_RESOLUTION_SENSOR` capability. While the default stream map limits JPEG output to 2048×1536 (3.1MP), the **maximum-resolution stream map** exposes:

| Format | Resolution | Megapixels |
|--------|-----------|------------|
| JPEG | 4608×3456 | 15.9 MP |
| RAW/DNG | 4656×3496 | 16.3 MP |

This app uses the standard Camera2 API to capture at these full resolutions. No root, no vendor file edits, no firmware mods.

## Features

### Still Photo Modes
- **8MP / 12MP / 16MP** toggle — selects the closest available camera output size
- **16MP** uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` for true full-sensor capture
- JPEG quality fixed at 100%
- Proper pixel-rotated orientation (images saved upright, not relying on EXIF rotation)

### RAW/DNG Support
- Toggle DNG capture on/off (default: off to reduce capture delay)
- Proper `.dng` files via `DngCreator` with full metadata
- Opens in Lightroom, Photoshop, Google Photos, and any DNG-compatible editor
- DNG and JPEG share the same timestamp for easy pairing

### Video Recording
- **1080p @ 30fps** and **4K @ 30fps** (if supported by HAL)
- Landscape and Portrait orientation modes
- Recording indicator with timer
- Proper orientation metadata for correct gallery playback

### Camera Controls
- **Tap-to-focus** with visual focus ring indicator
- **Exposure compensation** (EV+/EV-) with real-time preview adjustment
- **AF/AE status indicator** showing focus and exposure state

### Orientation & Preview Framing
- **Landscape** (default): saves the full sensor frame (e.g., 4608×3456 for 16MP)
- **Portrait**: center-crops the landscape frame to **3:4 aspect ratio**, then rotates 90° CW to produce a true portrait image (height > width). No pixel data is stretched — it's a clean crop from the center of the sensor.
- **Pixel rotation** — saved images are physically rotated to be upright. EXIF orientation is always NORMAL for maximum compatibility.
- **Auto-correct** — after rotation, the app verifies W>H for landscape and H>W for portrait. If mismatched, an emergency 90° correction is applied and logged.
- **Capture frame overlay** — a semi-transparent shade shows the area outside the capture frame, with an orange border marking the exact capture area. In Portrait mode, the overlay shows the 3:4 crop region.
- Overlay updates instantly when toggling between Portrait and Landscape.

### Gallery Integration
- Photos saved to `Pictures/FlashCam-Air3/` via **MediaStore** (scoped-storage safe)
- Immediate Gallery visibility without manual scanning
- Fallback to direct file write if MediaStore fails
- File naming: `FlashCam_YYYYMMDD_HHMMSS_<8MP|12MP|16MP>_<land|port>.jpg`

### Debug/Receipt System
- Toggle debug receipts on/off (default: off)
- After each capture, shows: mode, orientation, sensorOrientation, JPEG_ORIENTATION sent, crop info (for portrait), requested vs actual dimensions, orientation correctness check, file path, file size
- Copy receipt to clipboard or export full capture log (last 50 captures)

## Device Compatibility

| Field | Value |
|-------|-------|
| Device | INMO Air3 (IMA301) |
| OS | Android 14 (SDK 34) |
| Build tested | Air3_DU_V3.11.027_202601230319 |
| SoC | Qualcomm Snapdragon 6 Gen 1 |
| Camera sensor | 16MP (4656×3496 physical) |

> **Note:** This app requires `ULTRA_HIGH_RESOLUTION_SENSOR` capability and `SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION` in the Camera2 HAL. It was developed for and tested on the INMO Air3. It may work on other devices with similar Camera2 capabilities.

## Install

### From GitHub Release (recommended)
1. Download the APK from the [Releases](../../releases) page
2. Transfer to your Air3 via Google Drive, USB, or direct download
3. Open the APK on the Air3
4. If prompted, enable "Install from unknown sources" for the app you used to open the APK
5. Tap Install

### From Source
```bash
# Clone
git clone https://github.com/Flash-Bri/FlashCam-Air3.git
cd FlashCam-Air3

# Build debug APK (no signing setup needed)
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Signing Setup (for Release Builds)

Release builds require a signing keystore. **Never commit signing secrets to the repository.**

1. Copy the example config:
   ```bash
   cp keystore.properties.example keystore.properties
   ```

2. Edit `keystore.properties` with your actual values:
   ```properties
   storeFile=release-key.jks
   storePassword=your_actual_password
   keyAlias=your_key_alias
   keyPassword=your_actual_key_password
   ```

3. Generate a keystore (if you don't have one):
   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias your_key_alias
   ```

4. Build the signed release APK:
   ```bash
   ./gradlew assembleRelease
   ```

The `keystore.properties` file and all `*.jks` / `*.keystore` files are in `.gitignore` and will never be committed. For CI/CD, set the same properties as environment variables.

> **Security note:** The v1.3 release inadvertently committed hard-coded keystore passwords to git history. Those credentials have been rotated. If you forked before v1.4, rotate your signing keystore and passwords.

## Build Requirements

- JDK 17
- Android SDK 34
- Android Build Tools 34.0.0
- Gradle 8.4
- Android Gradle Plugin 8.2.0

## Permissions

| Permission | Purpose |
|-----------|---------|
| `CAMERA` | Camera access for preview and capture |
| `RECORD_AUDIO` | Audio recording for video mode |
| `READ_MEDIA_IMAGES` | Gallery access (Android 13+) |
| `READ_MEDIA_VIDEO` | Gallery access for video (Android 13+) |

## Preview Framing Behavior

The preview uses **center-crop scaling** to fill the display without distortion. The capture frame overlay shows exactly what will be saved:

- **Landscape mode**: the overlay matches the full 4:3 sensor frame
- **Portrait mode**: the overlay shows the 3:4 center-crop region — the shaded area on the sides will be cropped out of the saved image
- **Tap-to-focus** only responds to taps within the clear (capture) area of the overlay
- Focus coordinates are correctly mapped from the overlay region to sensor coordinates

## Test Checklist (v1.5)

Use this checklist to verify all features after installing:

| # | Test | Expected Result |
|---|------|----------------|
| 1 | Launch app, grant CAMERA permission | Preview appears, status shows "Ready" |
| 2 | Tap shutter in LANDSCAPE + 16MP | Receipt shows 4608×3456, orientation correct: YES |
| 3 | Toggle to PORTRAIT, tap shutter | Receipt shows ~2592×3456 (3:4 crop), H > W, orientation correct: YES |
| 4 | Toggle DNG ON, capture in 16MP | DNG file saved alongside JPEG, ~31MB |
| 5 | Open saved JPEG in Gallery | Image is upright (not sideways) |
| 6 | Open saved DNG in Lightroom | DNG opens with correct orientation and white balance |
| 7 | Tap preview to focus | Yellow focus ring appears at tap point, AF indicator updates |
| 8 | Press EV+/EV- | Preview brightness changes, EV value updates |
| 9 | Toggle to 8MP, capture | Receipt shows ~3264×2448 or closest size |
| 10 | Switch to VIDEO mode, record 10s | Video saved, receipt shows file path and size |
| 11 | Tap shutter 10 times rapidly | No orange square, no crash, white flash each time |
| 12 | Leave idle 30 seconds | Status text stable, no flicker |
| 13 | Enable DBG, capture, tap COPY | Receipt text copied to clipboard |
| 14 | Tap EXPORT LOG | `flashcam_log.txt` saved to Pictures/FlashCam-Air3/ |

## Known Limitations

1. **Max-res capture requires session switch** — switching between default and max-res modes requires closing and reopening the camera session, which causes a brief preview interruption (~0.5s)
2. **No autofocus during max-res capture** — some devices may not support AF in max-res mode; the app falls back gracefully
3. **DNG files are large** — ~31MB per capture at full sensor resolution
4. **Video is always default-mode** — video recording uses the default stream map (not max-res)
5. **Portrait mode crops to ~75% of sensor width** — the 3:4 crop from a 4:3 landscape frame discards the left and right edges. This is by design (center-crop, not stretch).
6. **SENSOR_PIXEL_MODE may not be supported on all devices** — all access is wrapped in try/catch; the app falls back gracefully to default mode if the API throws.
7. **INMO Air3 specific** — the app was designed for and tested on the INMO Air3. Other devices with `ULTRA_HIGH_RESOLUTION_SENSOR` may work but are untested.

## How It Works

The INMO Air3's camera HAL (Hardware Abstraction Layer) exposes two stream configuration maps:

1. **Default map** (`SCALER_STREAM_CONFIGURATION_MAP`): Max JPEG 2048×1536 (3.1MP)
2. **Max-res map** (`SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION`): Max JPEG 4608×3456 (15.9MP)

Standard camera apps only query the default map. FlashCam-Air3 queries the max-res map and uses `SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION` in both the session configuration and capture requests to access the full sensor resolution.

### Portrait Mode Implementation

Portrait mode does **not** rotate the entire sensor output. Instead:

1. The camera captures a full landscape frame (e.g., 4608×3456)
2. A center-crop extracts a 3:4 region (e.g., 2592×3456) from the middle
3. The cropped region is rotated 90° CW to produce a portrait image (3456×2592)
4. The result is saved as a standard portrait JPEG with EXIF orientation NORMAL

This approach preserves maximum image quality — no interpolation, no stretching, just a clean geometric crop and rotation.

## Troubleshooting

| Issue | Solution |
|-------|---------|
| "Camera open failed" | Close all other camera apps, reboot the Air3, try again |
| Max-res capture times out | Ensure no other app is using the camera; reboot |
| Images appear sideways | Update to v1.5+; deterministic rotation with auto-correct should handle this |
| DNG won't open in editor | Ensure you're using a DNG-compatible editor (Lightroom, RawTherapee, etc.) |
| App crashes on launch | Ensure Camera permission is granted in Settings > Apps > FlashCam-Air3 |
| Preview shows black bars | Normal — letterbox/pillarbox areas are outside the capture frame |
| Orange square on shutter | Update to v1.4+; this was a UI bug fixed in the shutter animation |
| Portrait image has wrong aspect | Update to v1.5+; portrait is now a center-crop to 3:4 |
| "SENSOR_PIXEL_MODE not supported" | The HAL may not support this API; the app falls back to default mode |

## License

MIT License — see [LICENSE](LICENSE) for details.

## Credits

- Developed with assistance from [Manus AI](https://manus.im) and [ChatGPT (OpenAI)](https://openai.com)
- Inspired by the INMO Air3 community's discovery that the 16MP sensor was firmware-locked to 3MP
- Camera2 max-res API documentation: [Android Developer Docs](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
