# Android 归因与首页加载

本页记录当前 `echatApp` 运行链路的约定。实际入口为 `MainActivity` → 地区准入 → `ImageToVideoScreen`，认证和接口由 `AchatRepository` / `AchatSessionManager` / `AchatBackendClient` 执行。Core/Hilt 参考路径不代替此入口的验收。

## 启动和首页

地区检查通过后初始化数数并订阅归因结果，再允许 AppsFlyer 使用当前 RESUMED Activity 启动。Splash 在同一页面内顺序执行：本进程归因最多 3 秒、真实匿名认证最多 30 秒、认证成功后归因最多 8 秒、首次 profile 最多 30 秒。只有有效 Non-organic 能提前结束归因阶段；Organic、失败、未知或无回调等满阶段窗口。首次 profile 的 `is_new` 决定 A/B，认证或 profile 失败留在 Splash 供 Retry。

首页壳在首次 profile 后显示。首页归因窗口最多 10 秒；有本进程有效 Organic 或 Non-organic 快照时，模板和分类等待当前会话 epoch、当前快照的 `POST /api/v1/attribution/report` 业务成功，以及从成功回执单调时间起算的剩余 1 秒。没有有效实时快照时，窗口结束后直接加载。首页刷新、模板分类切换和分页也经过同一门禁；已满间隔的回执直接复用。补报后 profile 独立等待同一回执的剩余间隔后刷新 A/B，不阻塞首页。

归因快照改变或会话 epoch 改变时，旧首页请求失效并重新等待当前目标；当前目标补报耗尽后显示错误，Retry 合并重启有限重试。失败后迟到的不同媒体快照会触发自动恢复。安装归因缓存仅为登录参数提供历史值，不充当本进程实时快照或补报回执。全局 SDK 重试直到取得媒体结果；补报和后续 profile 各自最多 10 次，与全局 SDK 重试无关。

## 事件

仅真实 AppsFlyer conversion 回调产生归因结果事件。每次有效 Organic／Non-organic 回调向数数发送 `s_attribution_success`；conversion 失败或无效状态发送 `s_attribution_failure`。手动参数仅包括 `callback_id`、`request_attempt`、`app_version`、`platform` 以及对应的 `attribution_status` 或 `failure_reason`。事件通过数数专属出口，不进入四端业务队列。SDK Launch 响应、页面超时及自有接口失败不产生这两个事件。数数后台实收需另行核验。

## 配置与验证

超时、重试、SDK 最短会话间隔和本地在途保护配置在 `config/app.properties`，由 Gradle 生成当前 flavor 的 BuildConfig。保留各 flavor 的 SDK 身份、业务域名、地区规则、Firebase 与签名配置。单元测试验证阶段顺序、请求保护、补报回执、首页门禁与事件映射；设备验收另查真实归因、前后台、弱网、服务端补报后数据可见性和数数后台实收。构建通过不等于平台验收。
