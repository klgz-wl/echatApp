# App 第三方支付对接文档

本文档用于 App 端对接本次第三方支付改造。当前 P0 范围为美国地区支付，由 `echat-payment-service` 负责根据后台支付列表配置选择官方支付或第三方支付，并返回给 App 执行后续动作。

## 1. 基础信息

### Base URL

```text
Test: https://test.appjoly.com/payment-api/v1
```

### 鉴权

所有 App 端接口都需要 App JWT：

```http
Authorization: Bearer <App JWT>
Content-Type: application/json
```

JWT 关键字段：

```json
{
  "iss": "social",
  "sub": "user_id",
  "info": {
    "app_id": "app_id",
    "platform": "android"
  }
}
```

说明：

- `sub` 必须是当前登录用户 ID。
- `info.app_id` 必须是当前 App ID。
- `info.platform` 当前 Android 传 `android`。
- payment-service 会校验 JWT 用户、App、平台与订单归属是否一致。

## 2. 整体支付流程

```text
1. App 在付费墙创建业务订单，拿到 order_id。
2. App 调用 payment-service 初始化支付接口。
3. payment-service 根据后台支付列表配置选择支付渠道。
4. 如果返回官方支付：
   - App 调用 Google Play SDK。
5. 如果返回第三方支付：
   - App 根据 open_mode 打开 payment_url。
   - 打开后每 10 秒查询一次支付状态，最多查询 10 分钟。
   - 支付成功后展示成功提示。
   - 用户关闭页面、页面异常、超时等情况需要上报事件。
```

当前服务端固定按美国 `US` 处理，App 不需要传 `country`。

## 3. 初始化支付 / 获取支付渠道

App 创建订单后调用该接口，由 payment-service 判断本单走官方支付还是第三方支付。

```http
POST /client/payments/initialize
```

### 请求参数

```json
{
  "order_id": "48ecf975-16a4-4b7c-aac6-7c29189115c7"
}
```

字段说明：

| 字段 | 类型 | 必须 | 说明 |
| --- | --- | --- | --- |
| `order_id` | string | 是 | App 创建业务订单后拿到的订单 ID |

不要传以下字段：

- `country`
- `amount`
- `product_id`
- `payment_method`
- `merchantNo`
- `callbackURL`
- `returnURL`
- `failURL`
- `sign`

这些字段由服务端根据订单和后台配置生成。

### IP 说明

第三方支付美国渠道要求真实终端用户公网 IP。当前 payment-service 会从 HTTP 请求链路中解析：

- `X-Forwarded-For`
- `X-Real-IP`
- `RemoteAddr`

如果 App 能拿到终端真实公网 IP，且网关允许透传，可以在请求 header 中带上：

```http
X-Forwarded-For: <真实终端公网IP>
```

注意：

- 不能传内网 IP、回环 IP、空 IP。
- 不能随意伪造统一 IP，否则可能触发三方风控。
- 当前初始化接口 body 不接收 `ip` 字段。

### 返回：官方支付

```json
{
  "data": {
    "order_id": "48ecf975-16a4-4b7c-aac6-7c29189115c7",
    "channel_type": "official",
    "channel_code": "google_play",
    "open_mode": "sdk",
    "sdk_params": {
      "product_id": "yumo_pack_100"
    }
  }
}
```

App 处理：

- `channel_type=official`
- `open_mode=sdk`
- 调用 Google Play SDK
- Google Play 商品 ID 使用 `sdk_params.product_id`

### 返回：第三方支付

