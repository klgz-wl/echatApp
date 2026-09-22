# EventApi 由 Retrofit 动态代理实例化，保留其反射入口及泛型／注解。
# 仅保护本接口，不关闭全局混淆；序列化使用 Kotlin 生成的 serializer 和库自带规则。
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep,allowobfuscation interface yumo.achat.core.analytics.EventApi {
    *;
}
