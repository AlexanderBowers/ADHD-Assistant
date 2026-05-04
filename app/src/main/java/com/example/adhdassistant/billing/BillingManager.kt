package com.example.adhdassistant.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.example.adhdassistant.config.ConfigRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Google Play In-App Purchases for the $0.99 Pro upgrade.
 *
 * Usage:
 *   val billing = BillingManager(context, lifecycleScope, configRepository)
 *   billing.proStatus.collect { isPro -> updateUI(isPro) }
 *   billing.launchProPurchase(activity)
 */
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
        // Immediately reflect any cached Pro status from DataStore
        scope.launch {
            _proStatus.value = configRepository.isProVersion()
        }
        connect()
    }

    // ─── Connection ──────────────────────────────────────────────────────────

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
        if (retryCount >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached")
            return
        }
        retryCount++
        scope.launch {
            delay(RETRY_DELAY_MS * retryCount)
            if (!billingClient.isReady) connect()
        }
    }

    // ─── Purchase Flow ───────────────────────────────────────────────────────

    /**
     * Call from an Activity to launch the $0.99 purchase bottom sheet.
     * Returns false if product details haven't loaded yet.
     */
    suspend fun launchProPurchase(activity: Activity): Boolean {
        if (cachedProductDetails == null) {
            cacheProductDetails()
        }
        val details = cachedProductDetails ?: run {
            _billingState.value = BillingState.PurchaseError("Product not available")
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "launchBillingFlow failed: ${result.debugMessage}")
            _billingState.value = BillingState.PurchaseError(result.debugMessage)
            return false
        }
        return true
    }

    /**
     * Restore purchases — call when user taps "Restore Purchase" in Settings.
     */
    suspend fun restorePurchases() {
        if (!billingClient.isReady) {
            _billingState.value = BillingState.PurchaseError("Billing not ready")
            return
        }
        queryAndSyncPurchases()
    }

    // ─── PurchasesUpdatedListener ─────────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let {
                    scope.launch { handlePurchases(it) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.value = BillingState.PurchaseCancelled
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _billingState.value = BillingState.AlreadyOwned
                scope.launch { queryAndSyncPurchases() }
            }
            else -> {
                Log.e(TAG, "Purchase error: ${result.responseCode} — ${result.debugMessage}")
                _billingState.value = BillingState.PurchaseError(result.debugMessage)
            }
        }
    }

    // ─── Internal Helpers ─────────────────────────────────────────────────────

    private suspend fun queryAndSyncPurchases() {
        if (!billingClient.isReady) return

        val result = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
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
                        if (!purchase.isAcknowledged) {
                            acknowledge(purchase)
                        }
                        _billingState.value = BillingState.PurchaseSuccess(PRO_PRODUCT_ID)
                    }
                    Purchase.PurchaseState.PENDING -> {
                        // Don't grant Pro yet; wait for PURCHASED state
                        Log.d(TAG, "Purchase pending for ${purchase.products}")
                    }
                    else -> Unit
                }
            }
        }

        // Sync Pro status to DataStore (single source of truth)
        if (foundPro != _proStatus.value) {
            _proStatus.value = foundPro
            configRepository.setProVersion(foundPro)
        }

        // If no active purchases found, ensure Pro is revoked
        if (purchases.isEmpty() && _proStatus.value) {
            _proStatus.value = false
            configRepository.setProVersion(false)
        }
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
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
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRO_PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
        )

        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            cachedProductDetails = result.productDetailsList?.firstOrNull()
            Log.d(TAG, "Product details loaded: ${cachedProductDetails?.name}")
        }
    }

    /**
     * Get formatted price string (e.g. "$0.99") for display in UI.
     */
    fun getFormattedPrice(): String? {
        return cachedProductDetails
            ?.oneTimePurchaseOfferDetails
            ?.formattedPrice
    }

    fun destroy() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}