# Android 归因与首页加载

本页记录 `echatApp` 当前真实运行链路。入口为 `MainActivity` → 地区准入 → `ImageToVideoScreen`，认证和接口由 `AchatRepository` / `AchatSessionManager` / `AchatBackendClient` 执行。

## 地区准入

prod Debug 与 Release 均启用 Splash 地区检查，受限名单固定为 `CN,HK,MO,TW`；中国大陆、香港、澳门、台湾之外的有效地区全部允许。dev 保持关闭。未知、无效或查询失败不能作为其他地区放行。地区通过前不创建或启动归因、统计和业务依赖。

## 统一归因链

进程内采用 `AppsFlyer SDK → 前置单次 GCD → 必要时 Google Play Install Referrer`。SDK `Non-organic` 立即结束辅助确认；SDK `Organic` 独立保存后发起一次前置 GCD。GCD 的 Organic 和 Non-organic 均按 `gcd_http` 来源保存；仅 GCD 也明确 Organic 时读取同安装缓存或 Google Play Install Referrer，只有有效 `gclid`，或白名单付费 `utm_medium` 与有效 `utm_source` 同时存在，才生成 `google_install_referrer` 媒体补充结果。

前置和首页 GCD 各最多一次、串行执行、不轮询。首页基础界面完成首帧后，若仍无媒体结果，再使用独立的一次 GCD 机会；页面重进、刷新、分页和 Retry 不增加机会。失败、超时、无 UID、坏 JSON 或未知状态均有限结束，不伪造 Organic。

## 持久化与补报

私有 SharedPreferences `achat_attribution.xml` 中，`install_attribution_ledger_v1` 保存来源化结果、稳定 resultId、安装内递增 index，以及按稳定 userId 建立的 reportId/outbox。写入使用同步 `commit`，先落盘再请求 `POST /api/v1/attribution/report`。旧键 `appsflyer_install_attribution` 仅用于登录兼容，不为其伪造来源或新报告。

同一来源与完整 JSON 重复时复用结果；不同来源独立保存。每个 userId + resultId 的 `report_id` 在重试、令牌刷新和重启后保持不变。只有 HTTP 与业务 `code == 0` 且成功状态再次落盘，才算补报成功。请求新增：`report_id`、`startup_elapsed_ms`、`attribution_result_index`、`attribution_stage`、`attribution_result_source`、`is_install_referrer_supplement`、`is_gcd_http_result`。

最终完成状态只接受现有媒体标准：当前结果必须是有效 `Non-organic`，独立补报已获得服务端明确业务成功，并在一次同步账本提交中同时保存报告成功和完成记录。完成记录包含稳定 userId、本地安装 UUID、包名、flavor、resultId、reportId、来源和完成时间；不设置有效期。Organic、SDK 初始化、普通回调、请求发出、超时、取消、失败或落盘失败均不标记完成。历史账本中符合相同条件的成功媒体报告会迁移为同范围完成记录。

## Splash 与首页

Splash 保留认证前最多 3 秒、认证后最多 8 秒窗口。媒体结果立即完成；Organic 在前置 GCD/Referrer 链结束后可提前完成。没有有效结果时按阶段超时放行，不能推造自然归因。

首页基础页面先显示 loading，首帧后触发一次 home verification。若存在有效结果，首页数据等待当前稳定用户的最终结果真实补报成功，并共享成功后剩余 1000ms；跨进程已成功媒体记录不恢复旧单调时间，也不重新等待。补报耗尽显示可重试错误，待报记录继续保留。

后续进程会在创建归因协调器、reporter 或调用任何 Splash/Home 归因钩子前读取完成记录。身份范围完全匹配时，认证前/后等待、GCD、Referrer、补报重试、首页门禁和成功后延迟全部旁路；离线也不会因归因阻塞页面。AppsFlyer 仍可为普通业务统计初始化，但恢复期间的 conversion/deep-link 回调不再进入归因账本或产生归因回调事件。新登录账号或安装 UUID、包名、flavor 不匹配时，恢复原有未完成归因流程。

完成态的匿名登录仍保留后端要求的非空 `attribution` 对象，但使用无 AF UID、渠道和原始数据的空归因形状，不重复携带历史安装结果。历史成功报告先作为已持久化事实直接旁路，显式完成记录在 IO 线程补写，避免升级后的首次启动因同步磁盘迁移阻塞；旧账号迟到成功只更新旧账号账本，不改变新账号的进程完成状态。

## 事件与验收

只有真实 AppsFlyer conversion 回调产生 `attribution_success` / `attribution_failure`。数数接收 `s_` 事件，自有后台接收 `z_` 事件；AppsFlyer、Firebase、GCD、Referrer 和页面超时不接收或伪造这两类手动回调事件。

2026-10-20 已通过完整 Gradle `test`、`:app:assembleDevDebug`、`:app:generateProdDebugBuildConfig` 和 `:app:lintDevDebug`；Lint 为 0 Error，并保留项目现有 Warning。Google Play 实际来源、AppsFlyer/数数/后台实收、断网杀进程恢复以及服务端 `report_id` 幂等和旧结果防回退仍需真机与后台联调，构建通过不代表平台验收。
