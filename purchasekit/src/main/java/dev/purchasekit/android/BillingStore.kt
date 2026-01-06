package dev.purchasekit.android

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class BillingStore private constructor(context: Context) {
    private var billingClient: BillingClient? = null
    private var isConnected = false
    private var pendingPurchaseContinuation: ((PurchaseResult) -> Unit)? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "purchasesUpdated: responseCode=${billingResult.responseCode}, " +
                "debugMessage=${billingResult.debugMessage}, " +
                "purchaseCount=${purchases?.size ?: 0}")

        purchases?.forEachIndexed { index, purchase ->
            Log.d(TAG, "  purchase[$index]: " +
                    "orderId=${purchase.orderId}, " +
                    "products=${purchase.products}, " +
                    "purchaseState=${purchase.purchaseState}, " +
                    "isAcknowledged=${purchase.isAcknowledged}")
        }

        val result = when (billingResult.responseCode) {
            BillingResponseCode.OK -> {
                purchases?.firstOrNull()?.let { purchase ->
                    Log.d(TAG, "Purchase successful: ${purchase.products}")
                    PurchaseResult.Success(purchase)
                } ?: run {
                    Log.e(TAG, "Purchase OK but no purchase returned")
                    PurchaseResult.Error("No purchase returned")
                }
            }

            BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Purchase cancelled by user")
                PurchaseResult.Cancelled
            }
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned")
                PurchaseResult.AlreadyOwned
            }
            BillingResponseCode.ITEM_NOT_OWNED -> {
                Log.e(TAG, "Item not owned error")
                PurchaseResult.Error("Item not owned")
            }
            else -> {
                val errorMsg = billingResult.debugMessage.ifEmpty {
                    "Purchase failed (${billingResult.responseCode})"
                }
                Log.e(TAG, "Purchase failed: $errorMsg")
                PurchaseResult.Error(errorMsg)
            }
        }

        pendingPurchaseContinuation?.invoke(result)
        pendingPurchaseContinuation = null
    }

    private val appContext = context.applicationContext

    private fun getOrCreateBillingClient(): BillingClient {
        return billingClient ?: BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()
            .also {
                billingClient = it
                Log.d(TAG, "Created new BillingClient")
            }
    }

    suspend fun connect(): Boolean {
        Log.d(TAG, "connect() called, isConnected=$isConnected, isReady=${billingClient?.isReady}")

        if (isConnected && billingClient?.isReady == true) {
            Log.d(TAG, "Already connected")
            return true
        }

        val client = getOrCreateBillingClient()

        return suspendCoroutine { continuation ->
            Log.d(TAG, "Starting billing connection...")
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    isConnected = billingResult.responseCode == BillingResponseCode.OK
                    Log.d(TAG, "Billing setup finished: " +
                            "responseCode=${billingResult.responseCode}, " +
                            "isConnected=$isConnected, " +
                            "debugMessage=${billingResult.debugMessage}")
                    continuation.resume(isConnected)
                }

                override fun onBillingServiceDisconnected() {
                    isConnected = false
                    Log.w(TAG, "Billing service disconnected")
                }
            })
        }
    }

    suspend fun prices(productIds: List<String>): Map<String, String> {
        Log.d(TAG, "prices() called for: $productIds")

        if (!connect()) {
            Log.e(TAG, "Failed to connect for prices query")
            throw BillingException("Failed to connect to Google Play Billing")
        }

        val client = billingClient ?: throw BillingException("BillingClient is null")

        val productList = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Log.d(TAG, "Querying product details...")
        val result = client.queryProductDetails(params)

        Log.d(TAG, "Query result: responseCode=${result.billingResult.responseCode}, " +
                "debugMessage=${result.billingResult.debugMessage}, " +
                "productCount=${result.productDetailsList?.size ?: 0}")

        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            val errorMsg = result.billingResult.debugMessage.ifEmpty {
                "Failed to query product details (${result.billingResult.responseCode})"
            }
            Log.e(TAG, "Query failed: $errorMsg")
            throw BillingException(errorMsg)
        }

        val productDetails = result.productDetailsList ?: emptyList()
        val prices = mutableMapOf<String, String>()

        for (product in productDetails) {
            Log.d(TAG, "Product: ${product.productId}, " +
                    "title=${product.title}, " +
                    "offerCount=${product.subscriptionOfferDetails?.size ?: 0}")

            product.subscriptionOfferDetails?.forEachIndexed { index, offer ->
                Log.d(TAG, "  offer[$index]: " +
                        "basePlanId=${offer.basePlanId}, " +
                        "offerId=${offer.offerId}, " +
                        "pricingPhases=${offer.pricingPhases.pricingPhaseList.map { it.formattedPrice }}")
            }

            val price = product.subscriptionOfferDetails
                ?.firstOrNull()
                ?.pricingPhases
                ?.pricingPhaseList
                ?.firstOrNull()
                ?.formattedPrice

            if (price != null) {
                prices[product.productId] = price
                Log.d(TAG, "Price for ${product.productId}: $price")
            }
        }

        val foundIds = prices.keys
        val missingIds = productIds.filter { it !in foundIds }
        if (missingIds.isNotEmpty()) {
            Log.w(TAG, "Products not found: $missingIds")
        }

        Log.d(TAG, "prices() returning: $prices")
        return prices
    }

    suspend fun purchase(
        activity: Activity,
        productId: String,
        correlationId: String
    ): PurchaseResult {
        Log.d(TAG, "purchase() called: productId=$productId, correlationId=$correlationId")

        if (!connect()) {
            Log.e(TAG, "Failed to connect for purchase")
            return PurchaseResult.Error("Failed to connect to Google Play Billing")
        }

        val client = billingClient ?: return PurchaseResult.Error("BillingClient is null")

        // Get product details
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Log.d(TAG, "Querying product details for purchase...")
        val queryResult = client.queryProductDetails(queryParams)

        Log.d(TAG, "Query for purchase: responseCode=${queryResult.billingResult.responseCode}, " +
                "productCount=${queryResult.productDetailsList?.size ?: 0}")

        val productDetails = queryResult.productDetailsList?.firstOrNull()
        if (productDetails == null) {
            Log.e(TAG, "Product not found: $productId")
            return PurchaseResult.Error("Product not found: $productId")
        }

        Log.d(TAG, "Found product: ${productDetails.productId}, " +
                "offerCount=${productDetails.subscriptionOfferDetails?.size ?: 0}")

        val offerDetails = productDetails.subscriptionOfferDetails?.firstOrNull()
        if (offerDetails == null) {
            Log.e(TAG, "No offer found for product: $productId")
            return PurchaseResult.Error("No offer found for product: $productId")
        }

        val offerToken = offerDetails.offerToken
        Log.d(TAG, "Using offer: basePlanId=${offerDetails.basePlanId}, " +
                "offerId=${offerDetails.offerId}, " +
                "offerToken=${offerToken.take(20)}...")

        // Build purchase params with obfuscatedAccountId for correlation
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setObfuscatedAccountId(correlationId)
            .build()

        Log.d(TAG, "Launching billing flow...")

        // Wait for purchase result from listener using suspendCancellableCoroutine
        return suspendCancellableCoroutine { continuation ->
            pendingPurchaseContinuation = { result ->
                Log.d(TAG, "Purchase continuation received: $result")
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            continuation.invokeOnCancellation {
                Log.d(TAG, "Purchase continuation cancelled")
                pendingPurchaseContinuation = null
            }

            // Launch billing flow on main thread
            val billingResult = client.launchBillingFlow(activity, billingFlowParams)

            Log.d(TAG, "launchBillingFlow result: responseCode=${billingResult.responseCode}, " +
                    "debugMessage=${billingResult.debugMessage}")

            if (billingResult.responseCode != BillingResponseCode.OK) {
                pendingPurchaseContinuation = null
                val errorMsg = billingResult.debugMessage.ifEmpty {
                    "Failed to launch billing flow (${billingResult.responseCode})"
                }
                Log.e(TAG, "Failed to launch billing flow: $errorMsg")
                continuation.resume(PurchaseResult.Error(errorMsg))
            }
        }
    }

    suspend fun acknowledgePurchase(purchase: Purchase) {
        Log.d(TAG, "acknowledgePurchase() called: " +
                "purchaseState=${purchase.purchaseState}, " +
                "isAcknowledged=${purchase.isAcknowledged}")

        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            Log.d(TAG, "Skipping acknowledgment - not in PURCHASED state")
            return
        }
        if (purchase.isAcknowledged) {
            Log.d(TAG, "Skipping acknowledgment - already acknowledged")
            return
        }

        val client = billingClient ?: return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        Log.d(TAG, "Acknowledging purchase...")
        val result = client.acknowledgePurchase(params)
        Log.d(TAG, "Acknowledgment result: responseCode=${result.responseCode}, " +
                "debugMessage=${result.debugMessage}")
    }

    companion object {
        private const val TAG = "BillingStore"

        @Volatile
        private var instance: BillingStore? = null

        fun getInstance(context: Context): BillingStore {
            return instance ?: synchronized(this) {
                instance ?: BillingStore(context.applicationContext).also { instance = it }
            }
        }
    }
}

sealed class PurchaseResult {
    data class Success(val purchase: Purchase) : PurchaseResult()
    data object Cancelled : PurchaseResult()
    data object AlreadyOwned : PurchaseResult()
    data object Pending : PurchaseResult()
    data class Error(val message: String) : PurchaseResult()
}

class BillingException(message: String) : Exception(message)
