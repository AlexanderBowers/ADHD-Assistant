package com.example.adhdassistant.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.example.adhdassistant.BuildConfig
import com.example.adhdassistant.config.ConfigRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val configRepository: ConfigRepository
) : PurchasesUpdatedListener {

    companion object {
        const val PRO_PRODUCT_ID = "adhd_assistant_pro"
        private const val TAG = "BillingManager"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L

        /** In debug builds Pro is automatically granted — no Play purchase needed. */
        private val DEBUG_PRO_ENABLED = BuildConfig.DEBUG
    }

    sealed class BillingState {
        object Loading : BillingState()
        object Connected : BillingState()
        object Disconnected : BillingState()
        data class PurchaseSuccess(val productId: String) : BillingState()
        data class PurchaseError(val message: String) : BillingState()
        object PurchaseCancelled : BillingState()
        object AlreadyOwned : BillingState()
    }

    private val _proStatus = MutableStateFlow(false)
    val proStatus: StateFlow<Boolean> = _proStatus.asStateFlow()

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Loading)
    val billingState: StateFlow<BillingState> = _billingState.asStateFlow()

    private var cachedProductDetails: ProductDetails? = null
    private var retryCount = 0

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        if (DEBUG_PRO_ENABLED) {
            Log.d(TAG, "Debug build — Pro features unlocked without purchase")
            _proStatus.value = true
            _billingState.value = BillingState.Connected
            scope.launch { configRepository.setProVersion(true) }
        } else {
            scope.launch { _proStatus.value = configRepository.isProVersion() }
            connect()
        }
    }

    private fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                retryCount = 0
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected")
                    _billingState.value = BillingState.Connected
                    scope.launch {
                        queryAndSyncPurchases()
                        cacheProductDetails()
                    }
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                    _billingState.value = BillingState.Disconnected
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
                _billingState.value = BillingState.Disconnected
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (retryCount >= MAX_RETRY_ATTEMPTS) { Log.e(TAG, "Max reconnect attempts reached"); return }
        retryCount++
        scope.launch {
            delay(RETRY_DELAY_MS * retryCount)
            if (!billingClient.isReady) connect()
        }
    }

    /** In debug builds, Pro is already granted — returns true immediately. */
    suspend fun launchProPurchase(activity: Activity): Boolean {
        if (DEBUG_PRO_ENABLED) {
            _billingState.value = BillingState.PurchaseSuccess(PRO_PRODUCT_ID)
            return true
        }
        if (cachedProductDetails == null) cacheProductDetails()
        val details = cachedProductDetails ?: run {
            _billingState.value = BillingState.PurchaseError("Product not available")
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
            ).build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "launchBillingFlow failed: ${result.debugMessage}")
            _billingState.value = BillingState.PurchaseError(result.debugMessage)
            return false
        }
        return true
    }

    /** In debug builds, Pro is already granted — emits AlreadyOwned. */
    suspend fun restorePurchases() {
        if (DEBUG_PRO_ENABLED) { _billingState.value = BillingState.AlreadyOwned; return }
        if (!billingClient.isReady) { _billingState.value = BillingState.PurchaseError("Billing not ready"); return }
        queryAndSyncPurchases()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.let { scope.launch { handlePurchases(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> _billingState.value = BillingState.PurchaseCancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _billingState.value = BillingState.AlreadyOwned
                scope.launch { queryAndSyncPurchases() }
            }
            else -> {
                Log.e(TAG, "Purchase error: ${result.responseCode}")
                _billingState.value = BillingState.PurchaseError(result.debugMessage)
            }
        }
    }

    private suspend fun queryAndSyncPurchases() {
        if (!billingClient.isReady) return
        val result = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        )
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            handlePurchases(result.purchasesList)
        }
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) {
        var foundPro = false
        for (purchase in purchases) {
            if (purchase.products.contains(PRO_PRODUCT_ID)) {
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        foundPro = true
                        if (!purchase.isAcknowledged) acknowledge(purchase)
                        _billingState.value = BillingState.PurchaseSuccess(PRO_PRODUCT_ID)
                    }
                    Purchase.PurchaseState.PENDING -> Log.d(TAG, "Purchase pending for ${purchase.products}")
                    else -> Unit
                }
            }
        }
        if (foundPro != _proStatus.value) {
            _proStatus.value = foundPro
            configRepository.setProVersion(foundPro)
        }
        if (purchases.isEmpty() && _proStatus.value) {
            _proStatus.value = false
            configRepository.setProVersion(false)
        }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Acknowledge failed: ${result.debugMessage}")
        } else {
            Log.d(TAG, "Purchase acknowledged for ${purchase.products}")
        }
    }

    private suspend fun cacheProductDetails() {
        if (!billingClient.isReady) return
        val result = billingClient.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRO_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build())
            ).build()
        )
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            cachedProductDetails = result.productDetailsList?.firstOrNull()
            Log.d(TAG, "Product details loaded: ${cachedProductDetails?.name}")
        }
    }

    /** Returns "$0.99" in debug builds; formatted Play price in release. */
    fun getFormattedPrice(): String? {
        if (DEBUG_PRO_ENABLED) return "$0.99"
        return cachedProductDetails?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    fun destroy() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
