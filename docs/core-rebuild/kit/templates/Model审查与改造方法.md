# Model审查与改造方法

基线台账位于manifests/models.json，共100个自有值模型：含原先未单独统计的宿主ProfilePreview、private PendingEvent/QueuedPaymentEvent、非data配置类和有数据sealed分支。清单保留源码位置；脚本只作声明索引，不能替代Kotlin类型/serializer审查。目标项目新增参数Model与模板自有状态值也须继续登记。

## 已审查的序列化路径

| 类/族 | 当前实际路径 | 重构时的必查项 |
| --- | --- | --- |
| ApiResponse、LoginRequest、AnonymousRequest、GoogleRequest、RefreshRequest、AuthResponse、UserProfile、LoginAttribution | Retrofit Kotlin serializer；登录体嵌套归因 | 字段级EncodeDefault或局部serializer；不能全局开启旧默认值；token/密码不做join |
| Session | PreferenceSessionStorage使用独立Json默认实例，非DI Json | 同时修改该编码路径；恢复epoch/revision不受扩展影响，加入兼容默认值 |
| ConversionResult.Success、AttributionStartStatus | SDK→内存状态；归因缓存与补报直接JsonObject | 自有wrapper新增值适配；原SDK extra_data类型原样保留，deviceAttributionReport手写JSON逐字段审查 |
| ReportEventRequest、PendingEvent、BusinessEvent | 前两者为backend DTO和内存队列，app事件经Hub转Map四端发送 | 自有wrapper可序列化；不把新增字段自动撒入SDK参数；backend自有DTO按契约输出；不能串用户或超队列限制 |
| PaymentClientEvent、QueuedPaymentEvent、PaymentRecord、LegacyPaymentOrder | PaymentEventQueue、PaymentStorage、LegacyPaymentRepository局部Json/SharedPreferences | 私有嵌套模型不能漏；持久后重试相同值；敏感链接/凭据不拼接；支付遥测独立于业务队列 |
| InitializePaymentRequest、PaymentEnvelope、PaymentSdkParams、InitializedPayment、PaymentOrderStatus、PaymentEventReceipt | SERVICE Retrofit JSON、metadata JsonObject | 保留业务协议；第三方sdk_params不无差别扩展；DTO wrapper与SDK原生类型分别登记 |
| PendingPurchaseRecord、BillingPurchase、BillingResult、BillingProduct及其offer/phase、CoinPurchaseState、有数据购买/消费结果 | BillingManager的SDK映射、pending持久化、内存StateFlow | BillingProduct原生ProductDetails引用不直接序列化；用可序列化商品快照并重新查询Play恢复句柄；purchaseToken/originalJson不作为join源 |
| SourcePhoto、GenerationRequest、Template族、VisualTask/Resource/Result/Page、UploadedPhoto | PrivateGenerationStorage局部Json、Retrofit响应；创建任务multipart | 本地记录序列化全部扩展；原multipart参数不扩大；photo相等性、幂等键、copy、终态/退款合并不能改变 |
| CoinProduct/ProductsResponse/CurrencyResponse/CoinTransaction/TransactionsResponse/CreateOrderRequest/CreatedOrder | 钱包JSON；WalletSnapshot内存状态；RechargeNotice由手写WS JSON解析 | 业务金额保持精度，WS认证/通知协议不添加任意字段；包装值模型另行编码；扩展不改变通知去重 |
| 全部Configuration/Config/Rules/Policy/ClientIdentity | Gradle/DI或本地配置→值模型 |补局部serializer；密钥、地址或密码不用于join；保持init校验；输出配置快照不能被当作日志许可 |
| app各UiState、ProfileMode、PaymentViewState、LocalRegion等 | StateFlow/普通值；含Throwable/Compose Color的展示包装 | 业务比较显式保留；Throwable仅映射有限错误代码；Color映射数值；不恢复Activity等运行时引用 |
| QualityOption/GenerationOptions/ProfilePreview、CatalogFixture等 | assets或测试fixture JSON | 旧资产仍能读，所有扩展有默认值；fixture不作为真实接口回退 |

## 推荐实施方法

先确认项目字段前缀，再为每个实际目标Model生成并保存结构计划：完整类名、原字段、1–5个新增字段名和SerialName、join安全源与顺序/分隔符/null格式/上限、默认值或一次随机策略、编码出口、equals/hashCode、copy与恢复方案。数量/名字/公式只生成一次并提交版本控制；不要在Gradle或serializer运行时抽取新结构。

字段放入data class主构造器可使copy默认保留已有扩展值，但必须显式实现业务equals/hashCode忽略这些字段。放入类体时自动copy会丢失或重算扩展值，必须另给清晰复制方法。普通引用相等类不因扩展改为结构相等；同时核对componentN和toString调用点。字段值默认沿用对象创建快照，原字段copy后是否重算由该模型契约登记；在途请求始终保持原值。只有安全原字段才能join，没有安全原字段时由显式创建入口生成与凭据无关的值；旧记录使用确定默认值，不在缺字段解码时重抽随机值。仓库重建DTO、鉴权重发、落盘恢复仍携带同一快照。

