package dev.purchasekit.android

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
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

    suspend fun prices(queries: List<ProductQuery>): Map<String, String> {
        Log.d(TAG, "prices() called for: $queries")

        if (!connect()) {
            Log.e(TAG, "Failed to connect for prices query")
            throw BillingException("Failed to connect to Google Play Billing")
        }

        val client = billingClient ?: throw BillingException("BillingClient is null")

        // Deduplicate product IDs for the Google Play query
        val uniqueProductIds = queries.map { it.productId }.distinct()
        val productList = uniqueProductIds.map { productId ->
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

        // Build a lookup from productId to product details
        val detailsByProductId = productDetails.associateBy { it.productId }

        for (query in queries) {
            val product = detailsByProductId[query.productId] ?: continue

            Log.d(TAG, "Product: ${product.productId}, " +
                    "title=${product.title}, " +
                    "offerCount=${product.subscriptionOfferDetails?.size ?: 0}")

            product.subscriptionOfferDetails?.forEachIndexed { index, offer ->
                Log.d(TAG, "  offer[$index]: " +
                        "basePlanId=${offer.basePlanId}, " +
                        "offerId=${offer.offerId}, " +
                        "pricingPhases=${offer.pricingPhases.pricingPhaseList.map { it.formattedPrice }}")
            }

            // Match by basePlanId when provided, fall back to first offer
            val offer = if (query.basePlanId != null) {
                product.subscriptionOfferDetails?.firstOrNull { it.basePlanId == query.basePlanId }
            } else {
                product.subscriptionOfferDetails?.firstOrNull()
            }

            val price = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

            if (price != null) {
                // Key by basePlanId when present, productId when not
                val key = query.basePlanId ?: product.productId
                prices[key] = price
                Log.d(TAG, "Price for ${key}: $price")
            }
        }

        val foundKeys = prices.keys
        val missingQueries = queries.filter { (it.basePlanId ?: it.productId) !in foundKeys }
        if (missingQueries.isNotEmpty()) {
            Log.w(TAG, "Products not found: $missingQueries")
        }

        Log.d(TAG, "prices() returning: $prices")
        return prices
    }

    suspend fun purchase(
        activity: Activity,
        productId: String,
        basePlanId: String? = null,
        correlationId: String,
        prorationMode: String? = null
    ): PurchaseResult {
        Log.d(TAG, "purchase() called: productId=$productId, basePlanId=$basePlanId, " +
                "correlationId=$correlationId, prorationMode=$prorationMode")

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

        val offerDetails = if (basePlanId != null) {
            productDetails.subscriptionOfferDetails?.firstOrNull { it.basePlanId == basePlanId }
        } else {
            productDetails.subscriptionOfferDetails?.firstOrNull()
        }
        if (offerDetails == null) {
            Log.e(TAG, "No offer found for product: $productId (basePlanId=$basePlanId)")
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

        val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setObfuscatedAccountId(correlationId)

        // When the user already owns this product (e.g. switching the monthly base
        // plan to annual within one umbrella subscription), Google requires the
        // existing purchase token so it can replace the plan instead of rejecting
        // the buy with ITEM_ALREADY_OWNED. Apple does intra-group swaps for free.
        val existingToken = activeSubscriptionToken(client, productId)
        if (existingToken != null) {
            val replacementMode = replacementModeFor(prorationMode)
            Log.d(TAG, "Existing subscription found for $productId, attaching " +
                    "SubscriptionUpdateParams (replacementMode=$replacementMode, " +
                    "oldToken=${existingToken.take(20)}...)")
            billingFlowParamsBuilder.setSubscriptionUpdateParams(
                SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(existingToken)
                    .setSubscriptionReplacementMode(replacementMode)
                    .build()
            )
        }

        val billingFlowParams = billingFlowParamsBuilder.build()

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

    // Returns the purchase token of an active subscription for [productId], or null
    // if the user does not currently own it. Used to drive plan upgrades/downgrades.
    private suspend fun activeSubscriptionToken(client: BillingClient, productId: String): String? {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = client.queryPurchasesAsync(params)

        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Could not query existing purchases for upgrade: " +
                    "${result.billingResult.debugMessage}")
            return null
        }

        return result.purchasesList
            .firstOrNull {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    it.products.contains(productId)
            }
            ?.purchaseToken
    }

    // Maps a paywall proration option to a Google Play replacement mode. Defaults to
    // CHARGE_PRORATED_PRICE, the closest match to Apple's "refund unused time".
    private fun replacementModeFor(prorationMode: String?): Int {
        return when (prorationMode?.lowercase()) {
            "with_time_proration" -> SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
            "charge_full_price" -> SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE
            "without_proration" -> SubscriptionUpdateParams.ReplacementMode.WITHOUT_PRORATION
            "deferred" -> SubscriptionUpdateParams.ReplacementMode.DEFERRED
            else -> SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
        }
    }

    suspend fun currentSubscriptionIds(): List<String> {
        Log.d(TAG, "currentSubscriptionIds() called")

        if (!connect()) {
            throw Exception("Failed to connect to Google Play")
        }

        val client = billingClient ?: throw Exception("Billing client not available")

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val result = client.queryPurchasesAsync(params)

        Log.d(TAG, "queryPurchasesAsync result: responseCode=${result.billingResult.responseCode}, " +
                "purchaseCount=${result.purchasesList.size}")

        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            throw Exception("Failed to query purchases: ${result.billingResult.debugMessage}")
        }

        val ids = result.purchasesList
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .mapNotNull { it.orderId }
            .map { it.replace(Regex("\\.\\.\\d+$"), "") }

        Log.d(TAG, "currentSubscriptionIds() returning: $ids")
        return ids
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

data class ProductQuery(
    val productId: String,
    val basePlanId: String? = null
)

class BillingException(message: String) : Exception(message)
