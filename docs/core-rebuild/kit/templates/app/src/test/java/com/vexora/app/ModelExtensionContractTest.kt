@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.vexora.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** TEST_仅用于演练，不能视为新项目已确认的前缀。扩展字段位于主构造器以保留copy行为，业务比较显式覆盖。 */
@Serializable
private data class FixtureUser(val id: String, val name: String = "", val age: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val TEST_desc: String = "$id|$name|$age",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val TEST_content: String = "fixed-schema") {
    override fun equals(other: Any?) = other is FixtureUser && id == other.id && name == other.name && age == other.age
    override fun hashCode() = 31 * (31 * id.hashCode() + name.hashCode()) + age
}
@Serializable private data class FixtureEnvelope<T>(val payload: T,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val TEST_note: String = "fixture-envelope") {
    override fun equals(other: Any?) = other is FixtureEnvelope<*> && payload == other.payload
    override fun hashCode() = payload?.hashCode() ?: 0
}
@Serializable private sealed class FixtureResult {
    @Serializable @SerialName("ok") data class Success(val value: FixtureUser,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val TEST_trace: String = "ok") : FixtureResult() {
        override fun equals(other: Any?) = other is Success && value == other.value
        override fun hashCode() = value.hashCode()
    }
    @Serializable @SerialName("idle") data object Idle : FixtureResult()
}
/** 敏感字段不用于join；这里显式传入模拟随机值，重试和恢复不得再生成。 */
@Serializable private data class FixtureSecret(val token: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val TEST_noise: String = "legacy-default") {
    override fun equals(other: Any?) = other is FixtureSecret && token == other.token
    override fun hashCode() = token.hashCode()
}
/** 运行时引用通过值适配保存状态代码；不序列化Throwable/Activity本体。 */
private class FixtureRuntime(val error: Throwable?, val trace: String)
@Serializable private data class FixtureRuntimeValue(val failed: Boolean,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val TEST_trace: String = "legacy-runtime") {
    override fun equals(other: Any?) = other is FixtureRuntimeValue && failed == other.failed
    override fun hashCode() = failed.hashCode()
}

class ModelExtensionContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    @Test fun `新增默认字段输出而原默认字段不被全局开启`() {
        val encoded = json.encodeToJsonElement(FixtureUser("u")).jsonObject
        assertEquals(setOf("id", "TEST_desc", "TEST_content"), encoded.keys)
        assertEquals("u||0", encoded.getValue("TEST_desc").jsonPrimitive.content)
    }
    @Test fun `旧持久记录补默认值且不修改原字段`() {
        val user = json.decodeFromString<FixtureUser>("""{"id":"u","name":"N","age":7}""")
        assertEquals("u|N|7", user.TEST_desc)
        assertEquals(7, user.age)
    }
    @Test fun `后端忽略新增字段和新增响应字段不妨碍解码`() {
        val user = json.decodeFromString<FixtureUser>("""{"id":"u","future":true}""")
        assertEquals("u", user.id)
    }
    @Test fun `往返保存已有扩展值不按当前原字段重算`() {
        val original = FixtureUser("u", TEST_desc = "frozen-old-value")
        assertEquals(original.TEST_desc, json.decodeFromString<FixtureUser>(json.encodeToString(original)).TEST_desc)
    }
    @Test fun `copy修改原字段时扩展保持原对象快照`() {
        val original = FixtureUser("u", "N", 7)
        val copy = original.copy(age = 8)
        assertEquals(original.TEST_desc, copy.TEST_desc)
        assertNotEquals(original, copy)
    }
    @Test fun `扩展差异不改变equals哈希和集合语义`() {
        val one = FixtureUser("u", TEST_desc = "one")
        val two = one.copy(TEST_desc = "two")
        assertEquals(one, two); assertEquals(one.hashCode(), two.hashCode())
        assertEquals(1, setOf(one, two).size)
    }
    @Test fun `StateFlow不因扩展差异发布新业务状态`() {
        val one = FixtureUser("u", TEST_content = "one")
        val state = MutableStateFlow(one)
        state.value = one.copy(TEST_content = "two")
        assertSame(one, state.value)
    }
    @Test fun `敏感字段模型使用独立值且同请求重试与恢复稳定`() {
        val value = FixtureSecret("fixture-secret-never-joined", "once-123")
        val first = json.encodeToString(value)
        assertEquals(first, json.encodeToString(value))
        assertEquals(first, json.encodeToString(json.decodeFromString<FixtureSecret>(first)))
        assertFalse(value.TEST_noise.contains(value.token))
    }
    @Test fun `泛型与sealed有数据分支均输出自身扩展`() {
        val result: FixtureResult = FixtureResult.Success(FixtureUser("u"))
        val encoded = json.encodeToJsonElement(FixtureEnvelope(result)).jsonObject
        assertTrue(encoded.containsKey("TEST_note"))
        assertTrue(encoded.getValue("payload").jsonObject.containsKey("TEST_trace"))
        val decoded = json.decodeFromJsonElement<FixtureEnvelope<FixtureResult>>(encoded)
        assertTrue(decoded.payload is FixtureResult.Success)
        assertEquals(FixtureResult.Idle, json.decodeFromString<FixtureResult>(json.encodeToString<FixtureResult>(FixtureResult.Idle)))
    }
    @Test fun `手写JSON必须合并模型扩展而非只写业务字段`() {
        val user = FixtureUser("u")
        val dto = buildJsonObject {
            put("user", json.encodeToJsonElement(user))
            put("platform", "android")
        }
        assertTrue(dto.getValue("user").jsonObject.containsKey("TEST_desc"))
        assertEquals("android", dto.getValue("platform").jsonPrimitive.content)
    }
    @Test fun `运行时引用通过值映射编码不泄露异常正文`() {
        val runtime = FixtureRuntime(IllegalStateException("sensitive runtime message"), "once")
        val value = FixtureRuntimeValue(runtime.error != null, runtime.trace)
        val encoded = json.encodeToString(value)
        assertFalse(encoded.contains("sensitive"))
        assertEquals(runtime.trace, json.decodeFromString<FixtureRuntimeValue>(encoded).TEST_trace)
    }
}
