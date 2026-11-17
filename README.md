# PulseHUD V3

PulseHUD is a native Kotlin + Jetpack Compose Android floating HUD. V3 continues the uploaded V2 project and retains its package name (`com.pulsehud`), app identity, preference store, overlay service, and accessibility service.

- **Target:** Android 16 / API 36
- **Minimum:** Android 10 / API 29
- **Language/UI:** Kotlin, Jetpack Compose, Android Views for the lightweight floating overlay
- **Version:** 3.0.0

## V3 implementation status

### Implemented in this source

- Dark charcoal dashboard with orange accent styling.
- Floating overlay foreground service with persistent screen position.
- Single **Move HUD / Done** toggle. Normal mode uses `FLAG_NOT_TOUCHABLE`; move mode accepts touch and drags the overlay. Position is saved on release.
- Functional horizontal, vertical, and custom-grid rendering in the actual overlay.
- 4 × 6 editor with 24 independently assignable cells, an explicit empty assignment, and immediate persistence.
- Custom grid preserves empty cells and uses the exact row/column cell positions.
- Live layout preview uses current observed CPS, peak, total, and battery values. FPS is shown as unavailable.
- Built-in Minimal, Gaming, Neon, Glass, and Terminal presets can be previewed and applied.
- Custom presets save and restore cell assignments, layout, text color, font size, opacity, alignment, and CPS window.
- Battery percentage and battery temperature are independent metrics and can be assigned to separate cells. The initial default layout is vertical so they appear on separate lines.
- Accessibility click-event counting excludes PulseHUD's own package. Total clicks and peak CPS persist; current CPS uses a monotonic rolling time window.
- Missing overlay permission and unavailable battery readings are handled without substituting fake measurements.
- GitHub Actions debug-APK workflow at `.github/workflows/android.yml`.

### Explicit limitations — not represented as completed

**Actual game FPS:** The HUD deliberately displays `FPS N/A`. The old V2 Choreographer counter was removed because it measured PulseHUD's own rendering, not the game. Android's documented SurfaceFlinger `--timestats` workflow can report presented frame statistics per layer, but access generally requires shell/root-level access and matching the correct foreground game layer. The project does not yet contain a verified Shizuku UserService bridge or a device-tested SurfaceFlinger parser. Therefore no FPS value is fabricated or inferred.

**CPS in games:** The AccessibilityService counts `TYPE_VIEW_CLICKED` and `TYPE_VIEW_LONG_CLICKED` events exposed by another app. Many games draw their own touch controls and expose no accessibility click events, so CPS may stay at zero or undercount in those games. This is not universal raw touchscreen capture and has not been validated against a specific game on the target Infinix phone.

**Android 14+ motion events:** Android added `AccessibilityService.onMotionEvent()` in API 34. It requires requesting motion-event sources and is not documented as a guarantee that every physical touchscreen action in every game will be observed. The related touch-exploration path has additional interaction implications. It is not enabled by default because it would be misleading to market it as universal game-tap capture.

**Shizuku:** Shizuku can run code with ADB-shell or root identity after the user explicitly authorizes the app. It is the likely route for a future SurfaceFlinger FPS backend, but V3 does not currently request Shizuku permission or execute shell commands. Installing Shizuku alone will not make the FPS widget start working in this build.

**Device verification:** This source has not been compiled or run on the target Infinix device in this environment. It has not been tested with an actual game. Please treat the debug build as a development build until the build workflow and on-device checklist below pass.

## Build the debug APK

### GitHub Actions (recommended)

1. Create a GitHub repository and upload the contents of this folder to its root.
2. Push to `main` or `master`, or open **Actions → Build PulseHUD V3 APK → Run workflow**.
3. Wait for the `debug-apk` job to finish.
4. Download the `PulseHUD-V3-debug-apk` artifact from the workflow run.
5. Extract it and install `app-debug.apk` on the Android phone. If Android blocks the install, allow installation from the browser/file manager used to open the APK.

The workflow uses JDK 17, Android SDK Platform 36, Build Tools 36.0.0, and Gradle 8.11.1.

### Android Studio / local Gradle

1. Install Android Studio with Android SDK Platform 36 and Build Tools 36.0.0.
2. Use JDK 17 (JDK 21 can also run Gradle 8.11.1, but the project targets Java 17 bytecode).
3. Open this `PulseHUD` project directory.
4. Run `gradle :app:assembleDebug` from the project root, or run the `app` configuration in Android Studio.
5. The APK output is `app/build/outputs/apk/debug/app-debug.apk`.

## First-run setup

1. Install and launch PulseHUD.
2. Tap **Start HUD**. Grant **Display over other apps** when Android opens the permission screen, return to PulseHUD, then tap **Start HUD** again.
3. To enable the available CPS source, open **Accessibility settings** and enable PulseHUD's accessibility service. Review the service description first.
4. Return to the app and start a game. The CPS reading reflects only click/long-click events that the game exposes to Android Accessibility.
5. Tap **Move HUD** to drag the overlay. Tap **Done** to restore touch-through mode. In normal mode, the overlay is marked not-touchable so it should not intercept game input.
6. Assign metrics in the 4 × 6 editor, choose Horizontal / Vertical / Custom Grid, and apply a preset if desired.

## On-device verification checklist

- Grant overlay permission and start the foreground overlay.
- Confirm the overlay is visible above another app.
- Confirm normal mode does not intercept taps; move mode drags; Done returns to touch-through mode.
- Move the HUD, stop the service, restart it, and confirm the position is retained.
- Assign one metric to a non-default cell, leave neighboring cells empty, switch to Custom Grid, and confirm the overlay matches the editor.
- Switch between Horizontal and Vertical and confirm the actual overlay changes direction.
- Change battery percentage and temperature assignments independently.
- Save a custom preset, force-stop/reopen the app, preview it, then apply it and verify settings restore.
- Enable Accessibility and test click events in a normal app and in the target game. Compare with a manual tap count; do not assume the game exposes events.
- Confirm FPS remains unavailable unless a future verified SurfaceFlinger backend is added.

## Research references

- [Android AccessibilityService API — `onMotionEvent`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onMotionEvent(android.view.MotionEvent))
- [Android AccessibilityServiceInfo API — motion event source configuration](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo)
- [Android game frame-rate diagnostics — SurfaceFlinger TimeStats](https://developer.android.com/games/optimize/framerate)
- [Shizuku API and developer guide](https://github.com/RikkaApps/Shizuku-API)

## Privacy

The app stores HUD preferences and click totals locally in app-private SharedPreferences. It does not upload gameplay or battery metrics. Accessibility permission is optional and should only be enabled if you accept its scope.
