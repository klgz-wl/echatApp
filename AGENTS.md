# echatApp Project Conventions

- `/Users/kuailegeziwl/android/echatApp` is both the Android project root and the Git repository root. Open this directory in Android Studio; do not open `app/` as a separate project.
- Keep the project single-module (`:app`) until a real feature boundary justifies another Gradle module.
- `MainActivity` owns only the Android activity shell and root composition. Feature screens live under `ui/<feature>/`; reusable visual tokens live under `ui/theme/`.
- Put user-visible and accessibility text in Android string resources. Keep stable brand colors in the theme package.
- Put density-independent photographic content in `res/drawable-nodpi/`. Record third-party asset source and release constraints in `docs/asset-sources.md`.
- When adjacent Figma frames represent a task flow, model them as explicit Compose screen state and cover the transition with an instrumented UI test.
- When Figma frames differ by bottom navigation section, drive the visible section from the bottom navigation state and test the selected destination's headline and tabs.
- For commerce-style Figma sections before real billing exists, keep package data as local UI state/static model data, avoid creating payment behavior, and cover the default selection/CTA surface with an instrumented UI test.
- For account/profile Figma sections before backend identity data exists, model the profile and module rows as local placeholder UI state, avoid adding account storage or networking, and test the profile headline plus module entry points.
- For secondary account-module Figma frames before backend data exists, represent them as explicit in-feature screen state with a back transition to the originating account section and cover the module click plus back behavior with an instrumented UI test.
- For feedback Figma frames before upload/API support exists, implement only local static form UI and media action placeholders, keep submit/photo/library non-networked, and test the entry route plus visible form affordances.
- The Me profile name and avatar edit actions are backend-persisted. Use the in-feature editable name sheet for `PUT /api/v1/user/profile`, and use the single-image picker plus `/api/v1/files/upload` before persisting the returned avatar URL. Keep the two pencil actions distinct and expose loading/error/success states.
- Never commit `local.properties`, IDE state, build output, transient generated screenshots, keystores, or credentials. Curated design baselines under `docs/screenshots/` may be committed when their purpose and asset provenance are documented.
- Before completion, run `./gradlew --no-configuration-cache testDebugUnitTest assembleDebug lintDebug`. Run `connectedDebugAndroidTest` when a compatible emulator or device is available and UI behavior changed.

## 每次任务必须 git commit and push（Core脚手架规则）

1. 每次完成一个明确的开发、修复、重构、配置或文档任务后，自动执行中文 git commit，并 git push，无需重复询问。纯问答或没有文件变更时不制造空提交。
2. 开始和提交前检查 git status；只按明确路径暂存本任务文件，禁止 git add . 或 git add -A，禁止提交签名、密码、本机配置和构建缓存。
3. 提交前完成适用验证；纯文档检查内容、引用及 git diff --check。验证失败先修复，外部条件阻塞时如实记录，不伪称通过。
4. 推送当前分支已配置的 upstream；无 upstream 时推送 origin 的同名分支并建立跟踪。禁止 force push、改写已有提交或复制参考仓库的 .git/远程。
5. 无远程时保留本地提交并报告。网络、鉴权或推送失败时保留提交，报告原因，不把本地提交当作已推送；远程领先时正常整合并重新验证。
6. 保留目标已有 Git 仓库、分支和远程配置，不伪造作者或 Co-Authored-By。完成报告列出提交哈希、远程分支、变更与验证结果。
