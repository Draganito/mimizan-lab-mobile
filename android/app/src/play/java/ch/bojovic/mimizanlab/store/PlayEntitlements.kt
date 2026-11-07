package ch.bojovic.mimizanlab.store

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one-time product on Play Billing. The entitlement is cached in
 * shared preferences so the first export after a cold start does not wait
 * for the store; Play's own purchase list is the source of truth and is
 * read on every connection. A purchase is granted only once acknowledged,
 * as Play asks; unacknowledged ones are refunded after three days.
 */
class PlayEntitlements(context: Context) : Entitlements, PurchasesUpdatedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("entitlements", Context.MODE_PRIVATE)

    private val _fullResolution = MutableStateFlow(prefs.getBoolean(KEY_FULLRES, false))
    override val fullResolution: StateFlow<Boolean> = _fullResolution.asStateFlow()
    private val _price = MutableStateFlow<String?>(null)
    override val price: StateFlow<String?> = _price.asStateFlow()
    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()
    override val message = MutableStateFlow<String?>(null)
    override val storeBound = true

    private var product: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "billing setup: ${result.debugMessage}")
                    return
                }
                scope.launch { loadProduct(); refresh(quiet = true) }
            }

            // The client reconnects by itself (enableAutoServiceReconnection).
            override fun onBillingServiceDisconnected() {}
        })
    }

    private suspend fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(Entitlements.PRODUCT_ID)
                        .setProductType(ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "product details: ${result.billingResult.debugMessage}")
            return
        }
        val details = result.productDetailsList?.firstOrNull { it.productId == Entitlements.PRODUCT_ID } ?: return
        product = details
        _price.value = details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
    }

    /** Play's list of owned purchases; the message only when the user asked (Restore). */
    private suspend fun refresh(quiet: Boolean) {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            if (!quiet) message.value = "Could not reach Google Play"
            return
        }
        val owned = result.purchasesList.filter { Entitlements.PRODUCT_ID in it.products }
        var granted = false
        for (p in owned) granted = grant(p) || granted
        // Play answered and lists no completed purchase: a refund, or the
        // cache of another account. The cached grant goes with it. A
        // pending or unacknowledged purchase is not a refusal and keeps it.
        if (!granted && owned.none { it.purchaseState == Purchase.PurchaseState.PURCHASED } && _fullResolution.value) set(false)
        if (!quiet) message.value = if (granted || _fullResolution.value) "Full resolution restored" else "Nothing to restore"
    }

    /** True when the purchase is complete (and acknowledged); a pending one leaves a message instead. */
    private suspend fun grant(p: Purchase): Boolean {
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (!p.isAcknowledged) {
                    val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    val r = client.acknowledgePurchase(ack)
                    if (r.responseCode != BillingResponseCode.OK) {
                        Log.w(TAG, "acknowledge: ${r.debugMessage}")
                        return false
                    }
                }
                set(true)
                return true
            }
            Purchase.PurchaseState.PENDING -> {
                message.value = "Purchase pending: Google Play will complete it"
                return false
            }
            else -> return false
        }
    }

    private fun set(owned: Boolean) {
        _fullResolution.value = owned
        prefs.edit().putBoolean(KEY_FULLRES, owned).apply()
    }

    override fun purchase(activity: Activity) {
        val details = product
        if (details == null) {
            message.value = "The store has not answered yet. Try again in a moment."
            scope.launch { loadProduct() }
            return
        }
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        offer?.offerToken?.takeIf { it.isNotEmpty() }?.let { item.setOfferToken(it) }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build()
        _busy.value = true
        val r = client.launchBillingFlow(activity, params)
        if (r.responseCode != BillingResponseCode.OK) {
            _busy.value = false
            message.value = r.debugMessage.ifEmpty { "Could not open the store" }
        }
    }

    /** Play's answer to the purchase sheet (also to a pending purchase completed later). */
    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        scope.launch {
            try {
                when (result.responseCode) {
                    BillingResponseCode.OK -> {
                        var granted = false
                        for (p in purchases.orEmpty()) if (Entitlements.PRODUCT_ID in p.products) granted = grant(p) || granted
                        if (granted) message.value = "Full resolution unlocked"
                    }
                    BillingResponseCode.ITEM_ALREADY_OWNED -> refresh(quiet = false)
                    BillingResponseCode.USER_CANCELED -> {}
                    else -> message.value = result.debugMessage.ifEmpty { "Purchase failed" }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    override fun restore() {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                refresh(quiet = false)
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "PlayEntitlements"
        const val KEY_FULLRES = "fullres"
    }
}
