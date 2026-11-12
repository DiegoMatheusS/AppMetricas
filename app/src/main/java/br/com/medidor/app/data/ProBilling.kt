package br.com.medidor.app.data

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.medidor.app.BuildConfig
import br.com.medidor.app.domain.PurchaseSignature
import com.android.billingclient.api.*
import org.json.JSONObject

internal data class ProBillingState(
    val configured: Boolean, val owned: Boolean = false, val busy: Boolean = false,
    val price: String? = null, val canBuy: Boolean = false, val pending: Boolean = false,
    val message: String? = null
)

/** One non-consumable purchase. Never grants access for pending, wrong-product or unsigned receipts. */
internal class ProBilling(private val context: Context,
    private val productId: String = BuildConfig.PLAY_PRODUCT_ID,
    private val publicKey: String = BuildConfig.PLAY_LICENSE_KEY) : PurchasesUpdatedListener {
    private val cache = ProReceiptCache(context)
    var state by mutableStateOf(ProBillingState(PurchaseSignature.configured(publicKey), message =
        "A compra pela Google Play ficará disponível após a publicação. As ferramentas estão liberadas nesta versão de testes."))
        private set
    private var closed = false
    private var connecting = false
    private var querying = false
    private var purchaseInProgress = false
    private val actions = ArrayDeque<() -> Unit>()
    private val acknowledging = mutableSetOf<String>()
    private val client = BillingClient.newBuilder(context).setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection().build()

    init {
        if (state.configured) {
            val cached = cache.read()?.let { (json, signature) -> runCatching { Purchase(json, signature) }.getOrNull() }
            state = state.copy(owned = cached?.let(::valid) == true, message = null)
        }
    }

    internal fun valid(purchase: Purchase): Boolean = runCatching {
        purchase.purchaseState == Purchase.PurchaseState.PURCHASED && purchase.quantity == 1 &&
            purchase.products == listOf(productId) && purchase.purchaseToken.isNotBlank() &&
            JSONObject(purchase.originalJson).optInt("purchaseState", -1) == 0 &&
            JSONObject(purchase.originalJson).optString("packageName") == context.packageName &&
            PurchaseSignature.verify(publicKey, purchase.originalJson, purchase.signature)
    }.getOrDefault(false)

    private fun connect(action: () -> Unit) {
        if (closed || !state.configured) return
        if (client.isReady) { action(); return }
        actions.addLast(action)
        if (connecting) return
        connecting = true
        state = state.copy(busy = true)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (closed) return
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    state = state.copy(busy = false)
                    val pending = actions.toList(); actions.clear(); pending.forEach { it() }
                } else { actions.clear(); fail("Não foi possível conectar à Google Play. Tente novamente.") }
            }
            override fun onBillingServiceDisconnected() {
                connecting = false
                if (!closed) state = state.copy(busy = false, canBuy = false,
                    message = "Conexão com a Google Play interrompida. Tente novamente.")
            }
        })
    }

    fun refresh(showResult: Boolean = false) {
        if (querying || purchaseInProgress) return
        if (state.configured && state.owned && cache.read() == null) state = state.copy(owned = false)
        connect {
            if (querying) return@connect
            querying = true
            state = state.copy(busy = true)
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
                querying = false
                if (closed) return@queryPurchasesAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) process(purchases, showResult)
                else fail("Não foi possível conferir suas compras. Verifique a conexão e tente novamente.")
                loadProduct()
            }
        }
    }

    private fun loadProduct(onReady: ((ProductDetails, ProductDetails.OneTimePurchaseOfferDetails) -> Unit)? = null) {
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP).build())).build()
        client.queryProductDetailsAsync(params) { result, products ->
            if (closed) return@queryProductDetailsAsync
            val product = products.productDetailsList.firstOrNull { it.productId == productId }
            val offer = product?.oneTimePurchaseOfferDetailsList?.filter {
                it.rentalDetails == null && it.preorderDetails == null
            }?.minByOrNull { it.priceAmountMicros }
            if (result.responseCode != BillingClient.BillingResponseCode.OK || product == null || offer == null) {
                fail("O Pro ainda não está disponível nesta instalação da Google Play.")
                return@queryProductDetailsAsync
            }
            state = state.copy(price = offer.formattedPrice, busy = false, canBuy = !state.owned && !state.pending)
            onReady?.invoke(product, offer)
        }
    }

    fun buy(activity: Activity) {
        if (!state.canBuy || state.busy || state.owned || purchaseInProgress) return
        connect {
            state = state.copy(busy = true)
            // Query again: do not launch a purchase with cached ProductDetails or an expired offer token.
            loadProduct { product, offer ->
                val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
                    .setOfferToken(offer.offerToken).build()
                purchaseInProgress = true
                state = state.copy(busy = true, message = null)
                val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(params)).build())
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    purchaseInProgress = false
                    if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh(true)
                    else fail("Não foi possível abrir a compra. Tente novamente pela Google Play.")
                }
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        purchaseInProgress = false
        if (closed) return
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> refresh(true)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh(true)
            BillingClient.BillingResponseCode.USER_CANCELED -> state = state.copy(busy = false, message = "Compra cancelada.")
            else -> fail("Não foi possível concluir a compra. Confira a Google Play antes de tentar novamente.")
        }
    }

    private fun process(purchases: List<Purchase>, showResult: Boolean) {
        val owned = purchases.firstOrNull(::valid)
        val pending = purchases.any { productId in it.products && it.purchaseState == Purchase.PurchaseState.PENDING }
        if (owned == null) cache.clear() else runCatching { cache.save(owned.originalJson, owned.signature) }
        state = state.copy(owned = owned != null, pending = pending, busy = false, canBuy = owned == null && !pending,
            message = when {
                owned != null -> "Pro Completo ativado nesta conta da Google Play."
                pending -> "Pagamento pendente. O Pro será liberado após a confirmação da Google Play."
                showResult -> "Nenhuma compra do Pro encontrada nesta conta da Google Play."
                else -> null
            })
        if (owned != null && !owned.isAcknowledged && acknowledging.add(owned.purchaseToken)) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(owned.purchaseToken).build()) { result ->
                acknowledging.remove(owned.purchaseToken)
                if (!closed && result.responseCode != BillingClient.BillingResponseCode.OK)
                    state = state.copy(message = "Pro liberado. Mantenha a conexão para concluir a confirmação da compra.")
            }
        }
    }

    private fun fail(message: String) {
        if (!closed) state = state.copy(busy = false, canBuy = false, message = message)
    }
    fun close() { closed = true; actions.clear(); client.endConnection() }
}
