# 给 Codex：新 App 双入口订阅与模板 VIP 接入提示词

版本：2026-11-12。参考实现：echatApp，分支 `core_9`，提交 `b46b23f`。

使用方法：在新 App 的 Codex 聊天中附上本文件，并说明目标项目目录。以下正文就是执行提示词，无需同时提供本次开发的聊天记录。已有目标项目配置优先复用；文中的占位符不是真实接口参数，不能原样发送。

---

## 你的任务

请在当前 Android App 中实现下述**两种入口，共用一套 Google Play 订阅能力**，完成代码、本地验证和问题说明。不要只输出方案或静态页面，不要重建已有充值、登录、网络、生成任务或埋点系统。

两种入口的准确含义：

1. **普通 VIP 标签入口**：视频页、图片页、Top Up、个人页等非模板内容上的账户 VIP 标签，点击打开完整订阅页。
2. **生成按钮入口**：用户选中图片／视频模板，进入上传页并上传图片后，点击 **Continue／生成视频／生成图片** 按钮时，根据模板和会员资格决定是否展示订阅覆盖层。

第二种入口**不是点击模板卡片，也不是点击 USE THIS TEMPLATE 时直接拦截**。这两个操作应允许用户进入上传流程。

这里的“两种”是两种业务入口／页面形态，不是两套 BillingClient，不是“订阅与钻石充值”两种购买类型，也不是两套并行支付 owner。

## 1. 先调查现有项目，再实施

先阅读目标项目的 AGENTS.md、相关 Skill、支付／订阅／充值／模板代码和现有接口文档。查明以下信息，能够从仓库或现有配置取得的不要反复询问用户：

|信息|处理要求|
|目标项目与语言|沿用现有模块划分、Compose／其他 UI、网络层和依赖注入|
|App 包名、版本、签名、环境|使用目标项目配置，不复制参考 App 的值|
|USER_API_BASE_URL|现有用户业务 API 源地址，下面路径已含 `/api/v1`|
|CHAT_API_BASE_URL|现有模板／生成业务 API 源地址，下面路径已含 `/api/v1`|
|PAYMENT_API_BASE_URL|现有支付服务基址，下面使用相对路径 `client/payments/...`|
|账户与会话|复用现有登录 token、401 刷新、userId、session epoch|
|支付与通知|已有 BillingClient、支付请求协调器、订单存储、RTDN／充值通知|
|会员商品|服务端商品 ID、Google SKU、真实订阅周期、报价字段|
|法律链接|使用目标 App 配置，不能复制其他品牌的 Terms／Privacy 地址|

默认只修改客户端、使用现有后台接口。不直接改后台、不清数据库、不删除用户订单、不清空 App 数据。如果目标环境缺少接口或契约不同，明确指出差异，不能编造接口或静默绕过校验。

把本文件视为最终产品规则；旧指南中与本文冲突的规则不得继续照搬，尤其是“VIP 点击去充值”“进入上传前拦 VIP”“只展示 Play 查到的套餐”“取消后必须复用旧单”等旧行为。

## 2. 两个入口的产品规则

### 2.1 普通 VIP 标签 → 完整订阅页

- 所有可见的非模板 VIP 标签，无论当前用户是 VIP 还是非 VIP，点击都进入订阅页。
- 已是 VIP 不能因此跳到 Top Up。
- 标签颜色可以继续反映会员状态；未知会员状态保持未知，沿用已有隐藏／加载策略，不能显示成确认非会员。
- **钻石余额按钮**继续去 Top Up，不与 VIP 标签共用错误的导航分支。
- 保存真实入口 trigger，返回时回到原页面／导航位置。
- 普通订阅页保留现有顶部导航、续订说明和 Terms 链接。

### 2.2 上传后 Continue／生成按钮 → VIP 资格判断

流程必须是：

```text
浏览模板 → USE THIS TEMPLATE → 上传图片成功 → 点击 Continue／生成
  ├─ 模板 vip=false：原有生成／钻石流程
  └─ 模板 vip=true：查询有效会员资格
       ├─ is_vip=true：直接提交生成，不做客户端余额拦截
       ├─ is_vip=false：打开模板专用订阅覆盖层
       └─ 资格未知／查询失败：停留上传页，可重试，不生成、不假定非会员
```

- 图片和视频都采用上述流程，不仅实现视频。
- 上传本身不是新增 VIP 门槛；未完成上传不允许继续生成。
- `USE THIS TEMPLATE` 只展示按钮文案，去掉钻石图标和消耗数量；保留报价数据供原业务使用。
- VIP 模板的会员用户直接调用原生成 API，不先判断本地钻石余额。
- 普通模板即使由 VIP 用户使用，仍保留原有钻石规则。
- **客户端跳过余额判断不代表后台免费。** 本次规则不修改后台价格／扣费。VIP 模板若仍收到后台 402，应显示真实错误，不自动引导充值，也不能伪造生成成功。
- 普通模板的余额不足继续走原充值路径。
- 查询资格、提交生成期间阻止重复 Continue。
- 只有真正发出生成请求时，才上报对应提交事件；被会员检查挡住不能伪装成已提交。

