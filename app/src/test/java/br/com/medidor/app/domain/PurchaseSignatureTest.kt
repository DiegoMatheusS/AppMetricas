package br.com.medidor.app.domain

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PurchaseSignatureTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val receipt = "{\"productId\":\"pro_completo\",\"purchaseState\":0}"
    private fun signature() = Base64.getEncoder().encodeToString(Signature.getInstance("SHA1withRSA").run {
        initSign(keys.private); update(receipt.toByteArray()); sign()
    })

    @Test fun validSignedReceiptIsAccepted() {
        assertTrue(PurchaseSignature.verify(publicKey, receipt, signature()))
    }
    @Test fun editedReceiptCannotUnlockPro() {
        assertFalse(PurchaseSignature.verify(publicKey, receipt.replace(":0", ":4"), signature()))
    }
    @Test fun wrongOrMissingKeyCannotUnlockPro() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(PurchaseSignature.verify(Base64.getEncoder().encodeToString(other.public.encoded), receipt, signature()))
        assertFalse(PurchaseSignature.configured(""))
        assertFalse(PurchaseSignature.verify(publicKey, receipt, "invalid"))
    }
}
