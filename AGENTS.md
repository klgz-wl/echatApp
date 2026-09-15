# echatApp Project Conventions

- `/Users/kuailegeziwl/android/echatApp` is both the Android project root and the Git repository root. Open this directory in Android Studio; do not open `app/` as a separate project.
- Keep the project single-module (`:app`) until a real feature boundary justifies another Gradle module.
- `MainActivity` owns only the Android activity shell and root composition. Feature screens live under `ui/<feature>/`; reusable visual tokens live under `ui/theme/`.
- Put user-visible and accessibility text in Android string resources. Keep stable brand colors in the theme package.
- Put density-independent photographic content in `res/drawable-nodpi/`. Record third-party asset source and release constraints in `docs/asset-sources.md`.
- When adjacent Figma frames represent a task flow, model them as explicit Compose screen state and cover the transition with an instrumented UI test.
- When Figma frames differ by bottom navigation section, drive the visible section from the bottom navigation state and test the selected destination's headline and tabs.
- For commerce-style Figma sections before real billing exists, keep package data as local UI state/static model data, avoid creating payment behavior, and cover the default selection/CTA surface with an instrumented UI test.
- Never commit `local.properties`, IDE state, build output, transient generated screenshots, keystores, or credentials. Curated design baselines under `docs/screenshots/` may be committed when their purpose and asset provenance are documented.
- Before completion, run `./gradlew --no-configuration-cache testDebugUnitTest assembleDebug lintDebug`. Run `connectedDebugAndroidTest` when a compatible emulator or device is available and UI behavior changed.
