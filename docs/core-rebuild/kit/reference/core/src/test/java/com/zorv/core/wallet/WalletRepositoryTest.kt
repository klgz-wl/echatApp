package com.zorv.core.wallet

import com.zorv.core.auth.MemoryStorage
import com.zorv.core.auth.SessionCoordinator
import com.zorv.core.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WalletRepositoryTest {
    private class Api : WalletApi {
        override suspend fun createOrder(body: CreateOrderRequest, session: com.zorv.core.auth.Session) =
            ApiResponse(0, data = CreatedOrder("order", body.productId, "pending"))
        var balance: suspend () -> ApiResponse<CurrencyResponse> = { ApiResponse(0, data = CurrencyResponse("user", 42)) }
        var products = listOf(CoinProduct("coins", "diamond", "Coins", 1.99, "USD", 100, googleProductId = "play.coins"))
        var productQuery: List<String>? = null
        var failRecords = false
        val pages = mutableListOf<Int>()
        override suspend fun currencies() = balance()
        override suspend fun products(type: String, platform: String, location: String): ApiResponse<ProductsResponse> {
            productQuery = listOf(type, platform, location)
            return ApiResponse(0, data = ProductsResponse(products))
        }
        override suspend fun transactions(page: Int, pageSize: Int): ApiResponse<TransactionsResponse> {
            pages.add(page)
            if (failRecords) return ApiResponse(1)
            val entries = if (page == 1) listOf(transaction("one", -14), transaction("two", 40))
                else listOf(transaction("two", 40), transaction("three", -5))
            return ApiResponse(0, data = TransactionsResponse(entries, 4, page, pageSize))
        }
        private fun transaction(id: String, amount: Long) = CoinTransaction(id, "test", amount, createdAt = "2026-09-10T00:00:00Z")
    }
    private suspend fun fixture(): Triple<WalletRepository, Api, SessionCoordinator> {
        val api = Api()
        val sessions = SessionCoordinator(MemoryStorage())
        sessions.saveLogin(AuthResponse("token", "refresh", "user"))
        return Triple(WalletRepository(api, sessions, WalletConfiguration("diamond", "store", 2)), api, sessions)
    }
    @Test fun `金币余额和商品保持原始数值且只查询消耗型商品`() = runBlocking {
        val (repository, api) = fixture()
        assertEquals(42L, repository.refreshBalance())
        api.balance = { ApiResponse(0, data = CurrencyResponse("user", -7)) }
        assertEquals(-7L, repository.refreshBalance())
        repository.refreshProducts()
        assertEquals(listOf("diamond", "android", "store"), api.productQuery)
        assertEquals(100L, repository.state.value.products.single().coins)
        api.products = api.products.map { it.copy(subscription = true) }
        try { repository.refreshProducts(); fail() } catch (_: ServiceFailure.InvalidResponse) { }
    }
    @Test fun `余额缺失或服务端失败不能伪造零余额`() = runBlocking {
        val (repository, api) = fixture()
        api.balance = { ApiResponse(1) }
        try { repository.refreshBalance(); fail() } catch (_: ServiceFailure.Business) { }
        assertNull(repository.state.value.balance)
        api.balance = { ApiResponse(0, data = CurrencyResponse("other-user", 99)) }
        try { repository.refreshBalance(); fail() } catch (_: ServiceFailure.InvalidResponse) { }
        assertNull(repository.state.value.balance)
    }
    @Test fun `旧会话返回不能污染新会话余额`() = runBlocking {
        val (repository, api, sessions) = fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.balance = { started.complete(Unit); release.await(); ApiResponse(0, data = CurrencyResponse("user", 99)) }
        val request = async { try { repository.refreshBalance(); false } catch (_: ServiceFailure.Superseded) { true } }
        started.await()
        sessions.saveLogin(AuthResponse("other-token", "other-refresh", "other-user"))
        release.complete(Unit)
        assertTrue(request.await())
        assertNull(repository.state.value.balance)
    }
    @Test fun `分页失败不跳页且去重保留金额正负号`() = runBlocking {
        val (repository, api) = fixture()
        repository.loadRecords()
        api.failRecords = true
        try { repository.loadRecords(); fail() } catch (_: ServiceFailure.Business) { }
        assertEquals(2, repository.state.value.nextPage)
        api.failRecords = false
        repository.loadRecords()
        assertEquals(listOf(1, 2, 2), api.pages)
        assertEquals(listOf(-14L, 40L, -5L), repository.state.value.transactions.map { it.amount })
        assertFalse(repository.state.value.hasMore)
        repository.loadRecords()
        assertEquals(3, api.pages.size)
        repository.loadRecords(refresh = true)
        assertEquals(2, repository.state.value.transactions.size)
    }
}