```json
{
  "data": {
    "order_id": "48ecf975-16a4-4b7c-aac6-7c29189115c7",
    "channel_type": "third_party",
    "channel_code": "payu_web_us",
    "open_mode": "webview",
    "payment_url": "https://example.com/checkout",
    "expires_at": "2026-09-12T08:00:00Z",
    "query_interval_seconds": 10,
    "max_query_seconds": 600
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `order_id` | string | 业务订单 ID |
| `channel_type` | string | `official` 官方支付；`third_party` 第三方支付 |
| `channel_code` | string | 支付渠道编码，例如 `google_play`、`payu_web_us` |
| `open_mode` | string | `sdk`、`webview`、`external_browser` |
| `payment_url` | string | 第三方收银台链接，仅第三方支付返回 |
| `expires_at` | string | 收银台过期时间，仅第三方支付返回 |
| `query_interval_seconds` | number | App 查询支付状态间隔，当前为 10 秒 |
| `max_query_seconds` | number | App 最长查询时间，当前为 600 秒 |
| `sdk_params.product_id` | string | 官方支付 SDK 商品 ID |

App 处理：

- `open_mode=webview`：用 App 内 WebView 打开 `payment_url`，需要开启 JavaScript。
- `open_mode=external_browser`：用系统浏览器打开 `payment_url`。
- `payment_url` 为空或接口报错时，不要打开页面，按失败/兜底逻辑处理并上报事件。

## 4. 查询支付状态

第三方支付页面打开后，App 每 10 秒查询一次，最多查询 10 分钟。

```http
GET /client/payments/{order_id}/status
```

示例：

```http
GET /client/payments/48ecf975-16a4-4b7c-aac6-7c29189115c7/status
```

### 返回示例

```json
{
  "data": {
    "order_id": "48ecf975-16a4-4b7c-aac6-7c29189115c7",
    "status": "pending",
    "payment_method": "payu_web_us",
    "fulfillment_status": "pending",
    "paid_at": null,
    "verified_at": null,
    "fulfilled_at": null,
    "third_party_payment": {
      "channel_code": "payu_web_us",
      "status": "pending",
      "open_mode": "webview",
      "expires_at": "2026-09-12T08:00:00Z"
    }
  }
}
```

### App 判断规则

| 条件 | App 行为 |
| --- | --- |
| `status=paid` 且 `fulfillment_status=fulfilled` | 支付成功，停止轮询，关闭支付页或回到业务页，展示成功提示 |
| `status=pending` | 继续轮询 |
| `status=failed` / `cancelled` / `expired` | 支付失败或取消，停止轮询，停留在付费墙 |
| 轮询超过 `max_query_seconds` | 停止轮询，按超时取消处理 |

## 5. 上报第三方支付页面事件

用于记录三方支付链路中的页面打开、加载、异常、用户关闭、超时、支付成功等 App 侧行为。

```http
POST /client/payments/{order_id}/client-events
```

### 请求参数

```json
{
  "event_type": "page_loaded",
  "channel_code": "payu_web_us",
  "open_mode": "webview",
  "page": "paywall",
  "url": "https://example.com/checkout",
  "occurred_at": "2026-09-12T08:00:00Z",
  "opened_at": "2026-09-12T07:59:30Z",
  "closed_at": "2026-09-12T08:00:10Z",
  "duration_ms": 40000,
  "error_code": "",
  "error_message": "",
  "html_excerpt": "<html>...</html>",
  "metadata": {
    "source": "vip_paywall",
    "product_id": "product_id"
  }
}
```

字段说明：

| 字段 | 类型 | 必须 | 说明 |
| --- | --- | --- | --- |
| `event_type` | string | 是 | 事件类型，见下方事件列表 |
| `channel_code` | string | 条件必填 | 支付渠道编码。若订单已有三方记录，服务端可自动补齐；建议 App 显式传 |
| `open_mode` | string | 条件必填 | `webview` 或 `external_browser`。若订单已有三方记录，服务端可自动补齐；建议 App 显式传 |
| `page` | string | 否 | 来源页面，例如 `paywall`、`vip_paywall`、`recharge_paywall` |
| `url` | string | 否 | 页面 URL。服务端会去掉 query 和 fragment 后保存 |
| `occurred_at` | string | 否 | 事件发生时间，ISO8601；不传则服务端使用当前时间 |
| `opened_at` | string | 否 | 支付页面打开时间 |
| `closed_at` | string | 否 | 支付页面关闭时间 |
| `duration_ms` | number | 否 | 页面停留时长，毫秒 |
| `error_code` | string | 否 | 页面加载或支付异常码 |
| `error_message` | string | 否 | 页面加载或支付异常信息 |
| `html_excerpt` | string | 否 | 如果 WebView 能读取页面 HTML，可上传截断后的内容 |
| `metadata` | object | 否 | App 自定义扩展信息 |

### 返回

```json
{
  "data": {
    "recorded": true
  }
}
```

### 支持的事件类型

| event_type | 触发时机 | 对应需求埋点 |
| --- | --- | --- |
| `link_ok` | 初始化接口返回正常第三方 `payment_url` | `3rdpayment_link_ok` |
| `link_error` | 初始化接口报错、`payment_url` 为空、链接不可用 | `3rdpayment_link_error` |
| `page_opened` | App 开始打开 WebView 或外部浏览器 | - |
| `page_loaded` | WebView 页面加载完成 | `3rdpayment_page_loaded` |
| `page_load_error` | WebView/浏览器打开失败或加载异常 | - |
| `paid_while_open` | 页面打开期间轮询到支付成功 | - |
| `paid_on_close` | 用户关闭页面时查询到支付成功 | - |
| `user_cancel` | 用户关闭页面且没有支付成功 | - |
| `timeout_cancel` | 超时后关闭支付页面 | - |
| `poll_timeout` | 轮询满 10 分钟仍未支付成功 | - |

## 6. App 埋点建议

App 自己的数数埋点和上报 payment-service 事件建议保持一一对应。

### 6.1 拿到第三方支付链接

条件：

- 初始化接口成功；
- `channel_type=third_party`；
- `payment_url` 非空。

App 动作：

- 数数埋点：`3rdpayment_link_ok`
- 上报 payment-service：

```json
{
  "event_type": "link_ok",
  "channel_code": "payu_web_us",
  "open_mode": "webview",
  "page": "paywall",
  "url": "https://example.com/checkout"
}
```

### 6.2 第三方链接异常

条件：

- 初始化接口失败；
- 或 `channel_type=third_party` 但 `payment_url` 为空；
- 或 App 判断链接不可打开。

App 动作：

- 数数埋点：`3rdpayment_link_error`
- 上报 payment-service：

```json
{
  "event_type": "link_error",
  "channel_code": "payu_web_us",
  "open_mode": "webview",
  "page": "paywall",
  "error_code": "empty_payment_url",
  "error_message": "payment_url is empty"
}
```

### 6.3 页面加载完成

条件：

- WebView `onPageFinished` / 等价回调触发。

App 动作：

- 数数埋点：`3rdpayment_page_loaded`
- 上报 payment-service：

```json
{
  "event_type": "page_loaded",
  "channel_code": "payu_web_us",
  "open_mode": "webview",
  "page": "paywall",
  "url": "https://example.com/checkout",
  "html_excerpt": "<html>如果能读取到页面内容则传</html>"
}
```

### 6.4 用户关闭页面

用户关闭内置 WebView 时，App 应立即查询一次支付状态：

- 如果成功：上报 `paid_on_close`。
- 如果未成功：上报 `user_cancel`。

示例：

```json
{
  "event_type": "user_cancel",
  "channel_code": "payu_web_us",
  "open_mode": "webview",
  "page": "paywall",
  "opened_at": "2026-09-12T07:59:30Z",
  "closed_at": "2026-09-12T08:00:10Z",
  "duration_ms": 40000
}
```

## 7. 成功提示

支付成功后，App 需要展示 toast 或撒花动效，倒计时 3 秒关闭。

### 金币/钻石/虚拟币充值成功

```text
Congratulations, You had paid successfully, xxx gem/coin added to your balance. You can continue now.
```

`gem` 或 `coin` 根据商品类型显示。

### VIP 会员购买成功

```text
Congratulations, You had paid successfully, xxx days VIP privilege had enabled. You can continue now.
```

注意：文案中的 `privilege` 建议使用正确拼写。

## 8. 错误码

### 初始化接口错误

| HTTP 状态 | error | 说明 | App 建议处理 |
| --- | --- | --- | --- |
| 400 | `order_id is required` | 缺少订单 ID | 重新检查订单创建流程 |
| 400 | `invalid request` | 请求 JSON 异常 | 修复请求格式 |
| 401 | `missing authorization token` | 缺少 JWT | 重新登录或刷新 token |
| 401 | `invalid authorization token` | JWT 无效或过期 | 重新登录或刷新 token |
| 403 | `payment order forbidden` | 订单不属于当前用户/App/平台 | 不允许继续支付 |
| 404 | `payment order not found` | 订单不存在 | 重新创建订单 |
| 409 | `PAYMENT_INITIALIZATION_IN_PROGRESS` | 三方交易正在创建或结果不确定 | 不要重复下单，可稍后重试初始化或查询状态 |
| 409 | `PAYMENT_ORDER_NOT_INITIALIZABLE` | 订单不可初始化 | 重新创建订单 |
| 503 | `PAYMENT_CHANNEL_UNAVAILABLE` | 当前没有可用支付渠道 | 提示稍后再试或使用官方兜底 |
| 500 | `payment initialization failed` | 服务端异常 | 提示稍后再试 |

### 事件上报接口错误

| HTTP 状态 | error | 说明 |
| --- | --- | --- |
| 400 | `invalid event_type` | 事件类型不在允许列表 |
| 400 | `channel_code and open_mode are required` | 订单未绑定三方记录且请求未传渠道/打开方式 |
| 400 | `metadata must be valid JSON` | metadata 不是合法 JSON |
| 413 | `request too large` | 请求体过大 |
| 401/403/404 | 同初始化接口 | 鉴权或订单归属异常 |
| 500 | `record client payment event failed` | 服务端保存失败 |

## 9. 推荐 App 伪代码

```kotlin
val init = paymentService.initializePayment(orderId)