### 2.3 模板订阅覆盖层的返回与续接

- 覆盖层关闭后保留原上传页，不销毁已选图片、上传 resource_id、模板、quality 和已有生成幂等键。
- 后台确认订阅后刷新 `/vip/info`；确认 `is_vip=true` 后回到原上传上下文。
- 不凭支付回调设置 VIP=true；未确认权益时显示等待／可重试状态。
- 不在关闭覆盖层、恢复前台或购买成功回调中自动再次收费生成。回到原操作后，由用户再次 Continue，使用保留的同一逻辑请求键。
- 如果已经拿到 task_id，优先恢复该任务，而不是重新创建生成请求。
- 当前账户／会话已经改变时，旧请求不能打开新账户的订阅层或更新其状态。

## 3. 页面设计与商品展示

可复用设计参考：

- 模板 VIP 标签：[Figma 1206:1828](https://www.figma.com/design/JKsmuWA408do7TUxEGQN6D?node-id=1206-1828)
- 模板入口订阅覆盖层：[Figma 1206:1954](https://www.figma.com/design/JKsmuWA408do7TUxEGQN6D?node-id=1206-1954)

实现 Figma 时先读取设计上下文，适配目标 App 的组件和主题；不直接粘贴 React 示例。图标使用实际导出素材或已确认相同的项目素材，不自行猜画。

### 模板标识

- 只按模板条目 `vip` 显示标识，与当前用户是否 VIP 无关。
- 普通模板不打标；不要从名称、价格、SKU、标签或时长猜测 VIP。
- 模板卡片上的 VIP 标识是内容属性，不是“已是会员就去充值”的账户按钮。

### 模板专用覆盖层

- 深色半透明、圆角、霓虹描边，顶部拖动装饰和可访问的关闭按钮。
- 皇冠、权益对比、真实套餐卡片；正文支持滚动。
- 底部固定 Subscribe；等待、取消、错误提示在按钮附近可见，短屏也能看到。
- **底部不展示续订说明和 Terms 链接**；仅模板入口使用这一规则。
- 和普通订阅页共用商品选择、取消、等待、错误及购买控制器，不能另建支付实现。
- 关闭覆盖层不影响上传页数据。

### 商品列表

- 展示后台返回的全部有效订阅商品：`type=membership`、`is_subscription=true`，并通过周期／价格／币种等必要数据校验。
- **不按 Google Play 查询结果隐藏后台套餐。** 后台有而 Play 没有的套餐也展示。
- 有精确匹配的 Play offer 时使用实际格式化价格；没有时可先显示后台参考价。
- 点击 Subscribe 时再校验 SKU 和可用 offer；缺失则提示“Google Play 暂不可用，请选择其他套餐”。
- 缺 Play 商品／offer 不得建单，不得拉起支付；提示不能替换整个商品列表或清空选择。
- 商品加载失败、成功空列表和单个套餐暂不可用是三种不同状态。
- 不复制 Figma 的示例金额、SAVE 42%、年度折算月价或第三个套餐；只有真实数据支持才展示。

## 4. 必须区分的接口字段

|字段|含义|不能混用成什么|
|---|---|---|
|模板条目 `vip: boolean`|内容是否 VIP 专属|不是当前用户是否 VIP|
|列表参数 `is_vip=0/1`|是否包含 VIP 内容|不是会员授权，也不是 Boolean true/false|
|`GET /vip/info` 的 `is_vip`|当前有效订阅资格|不是 `auto_renew`、等级或某个日期推算|
|会员商品 `is_subscription`|商品是否订阅型|不等于当前用户有会员|
|服务端商品 ID|建单的 product_id|不等于 Google SKU|
|Google SKU|查询／购买 Play 商品|不等于业务订单 ID|

会员资格只接受成功响应中明确的 Boolean。`auto_renew=false` 但 `is_vip=true` 是合法的未到期取消续订状态，不得拒绝。

## 5. 后台接口契约

使用现有鉴权、中间层、公共头和响应处理。401 走原刷新令牌流程，不能为付费操作静默创建新逻辑请求。不要额外发送 user_id、app_id、金额或自行指定身份。标准响应的 type 非空时先走现有统一解码层；不能把密文 data 直接当业务对象，无法解码时保持失败／未知。

### 5.1 会员商品

```http
GET USER_API_BASE_URL/api/v1/products?product_type=membership&platform=android&location=store
```

使用真实 `products` 列表，保留商品 ID、type、is_subscription、周期／value、price、currency、google_product_id、third_party_product_id。

优先使用明确的 Google SKU；备用映射按目标环境契约确认，不能把任意业务商品 ID 直接当 Google SKU。

### 5.2 有效会员资格

```http
GET USER_API_BASE_URL/api/v1/vip/info
```

标准成功响应的 `data.is_vip` 为本次资格依据。不要从 profile 里寻找本次没有新增的会员字段，也不要从充值商品的 `user_info.is_vip` 替代有效订阅资格。

### 5.3 图片／视频模板

```http
GET CHAT_API_BASE_URL/api/v1/visual-generation/image/templates?is_vip=1&page=1&page_size=20&sort_by=latest
GET CHAT_API_BASE_URL/api/v1/visual-generation/video/templates?is_vip=1&page=1&page_size=20&sort_by=hot
```

- 所有分页、刷新和重试都保留整数 `is_vip=1`，使非会员也能看到普通与 VIP 混排。
- `is_vip=1` 不是“只返回 VIP”；不传／0 会排除 VIP 模板。
- 条目 `vip` 是 Boolean；显式错误类型不能静默当普通模板。
- 兼容旧部署缺少字段时可以延续旧普通模板行为，但必须记录联调待确认，不能宣称旧环境已支持 VIP 字段。
- 保持既有 category、sort、home_featured 和分页语义，不能为了 VIP 功能悄悄改变内容池。

### 5.4 上传与实际生成接口

```http
POST CHAT_API_BASE_URL/api/v1/visual-generation/resources
Content-Type: multipart/form-data
Form image=<用户选择的源图片，最大20MiB>
```

上传成功为 HTTP201，使用实际响应的 `data.id` 作为后续 resource_id。上传本身不新增 VIP 门槛。

```http
POST CHAT_API_BASE_URL/api/v1/visual-generation/image/tasks
POST CHAT_API_BASE_URL/api/v1/visual-generation/video/tasks
Idempotency-Key: <本次稳定的逻辑生成请求键>
Content-Type: multipart/form-data

Form template_id=<真实模板ID>
Form quality=fast 或 quality
Form resource_id=<当前用户已上传图片的资源ID>
```

- 两种生成都使用源图片。也可用 Form image 直接上传，但首次创建时 image 与 resource_id 二选一，不能都缺或同时发送；推荐复用现有上传资源流程。
- 让网络库生成 multipart boundary，不把这些字段改成 JSON。
- Header Idempotency-Key 推荐必传；接口也支持 Form idempotency_key，至少一项，两者同时有时 Header 优先。不要用两个不同键表示同一次操作。
- 来源条目若实际返回 catalog_context，完整原样传 catalog_id、catalog_item_id、catalog_binding_revision；未返回时不自行编造。
- 成功为 HTTP202、code=0，意味着任务已接收，保存实际 task_id；不是视频／图片已经生成完成。
- 用 GET CHAT_API_BASE_URL/api/v1/visual-generation/tasks/<task_id> 恢复任务，遵守实际轮询间隔；succeeded 展示真实资源，failed 使用返回的失败／退款状态，不能自行承诺已退款。
- **服务端兜底必做：仅 HTTP403 且数字 code=200101 才打开订阅覆盖层，并刷新会员资格。** 即使 Continue 前检查通过，资格／模板属性也可能变化。普通403、401、402不能混为会员不足。
- 专用 VIP 拒绝后保留原上传图片、resource_id、模板、档位和生成键；不自动重新提交或换键收费。

### 5.5 新订阅与新充值统一业务建单

```http
POST USER_API_BASE_URL/api/v1/orders
Content-Type: application/json

{
  "product_id": "<真实服务端商品ID>",
  "trigger": "<本次真实入口>",
  "platform": "android"
}
```

这是最终选定的新建单接口，与参考 Yumo 使用的业务接口对齐。

- 新订阅和新充值都用该业务接口建单；保留各自独立商品类型和履约处理。
- 不再用 payment-service 的 `client/payments/orders` 创建新订单；该路径只服务于历史来源明确的旧请求恢复。
- 请求只发契约允许的字段。**不能添加 `pricing_mode=standard`、request_id、金额等自行发明的参数。** 曾核对到相关服务会拒绝未知字段。
- 业务 `/orders` 没有服务端 request_id 幂等保证。客户端本地生成 requestId 不代表后台会幂等。
- 建单前持久保存逻辑请求及来源；发送前保存 create-started 标记。
- 已经发出、但响应丢失或结果不明时，禁止自动重复 POST；显示订单未确认并保留记录。不擅自换接口或换一个 UUID 重试。
- 没有有效订单号，绝不能打开 Google 付款窗口。
- 校验返回 order_id 非空、product_id 等于本次选中商品、状态合法。
- `obfuscated_account_id`、`obfuscated_profile_id` 返回非空时原样持久化，不能覆盖成真实账号／订单 ID。
- 这两个可选字段确实缺失时，只有目标业务接口确认采用同一缺省契约，才用本次冻结 userId／orderId 作为 Play 标识；显式空字符串或不合法值不能胡乱补齐。

### 5.6 初始化、查单与恢复

```http
POST PAYMENT_API_BASE_URL/client/payments/initialize
{"order_id":"<已取得的业务订单ID>"}

GET PAYMENT_API_BASE_URL/client/payments/<业务订单ID>/status

GET PAYMENT_API_BASE_URL/client/payments/recover?product_id=<真实商品ID>
```

- 已知业务订单继续使用现有支付服务初始化和查单。确认环境中这些接口可以读取业务建单所写的同一订单、同 App／用户／Android 平台。
- SUBS 只接受合法的 official/google_play/sdk 路由，校验订单及 SKU 匹配后再打开 Play。
- 收到已有订单的初始化错误时，保留原单按错误分类重试，不能另建订单绕过。
- 订单成功至少区分 `paid` 与 `fulfillment_status=fulfilled`；只付款未履约仍需等待。
- `/vip/info` 是权益查询，不是按订单查单。余额变化也不是某一笔订单的付款证据。
- 不存在已确认的业务查单接口时，不能自行编造 `GET /orders/{id}`。
- 支付服务成功 envelope 与业务 API envelope 可能不同；不能强制所有接口都必须有 code=0，也不能把标准响应的非零业务码当成功。

## 6. 订单身份、来源与持久化

四个身份必须分开：

|身份|用途|
|---|---|
|ownerUserId|真实业务账号，用于归属、账号隔离|
|businessOrderId|真实后台订单号，用于查单、通知和恢复|
|obfuscatedAccountId|传给 Play 的混淆账号标识|
|obfuscatedProfileId|传给 Play 的混淆订单／档案标识|

每笔购买前冻结原账号、session epoch、商品、SKU、trigger、原会员状态及本次选中 offer 的 Play 微单位报价／币种。后续页面选择和登录账号变化不能覆盖旧订单快照。

持久记录至少覆盖：

```text
ownerUserId, requestId, businessOrderId, serverProductId, googleSku,
productType, billingType(SUBS/INAPP), orderCreationSource, orderCreateStarted,
obfuscatedAccountId, obfuscatedProfileId, trigger, session ownership,
offer/period, originalPlayPriceMicros, originalCurrency,
purchaseToken(仅内部), Play order ID, state/cancel evidence,
backend fulfillment evidence, success ledger identity/dedupe state
```

- 新请求持久来源为 business；旧记录缺少来源时按原 payment-service 来源恢复。
- 不能把未完成的旧幂等请求改成业务接口 POST，也不能改历史订单价格模式。
- 退出／切账号清显示与缓存，不删除原账号订单，不把原购买转给新账号。
- My Tasks／生成任务缓存必须按真实 userId 分区，整个登录后页面按 userId＋session epoch 重建。禁止把旧全局 tracked_tasks 无所属账号缓存直接认领给新登录用户；当前账号历史通过已鉴权后台接口恢复。
- 后台确认删除账号成功后，再清该账号任务缓存；删除失败不清理。远端删除与成功清理需跨 IO 返回取消仍能完成。清理后的旧 store 禁止迟到写入复活，不清其他账号、设备归因或未完成支付账本。
- 异步结果写入前同时检查账号 epoch 和请求新旧顺序，避免旧响应覆盖新状态。

## 7. Google Play SUBS 生命周期

- 使用进程级唯一 Billing owner 和全局购买准入，订阅／充值不能并发拉起。
- 查询 SUBS ProductDetails，选中精确匹配 SKU 和业务周期的 offer。
- 默认采用唯一、全价、自动续订 base plan；没有明确促销选择策略时，不猜 introductory offer、另一周期或多个候选中的某一个。
- 展示和购买使用同一个 offer；收入快照使用它的实际 pricing phase。
- 本地缺 SKU／offer 或 Activity 不可用时，在建单前提示，不能进入半套购买流程。
- 不缓存“已拥有”结果来长期禁用 Subscribe，上一次成功回执也不能永久禁用套餐。每次点击重新查询 Play SUBS 购买状态；失败保留保护，成功查询为空才允许新购。App 的缓存清理不能清除 Google 自身的订阅。
- 加白测试账号的短周期通常是续订周期，不是最终过期时间。例如月订阅测试周期通常为 5 分钟，还可能自动续期；不要用本地计时器强制判过期，应以当次 Play 查询为准。
- PURCHASED、PENDING、USER_CANCELED、未知／失败分别处理。
- SUBS 使用 acknowledge，**绝不能 consume 订阅**。
- ACK 暂时失败保留恢复记录并有界重试；不能删除独立统计账本，也不能把已确认付款说成付款失败。
- 查询和回调恢复都需要 token／业务订单精确关联；不能从多个同 SKU 候选中猜一个。
- 支付层自己执行履约／恢复，不依赖订阅页面一直存活。

### 恢复调度不能自循环

不要把 purchaseToken 永久放入“已恢复”集合后再尝试恢复：第一次网络失败会导致该会话以后永不重试。

采用单 worker，只合并正在执行的恢复；失败有界退避，耗尽后允许下一次前台继续触发。内部 queryPurchases 若会再次 emit recovered 事件，必须关闭本次内部查询的事件发送，否则形成 `restore → query → recovered → restore` 无限循环。

## 8. 取消支付：必须完整收尾

这里指**关闭本次 Google 购买弹框**，不是取消已生效的自动续订合同。

明确收到 USER_CANCELED 后：

```text
清理当前请求的本地 tokenless pending 占位
→ 结束这个 SDK 购买请求
→ 清理／归档当前业务 active 记录
→ 释放全局购买锁
→ 恢复 isSubscribing/isPurchasing=false
→ 恢复套餐／金额可选，保留页面原选择
```

- 清理只匹配本次 Play 账号标识、订单、商品和 Billing 类型；不要退回到“找一个同 SKU 记录”。
- 比较 Play 账号字段时，用 record.userId 对应 request.obfuscatedAccountId 等**相同语义字段**，不能拿业务 ownerUserId 与混淆账号 ID 比较。
- 已带真实 token 的 pending/purchased 购买不能被普通占位清理误删。
- 用 finally 结束捕获的那一个 SDK request；存储异常不能让 SDK 全局请求永久悬挂，也不能误结束后来创建的请求。
- 业务 active 清理应按 owner＋request 原子完成；取消快照覆盖同 request 的旧历史标记。
- 取消旧记录可以留作对账，但不得重新成为当前待处理单，不因恢复重新拿锁。
- 用户下一次点击订阅／充值，为当前选中商品创建新逻辑请求和新订单，不复用已明确取消的订单。
- 账号切换后到达的取消只清原账号对应请求，不修改新账号 UI。
- SDK 不可用、网络超时、PENDING、未知结果不能冒充用户取消。
- **不要调用第三方 `/close` 取消 Google 订单。** 现有接口仅支持第三方支付，且不等同直接取消业务订单。
- `official_sdk_cancelled` 事件即使上报成功，也只是记录行为，不代表后台订单取消或释放优惠名额。
- 本地清理不会释放后台首购预留。后台仍可能拒绝新单；显示真实错误，不能宣称必定可支付。

## 9. 充值取消不能只修“订阅取消”

必须检查 Top Up 自己的状态机和页面：

- `CLOSED` 不能一概当“已结束”；第三方关闭后可能仍付款，未知官方结果也需保护。
- 对明确 Google CANCELLED 持久保存订单级本地释放证据，如 `officialCheckoutReleased`。
- 只有官方支付、CLOSED 且具有释放证据的记录，才从充值 unresolved 中排除并允许新金额建单。
- 本地已释放的订单若再次被 `/recover` 以 pending 返回，按**精确订单 ID**跳过，不重新锁定用户。
- 同一订单的重复状态／轮询通知不能反复覆盖 selectedProductId；解锁后尊重用户新选择。
- 远端如果已经变成 paid／未履约，不能因为曾在本地取消就忽略真实付款。
- 旧版 CLOSED 无标记记录不能批量删除。只有成功查询 Play INAPP、对应 SKU 无 PENDING/PURCHASED、精确后台订单仍 pending，才安全释放；查询失败或 paid 不能释放。
- 旧订单晚到账按旧 key 履约和去重，不能覆盖当前正在处理的新金额、新订单。

## 10. 最重要的跨域坑：充值恢复不能接管订阅

**曾发生真实问题：Top Up 显示余额和充值卡片，但恢复接口返回了月订阅订单，页面长期显示 Waiting for payment confirmation，充值金额也被占住。**

`/recover?product_id=...` 不保证返回的就是该商品，也不保证一定是钻石订单。必须做以下隔离：

1. 根据返回的真实 `productId`，查询后台 membership／diamond 目录，正向确认商品类型。
2. 不按 `yumo_sub...` 这种 SKU 前缀、名称、价格或 trigger 猜测类型。
3. 明确 membership：交由订阅领域处理，不让 Top Up 接管，不 consume，不报充值成功。
4. 明确 diamond：保留原充值恢复，包括其他充值金额的真实未完成单。
5. Unknown、目录查询失败或矛盾：保持未知并提供重试，不把它当充值，不盲目创建可能重复的付款。
6. 对已被误写进充值存储的记录，持久标注 membership 并隔离，保留 owner／order／Play 身份和原状态，不删除真实订单。
7. 隔离覆盖 restore、buy、retry、claimOfficial、query、轮询、recharge 通知、consume 和成功埋点所有入口。
8. FAILED、OFFICIAL_CONSUMED 等历史订单也可能收到晚到通知，不能只迁移 unresolved 而漏掉它们。
9. 未知的已终结历史可保留，不必永久阻塞无关新购买；但它不能未经正向分类就通过迟到通知变成充值成功。

不要只隐藏 Waiting 文案或临时解锁卡片。恢复入口和数据归属不修，下一次查询仍会把错误订单拉回来。

## 11. 成功、到账和会员资格是三件事

|证据|能说明什么|不能直接推出什么|
|---|---|---|
|Google PURCHASED|Play 已确认本次购买|后台一定已发会员／钻石|
|后台 paid|后台已确认付款|履约一定完成|
|后台 paid＋fulfilled|该订单后台履约成功|新账号自动获得原账号权益|
|`vip/info.is_vip=true`|当前用户有效资格|任意某一笔订单一定成功|
|钱包余额上升／某笔 income|钱包发生变化|当前这笔订单一定成功|

- 余额刷新和订单确认可能先后返回。不要把 Play PURCHASED 或 consume 完成直接作为充值发币成功。
- 充值成功以原订单权威履约状态为准；通知只能加速查单。
- 钱包对账必须匹配 positive income、purchase 类别、payment_order 类型及精确 related_id，不能只比较余额。
- 订阅购买成功后独立刷新 VIP、用户资料及余额／额度；一个刷新失败不能跳过其他刷新，更不能把确认付款变失败。
- 后台已确认履约但 ACK 尚待重试时，持久保留已确认成功证据，后续网络失败不能把成功降回失败。
- 等待提示要说明当前订单；新订单不能让用户误以为上一笔已到账充值仍失败。

## 12. 埋点账本与收入

- 页面只负责曝光、选择和点击，不创建第二个支付成功出口。
- 订阅成功统计采用已关联原账号／订单的 Play PURCHASED，独立于 ACK、后台权益刷新和页面生命周期。
- 充值／第三方成功保持目标项目对应的真实履约口径，不能套用订阅口径。
- 使用统一 purchase_success 入口及目标项目已有各平台事件路由／开关；不要额外复制 Yumo 旧版 `purchase_custom`、`af_purchase` 等第二套成功计数。
- 账本独立于 pending：拉起 Play 前保存稳定 purchase_id、原账号、订单、trigger、原 is_vip、SKU、选中 offer 的微单位报价和币种。
- 回调只补 token／Play order ID／结果，不从当前页面重新读取用户、套餐或价格。
- 发送前原子 claim 去重；重复回调、恢复、轮询、通知及重启不能重复成功。
- 清理取消／ACK pending 不得删除独立账本。
- 订阅及 Google Play 收入用本次冻结的 Play 微单位金额／1_000_000；真实零元可记 0。第三方充值继续沿用目标项目已确认的冻结订单报价、币种和后台履约口径，不套用 Play 微单位规则，也不擅自删掉已有第三方收入统计。
- 金额／币种未知或无效时省略收入字段，不补 0、不固定 USD、不用当前新价给旧订单补账。
- 埋点异常不阻塞 ACK、查单或真实履约；不要把 token、JWT、签名写日志、Toast 或统计参数。
- 尊重已有远程统计开关和各 SDK 参数限制，不为此重建归因系统。

## 13. 这次开发已经踩过的坑：实施前逐条自查

|症状|实际原因／误区|必须采取的处理|
|---|---|---|
|取消后不能换套餐|UI 提示取消，但 active／锁／SDK request 未完整结束|清当前占位、结束捕获请求、原子归档、解锁、恢复选择|
|解锁后又被锁回|恢复读回了已取消历史，或 `/recover` 重新返回该 pending 单|精确订单级本地释放证据，恢复过滤，不复活取消单|
|选了别的金额又跳回去|每次支付状态发射都把原 productId 写回选择|只在真实活动单／新活动 key 时强制选择|
|取消清理漏记录|业务 owner 与 Play 混淆账号被拿来比较|比较同语义字段，订单／商品／类型精确匹配|
|取消后仍无法建单|误把“新请求已发出”说成“后台已创建成功”|检查真实 HTTP／业务码；区分本地取消与后台拒绝|
|Yumo 能建、目标 App 409|两者调用了不同建单 API，旧 pending 可能占首购资格|新单按本文业务 `/orders`；不假装客户端能释放后台预留|
|切业务建单后超时不断造单|把本地 requestId 当后台幂等键|来源与 create-started 持久化，未知业务创建不自动重发|
|所有新参数都发给后台|自行添加 standard／request_id 等未知字段|严格按接口 body，未知字段可能被后台拒绝|
|Google 提示 Already subscribed|已有 Play 购买，或多个后台套餐映射同一 SKU|查询实际购买并恢复；需要换购时单独对接官方换购，不能反复新购|
|Play 查询失败后目录全没了|把不可支付套餐从展示列表过滤，或把点击错误放入目录错误状态|展示后台套餐，点击时 Toast，保持卡片和选择|
|第一次恢复失败后永不恢复|token 被永久写入已处理集合|只合并执行中的任务，有限重试，允许下一次前台触发|
|恢复一直自触发|内部查询也 emit recovered 事件|内部查询关闭重复恢复事件|
|Top Up 等待的是订阅|恢复结果未按真实商品类型分域|membership 持久隔离，所有充值入口拒绝跨域|
|历史订阅又变成充值成功|只迁移未决记录，漏了 consume／failed 历史晚通知|历史入口也先分类，只处理正向 diamond|
|已到账但仍 Waiting|钱包、SDK、订单确认被混为一谈，或提示对应另一笔新单|对齐原订单状态／通知、区分展示，不让用户重复付款|
|图片上传后订阅回来又丢了|订阅导航销毁了上传组件或重新生成幂等键|覆盖层保留原页及资源／key，不自动重新收费|
|已会员仍去充值|复用了旧 VIP 用户 → Top Up 分支|账户 VIP 标签始终订阅，余额按钮单独充值|
|非会员还未上传就被挡住|把模板选择误当生成入口|会员判断只在 Continue／生成按钮|
|VIP=true 却生成失败|误认为客户端能免除后台计费或所有 403 都是会员问题|只识别专用 VIP code，真实 402／其他授权失败保持原语义|
|删除账号后新账号仍看到旧任务|全局任务缓存无 owner，或旧页面内存／轮询被沿用|按账号分区、按 epoch 重建 UI、忽略无主旧缓存、成功删除后清本账号并阻止迟到写|
|改完真机表现仍像旧版|同包名安装了另一个 App／版本，或看了旧 PID 日志|先核包名、Activity、版本、PID，再对照本次日志|

## 14. 实施步骤

1. 列出现有网络／会话／Billing／充值／生成入口及稳定接口差异，复用已有实现。范围限图片／视频模板与这两个订阅入口，不因参考文档出现 Feed／直播就擅自扩建这些功能。
2. 增加模板 vip 和有效 VipInfo 模型；接入两个模板列表 `is_vip=1`，保留原价格／分页行为。
3. 接入完整订阅页和模板专用覆盖层；实现普通 VIP 标签导航及 Continue 门禁。
4. 接入业务新建单工厂，并持久保存来源、创建不明状态和四组身份；保留旧来源恢复。
5. 完成 SUBS offer、购买请求、ACK、恢复 worker、账号隔离和独立成功账本。
6. 完整处理订阅取消及充值取消，覆盖选择、SDK 请求、持久记录、全局锁、恢复和晚通知。
7. 对充值恢复结果及历史记录做权威商品分类，隔离 membership，防止跨域消费／成功统计。
8. 补充聚焦回归测试并运行目标项目规定的检查；发现问题先定位证据，不反复只改提示文案。
9. 总结真实改动、测试结果及未验证范围。未获授权不进行真实付费、退款或后台数据清理。

## 15. 必须通过的验收矩阵

|场景|预期|
|---|---|
|VIP／非 VIP 点击普通账户 VIP 标签|均打开完整订阅页，不去充值|
|点击余额按钮|保持原 Top Up 入口|
|任意模板点击 USE THIS TEMPLATE|进入上传，不在这里拦会员，按钮无钻石数量|
|普通模板 Continue，会员／非会员|不新增会员请求，按原钻石逻辑|
|VIP 模板上传前|不允许未上传就生成|
|VIP 模板 Continue，is_vip=true，余额不足|不做客户端余额拦截，提交原生成 API|
|VIP 模板 Continue，is_vip=false|不提交生成，显示模板订阅覆盖层|
|VIP 模板会员未知／网络失败|停留原页，可重试，不假装非会员|
|后台 HTTP403＋code200101|订阅引导；保持原图片与生成 key|
|其他 403／401|保持原鉴权／错误逻辑，不误导订阅|
|普通模板余额不足|原充值逻辑|
|VIP 模板后台 402|显示真实错误，不自动去充值，不伪造成功|
|关闭订阅覆盖层|原图片、resource_id、模板、quality、生成 key 不丢|
|订阅确认后再 Continue|重新核资格，用原逻辑请求 key，不重复提交已存在任务|
|取消自动续订但未到期，is_vip=true|仍有资格，不依 auto_renew=false 拒绝|
|后台套餐 Play 缺失|卡片仍展示；点击提示；无后台建单和 Play 弹框|
|Play offer 歧义／周期不匹配|付款前拦截，不猜 offer|
|用户明确取消 Google 弹框|结束请求／解锁，保留原选择|
|取消后同套餐／换套餐再次订阅|新逻辑请求、新订单；不复用取消单|
|Top Up 取消后换另一金额|卡片可选；按钮使用新金额；生成新充值单|
|取消后重启／前后台切换|已释放旧单不重新锁回|
|PENDING／未知／建单响应丢失|不伪装取消、不重复 POST、不重复收费|
|业务建单缺订单号／商品不匹配|不打开 Play|
|Play 混淆账号与真实 owner 不同|请求、清理、恢复仍准确，不串账号|
|Google Already owned|恢复对应购买，不反复新购|
|恢复首次网络／ACK 失败|稍后仍可重试，无恢复循环|
|切换账号后旧回调到达|履约／统计用原快照，新账号 UI 不被覆盖|
|旧订单晚到账而新订单进行中|更新旧单并去重，不替换当前新单|
|充值 recover 返回 membership|不占充值选择、不 consume、不报充值成功|
|充值 recover 返回另一真实 diamond|保留其真实付款恢复保护|
|商品分类未知|保留记录、可重试，不当成充值|
|已污染 membership 为 FAILED／CONSUMED|晚通知仍不能进入充值成功|
|重复回调／重启恢复／通知／轮询|成功统计一次；金额来自原快照|
|模板覆盖层小屏／大字体|正文可滚动，关闭和固定 Subscribe 可用，支付提示可见|
|模板覆盖层底部|无续订说明与 Terms；普通订阅页保留|
|账号 A／B 切换与重启|各自只恢复自己任务，旧全局无主任务不自动迁移|
|删除账号成功／失败|成功仅清该账号任务缓存；失败保留；迟到写不能复活已清缓存|

## 16. 验证与交付要求

- 对关键状态流写可执行的回归测试，至少证明“取消 → 换金额 → 新订单”和“订阅恢复不能污染充值”；不能只断言文字改变。
- 按目标仓库要求运行单元测试、构建、Lint；有不同 flavor 时明确执行应用层各 flavor 测试，不假定通用 `testDebugUnitTest` 覆盖全部。
- 有可用设备／模拟器时，验证两种订阅页、关闭返回、套餐选择和取消后改选。支付 SDK 联调只能打开／关闭确认窗口，真实付款需要用户明确授权。
- 真机部署先确认连接设备、包名、入口 Activity、版本及环境；安装失败不能声称已部署。系统拒绝安装时让用户在手机确认，不卸载清数据来绕过。
- 区分本地源码／单元测试、模拟器 UI、设备安装、后台接口、真实 Play 购买、后台履约及统计平台证据。侧载 APK、Billing 连接成功或出现付款窗口都不证明真实支付／权益闭环。
- 不提交签名、local.properties、token、临时抓包、设备数据或截图；源素材记录来源。
- 最终给出改动、通过的测试、尚未验证的后台／Play 条件、遗留风险，以及相关稳定接入说明。没有证据就说明未验证，不能以“页面能点”代替接入成功。

## 17. 可选参考实现位置

目标环境能访问参考仓库时，可核对以下文件的业务边界；不要把包名、域名、密钥、品牌链接、版本号或某台设备状态复制到新 App：

```text
app/.../ui/imagevideo/SubscriptionRoute.kt
app/.../ui/imagevideo/SubscriptionScreen.kt
app/.../ui/imagevideo/SubscriptionViewModel.kt
app/.../ui/imagevideo/SubscriptionCatalogPresentation.kt
app/.../ui/imagevideo/TemplateVipBadge.kt
app/.../ui/imagevideo/TopUpPaymentViewModel.kt
core/.../backend/TemplateVipAccess.kt
core/.../billing/SubscriptionPurchaseController.kt
core/.../billing/SubscriptionRecovery.kt
core/.../billing/PurchaseRequestCoordinator.kt
core/.../billing/PendingPurchaseRegistry.kt
core/.../payment/BusinessPaymentOrderFactory.kt
core/.../payment/PaymentEngine.kt
core/.../payment/PaymentStorage.kt
core/.../wallet/WalletRepository.kt
```

优先参考的回归测试主题：

```text
TemplateContinueAccessTest / TemplateVipContractTest
SubscriptionCancelCleanupTest / SubscriptionCheckoutActionTest
SubscriptionRecoveryAndLedgerTest / SubscriptionOfferSelectionTest
BusinessOrderCreationTest / BusinessPaymentOrderFactoryTest
PaymentOfficialCancellationTest / PaymentRecoveryProductScopeTest
PendingPurchaseRegistryTest / PurchaseRequestCoordinatorTest
TopUpCardTest / TemplateSubscriptionScreenTest
```

请按以上要求完成目标 App 的接入，遇到差异先核实契约，避免重复上述问题。
