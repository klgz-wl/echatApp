package yumo.achat.core.payment

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaymentRecoveryIdentityTest {
    private fun remote()=RecoveredPaymentOrder("order","coins","pending","account-hash","profile-hash",2.0,"USD","google_play","sku","pending")
    @Test fun independentIdsAreAcceptedOnlyAfterAuthenticatedOwnerBinding() {
        val raw=remote()
        assertThrows(PaymentFailure.InvalidResponse::class.java){raw.validate("user")}
        val owned=raw.bindAuthenticatedOwner("user")
        assertSame(owned,owned.validate("user"))
        assertEquals("account-hash",owned.obfuscatedAccountId);assertEquals("profile-hash",owned.obfuscatedProfileId)
        assertThrows(PaymentFailure.InvalidResponse::class.java){owned.validate("other")}
    }
    @Test fun rawLegacyIdsAreNotProofAndAttestationCannotSurviveCopyOrWireRoundTrip() {
        val raw=remote().copy(obfuscatedAccountId="user",obfuscatedProfileId="order")
        assertThrows(PaymentFailure.InvalidResponse::class.java){raw.validate("user")}
        val owned=remote().bindAuthenticatedOwner("user")
        assertThrows(PaymentFailure.InvalidResponse::class.java){owned.copy().validate("user")}
        val encoded=Json.encodeToString(owned)
        assertFalse(encoded.contains("authenticatedOwner"))
        assertThrows(PaymentFailure.InvalidResponse::class.java){Json.decodeFromString<RecoveredPaymentOrder>(encoded).validate("user")}
    }
    @Test fun serverJsonCannotForgeTheTransientOwnerProof() {
        val json=Json {ignoreUnknownKeys=true}
        val encoded=Json.encodeToString(remote()).dropLast(1)+",\"authenticatedOwnerUserId\":\"user\"}"
        val decoded=json.decodeFromString<RecoveredPaymentOrder>(encoded)
        assertThrows(PaymentFailure.InvalidResponse::class.java){decoded.validate("user")}
        decoded.bindAuthenticatedOwner("user").validate("user")
    }
    @Test fun malformedIdentifiersAreStillRejectedAtTheAuthenticatedBoundary() {
        for(id in listOf(""," ","x".repeat(65),"hash\n", " hash")) {
            assertThrows(PaymentFailure.InvalidResponse::class.java){remote().copy(obfuscatedAccountId=id).bindAuthenticatedOwner("user")}
            assertThrows(PaymentFailure.InvalidResponse::class.java){remote().copy(obfuscatedProfileId=id).bindAuthenticatedOwner("user")}
        }
    }
}
