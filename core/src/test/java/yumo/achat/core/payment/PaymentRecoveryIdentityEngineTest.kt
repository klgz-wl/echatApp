package yumo.achat.core.payment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.core.auth.Session

class PaymentRecoveryIdentityEngineTest {
    private fun remote()=RecoveredPaymentOrder("remote-order","coins","pending","account-hash","profile-hash",7.25,"USD","google_play","server-sku","pending")
    @Test fun authenticatedOpaqueRecoverySurvivesRestartWithoutCreatingOrLaunchingPayment()=runBlocking {
        val f=PaymentEngineTest.Fixture();f.login();f.api.recovered=remote()
        var engine=f.engine();engine.buy("different","main")
        val record=engine.state.value.record!!
        assertEquals("user",record.userId);assertEquals("account-hash",record.obfuscatedAccountId)
        assertEquals("profile-hash",record.obfuscatedProfileId);assertEquals("coins",record.productId)
        assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty());assertTrue(record.recoveryRequired)
        engine=f.engine();engine.restore();assertEquals(record.key,engine.state.value.record!!.key)
        assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty())
    }
    @Test fun unboundOrForeignOwnerEvidenceCannotCreateOrPersistAnOrder()=runBlocking {
        for(boundTo in listOf<String?>(null,"other")) {
            val f=PaymentEngineTest.Fixture();f.login()
            val repository=object:PaymentRepository by f.api {
                override suspend fun recover(productId:String,session:Session)=
                    if(boundTo==null)remote() else remote().bindAuthenticatedOwner(boundTo)
            }
            val engine=f.engine(repository);engine.buy("coins","main")
            assertTrue(engine.state.value.recoveryLookupFailed);assertTrue(f.store.records.isEmpty())
            assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty())
        }
    }
    @Test fun conflictingLocalOwnerProductIdentifiersAndSkuBlockAdoptionWithoutRewritingHistory()=runBlocking {
        val matching=PaymentRecord("known-key","user","coins","store",orderId="remote-order",stage=PaymentStage.FAILED,
            initialized=InitializedPayment("remote-order","official","google_play","sdk",PaymentSdkParams("server-sku")),
            obfuscatedAccountId="account-hash",obfuscatedProfileId="profile-hash",productType="diamond")
        val conflicts=listOf(matching.copy(userId="other"),matching.copy(productId="different"),
            matching.copy(obfuscatedAccountId="wrong-account"),matching.copy(obfuscatedProfileId="wrong-profile"),
            matching.copy(initialized=matching.initialized!!.copy(sdkParams=PaymentSdkParams("different-sku"))),
            matching.copy(stage=PaymentStage.SUCCESS,successAcknowledged=true))
        for(local in conflicts) {
            val f=PaymentEngineTest.Fixture();f.login();f.api.recovered=remote();f.store.records=listOf(local)
            val engine=f.engine();engine.buy("coins","main")
            assertTrue(engine.state.value.recoveryLookupFailed);assertEquals(listOf(local),f.store.records)
            assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty())
        }
    }
    @Test fun matchingRecoveryKeepsFrozenPlayQuoteAndOriginalProvenanceThroughFulfillment()=runBlocking {
        val f=PaymentEngineTest.Fixture();f.login();f.api.recovered=remote().copy(status="paid")
        val saved=PaymentRecord("original-request","user","coins","generation",orderId="remote-order",
            stage=PaymentStage.OFFICIAL_CONSUMED,productType="diamond",
            initialized=InitializedPayment("remote-order","official","google_play","sdk",PaymentSdkParams("server-sku")),
            obfuscatedAccountId="account-hash",obfuscatedProfileId="profile-hash",
            quotePrice=3.49,quoteCurrency="EUR",quoteSource="google_play",
            orderCreationSource="business",orderCreationTrigger="template-vip",orderCreateStarted=true)
        f.store.records=listOf(saved)
        val engine=f.engine();engine.buy("coins","main")
        val restored=engine.state.value.record!!
        assertEquals(saved.key,restored.key);assertEquals(saved.quotePrice,restored.quotePrice)
        assertEquals(saved.quoteCurrency,restored.quoteCurrency);assertEquals(saved.quoteSource,restored.quoteSource)
        assertEquals(saved.source,restored.source);assertEquals(saved.orderCreationSource,restored.orderCreationSource)
        assertEquals(saved.orderCreationTrigger,restored.orderCreationTrigger);assertTrue(restored.orderCreateStarted)
        f.api.status="paid";f.api.fulfillment="fulfilled";engine.poll(saved.key);engine.poll(saved.key)
        val event=f.tracker.events.single {it.name=="purchase_success"}
        assertEquals(3.49,event.values["af_revenue"]);assertEquals("EUR",event.values["af_currency"])
    }
    @Test fun knownMatchingOrderKeepsItsOriginalRequestKeyInsteadOfDuplicatingHistory()=runBlocking {
        val f=PaymentEngineTest.Fixture();f.login();f.api.recovered=remote().copy(status="paid")
        f.store.records=listOf(PaymentRecord("known-key","user","coins","store",orderId="remote-order",
            stage=PaymentStage.FAILED,obfuscatedAccountId="account-hash",obfuscatedProfileId="profile-hash",productType="diamond"))
        val engine=f.engine();engine.buy("coins","main")
        assertEquals(1,f.store.records.size);assertEquals("known-key",engine.state.value.record!!.key)
        assertEquals(PaymentStage.AWAITING_FULFILLMENT,engine.state.value.record!!.stage)
        assertEquals(0,f.orders);assertTrue(f.api.initialized.isEmpty())
    }
}
