# echatApp Project Conventions

- `/Users/kuailegeziwl/android/echatApp` is both the Android project root and the Git repository root. Open this directory in Android Studio; do not open `app/` as a separate project.
- Keep the project single-module (`:app`) until a real feature boundary justifies another Gradle module.
- `MainActivity` owns only the Android activity shell and root composition. Feature screens live under `ui/<feature>/`; reusable visual tokens live under `ui/theme/`.
- Put user-visible and accessibility text in Android string resources. Keep stable brand colors in the theme package.
- Put density-independent photographic content in `res/drawable-nodpi/`. Record third-party asset source and release constraints in `docs/asset-sources.md`.
- Never commit `local.properties`, IDE state, build output, transient generated screenshots, keystores, or credentials. Curated design baselines under `docs/screenshots/` may be committed when their purpose and asset provenance are documented.
- Before completion, run `./gradlew --no-configuration-cache testDebugUnitTest assembleDebug lintDebug`. Run `connectedDebugAndroidTest` when a compatible emulator or device is available and UI behavior changed.
