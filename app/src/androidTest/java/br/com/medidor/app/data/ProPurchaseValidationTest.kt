package br.com.medidor.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.Purchase
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProPurchaseValidationTest {
    @Test fun onlySignedPurchasedSingleProductForThisPackageUnlocksPro() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val billing = ProBilling(context, "pro_completo", Base64.getEncoder().encodeToString(keys.public.encoded))
        fun receipt(state: Int = 0, product: String = "pro_completo", pkg: String = context.packageName,
            quantity: Int = 1): Purchase {
            val json = JSONObject().put("productId", product).put("packageName", pkg)
                .put("purchaseState", state).put("quantity", quantity).put("purchaseToken", "test-token")
                .put("acknowledged", false).toString()
            val signature = Signature.getInstance("SHA1withRSA").run {
                initSign(keys.private); update(json.toByteArray()); sign()
            }
            return Purchase(json, Base64.getEncoder().encodeToString(signature))
        }
        try {
            assertTrue(billing.valid(receipt()))
            assertFalse(billing.valid(receipt(state = 4)))
            assertFalse(billing.valid(receipt(state = 1)))
            assertFalse(billing.valid(receipt(product = "another_product")))
            assertFalse(billing.valid(receipt(pkg = "another.package")))
            assertFalse(billing.valid(receipt(quantity = 2)))
            val valid = receipt()
            assertFalse(billing.valid(Purchase(valid.originalJson, "invalid")))
        } finally { billing.close() }
    }
}