if (init.channelType == "official") {
    googlePlay.launchBilling(init.sdkParams.productId)
    return
}

if (init.channelType == "third_party") {
    if (init.paymentUrl.isNullOrBlank()) {
        track("3rdpayment_link_error")
        paymentService.recordClientEvent(orderId, "link_error")
        showPayCancelled()
        return
    }

    track("3rdpayment_link_ok")
    paymentService.recordClientEvent(orderId, "link_ok")

    openPaymentPage(init.paymentUrl, init.openMode)
    paymentService.recordClientEvent(orderId, "page_opened")

    startPolling(intervalSeconds = 10, maxSeconds = 600) {
        val status = paymentService.getPaymentStatus(orderId)
        if (status.status == "paid" && status.fulfillmentStatus == "fulfilled") {
            paymentService.recordClientEvent(orderId, "paid_while_open")
            showSuccessCelebration()
            stopPolling()
        }
    }
}
```

## 10. 当前注意事项

- App 初始化支付只传 `order_id`。
- 当前服务端固定美国 `US`，App 不传 `country`。
- 第三方支付页面打开后，App 轮询状态即可，不需要关心收银台页面内部超时逻辑。
- 外部浏览器无法可靠感知关闭时，可以依靠轮询超时和 App 回到前台时主动查询状态。
- 如果 WebView 能读取到 HTML 内容，可通过 `html_excerpt` 上报；建议 App 自行截断，避免上传过大内容。
- 不要把支付链接里的 query 参数、token、用户敏感信息打到 App 日志中。