单独的局部serializer/值DTO允许运行时对象序列化为必要快照，但无法把Activity、Throwable或ProductDetails从JSON还原为原实例。恢复时由宿主或SDK重建运行时引用，并将扩展值带回；保持原模型身份比较语义。不能以“适配困难”为由漏掉模型。

## 可运行演练

[ModelExtensionContractTest](app/src/test/java/com/vexora/app/ModelExtensionContractTest.kt)使用TEST_夹具，演示字段级默认编码、旧数据、往返、copy保留、equals/hash/StateFlow、泛型sealed、敏感源隔离、手写JSON和值适配。TEST_并非目标前缀；样例不能替代100个目标Model的逐项改造和回归。

目标台账校验入口为validation/validate_model_plan.py。其输入是开发者确认后生成的计划，拒绝空前缀、1–5范围外、原字段/序列化名冲突、非原字段join源及未填写策略记录。静态计划通过仍需运行Kotlin测试及实际编码比对，工具不宣称能自动证明所有运行期语义。

## 台账格式与拆分/合并

新项目采用schema=2。`models`每个实际目标类型只登记一次；`baseline_mapping`逐项覆盖manifests/models.json中category=model的source_id，允许多个源指向一个目标，也允许一个源指向多个目标。每项说明职责和原字段的完整去向，不能用空目标删除能力。新增类型（包括参数Model、运行时值快照和模板自有状态）使用 `new:完整类名`。原类型一对一迁移可保留原source_id；新增目标行也可通过baseline_mapping关联来源。旧schema=1仍能验证原一对一台账。

`original_fields`是该目标在添加冗余字段前的真实业务字段及序列化名；合并/拆分后按实际目标填写。字段重命名、嵌套或迁移的关系写入field_mapping。schema=2不会从旧构造参数机械推断新字段，但必须有源码和语义测试证据；静态校验只检查映射完整性。

以下是使用TEST_夹具的最小完整格式，用于说明字段，不可原样充当项目台账；真实prefix取确认值，source_id取基线原件，所有路径相对目标工程根。必须覆盖整个目标，而不是只填这一项：

```json
{
  "schema": 2,
  "prefix": "TEST_",
  "developer_confirmed": true,
  "baseline_mapping": [
    {
      "source_id": "fixture:User",
      "target_types": ["example.User"],
      "reason": "夹具：保留用户值模型",
      "field_mapping": "id、name原字段保留；真实项目逐原字段记录目标位置"
    }
  ],
  "models": [
    {
      "source_id": "new:example.User",
      "target_type": "example.User",
      "target_file": "core/src/main/java/example/User.kt",
      "extension_files": ["core/src/main/java/example/User.kt"],
      "original_fields": [{"name": "id"}, {"name": "name", "serial_name": "name"}],
      "extras": [
        {
          "name": "TEST_desc",
          "serial_name": "TEST_desc",
          "strategy": "join",
          "sources": ["id", "name"],
          "separator": "|",
          "null_format": "",
          "max_length": 64,
          "legacy_default": ""
        }
      ],
      "serialization": "字段级默认编码；记录实际serializer与出入口测试位置",
      "comparison": "保留id与name比较，忽略扩展；记录equals/hash/StateFlow测试",
      "copy": "原字段copy改变后扩展保留原快照；记录实际调用测试",
      "recovery": "旧数据缺字段采用空串；已有扩展解码后保留",
      "retry": "同请求重建DTO和恢复后仍透传同一扩展快照",
      "sensitive_sources": "逐字段审查后仅允许id/name；不拼接凭据或URL",
      "structure_frozen": true
    }
  ]
}
```

strategy接受join、random、default；random/default不填虚假的join源，在serialization/retry/recovery中记录明确的创建、编码及恢复策略。legacy_default、类型、最大长度必须与实际serializer一致，不能只为通过静态检查填写空描述。实现路径须指向目标生产源码（core/integration/确认宿主的src/main），不得指向reference、测试夹具或注释文件。新拆模块需先适配脚手架生产路径、部署范围与测试任务。

自有PaymentSdkParams等包装值仍扩展，传给SDK时只投影协议允许字段；原SDK类不改。raw extra_data、metadata及业务事件parameters字典原样遵循契约，不能把模型冗余字段散入它们。手写JSON要登记自有外层Model的编码出口；GET/multipart/WS不追加未经约定的参数。完整敏感对象编码只在指定本地流程使用，不输出到日志或业务埋点。

验收文档`refactor-review.md`至少包含：原职责/API→新职责/API→调用点→验证证据、目标模型全量清单、每模型实际编码/旧数据/copy/重试测试及传输例外、配置与宿主生命周期、已有工程保留项和未验证条件。测试统计不能仅引用模板11项样例；不要通过删除行为测试或手写passed报告绕过验证。
