# 保留序列化、网络模型按各库 consumer rules 处理。

# 当前不展示退出／注销入口，但启动注入仍创建 AccountApi 动态代理。
# 保留接口和方法，避免未调用方法被裁剪后失去 Retrofit 条件规则的保护。
-keep,allowobfuscation interface com.vexora.core.network.AccountApi {
    *;
}
