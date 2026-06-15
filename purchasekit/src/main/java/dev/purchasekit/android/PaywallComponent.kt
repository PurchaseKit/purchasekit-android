package dev.purchasekit.android

import android.util.Log
import androidx.lifecycle.lifecycleScope
import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import dev.hotwire.navigation.fragments.HotwireFragment
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class PaywallComponent(
    name: String,
    private val bridgeDelegate: BridgeDelegate<HotwireDestination>
) : BridgeComponent<HotwireDestination>(name, bridgeDelegate) {

    private val fragment: HotwireFragment
        get() = bridgeDelegate.destination.fragment as HotwireFragment
    private val billingStore: BillingStore
        get() = BillingStore.getInstance(fragment.requireContext())

    override fun onReceive(message: Message) {
        Log.d(TAG, "onReceive: event=${message.event}, jsonData=${message.jsonData}")
        when (message.event) {
            "prices" -> handlePrices(message)
            "purchase" -> handlePurchase(message)
            "restore" -> handleRestore(message)
            else -> Log.w(TAG, "Unknown event for message: $message")
        }
    }

    private fun handlePrices(message: Message) {
        val data = message.data<PricesRequest>()
        Log.d(TAG, "handlePrices: data=$data")

        if (data == null) {
            Log.e(TAG, "Failed to parse PricesRequest")
            return
        }

        val queries = data.products.mapNotNull { product ->
            product.googleStoreProductId?.let { productId ->
                ProductQuery(productId = productId, basePlanId = product.googleStoreBasePlanId)
            }
        }
        Log.d(TAG, "handlePrices: queries=$queries")

        if (queries.isEmpty()) {
            Log.w(TAG, "No Google product IDs found in request")
            replyTo(message.event, PricesResponse(error = "No Google product IDs"))
            return
        }

        fragment.lifecycleScope.launch {
            try {
                Log.d(TAG, "Fetching prices for: $queries")
                val prices = billingStore.prices(queries)
                Log.d(TAG, "Prices fetched: $prices")

                val response = PricesResponse(prices = prices)
                Log.d(TAG, "Replying with: $response")
                replyTo(message.event, response)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load prices for $queries", e)
                replyTo(message.event, PricesResponse(error = e.message))
            }
        }
    }

    private fun handlePurchase(message: Message) {
        val data = message.data<PurchaseRequest>()
        Log.d(TAG, "handlePurchase: data=$data")

        if (data == null) {
            Log.e(TAG, "Failed to parse PurchaseRequest")
            return
        }

        val productId = data.googleStoreProductId
        if (productId == null) {
            Log.e(TAG, "No Google product ID in purchase request")
            replyTo(message.event, PurchaseResponse(status = PurchaseStatus.Error, error = "No Google product ID"))
            return
        }

        Log.d(TAG, "Starting purchase: productId=$productId, basePlanId=${data.googleStoreBasePlanId}, correlationId=${data.correlationId}")

        fragment.lifecycleScope.launch {
            try {
                val result = billingStore.purchase(
                    activity = fragment.requireActivity(),
                    productId = productId,
                    basePlanId = data.googleStoreBasePlanId,
                    correlationId = data.correlationId,
                    prorationMode = data.googleStoreProrationMode
                )

                Log.d(TAG, "Purchase result: $result")

                val response = when (result) {
                    is PurchaseResult.Success -> {
                        Log.d(TAG, "Purchase successful, acknowledging...")
                        billingStore.acknowledgePurchase(result.purchase)
                        PurchaseResponse(status = PurchaseStatus.Success)
                    }

                    is PurchaseResult.Cancelled -> {
                        Log.d(TAG, "Purchase cancelled by user")
                        PurchaseResponse(status = PurchaseStatus.Cancelled)
                    }

                    is PurchaseResult.Pending -> {
                        Log.d(TAG, "Purchase pending")
                        PurchaseResponse(status = PurchaseStatus.Pending)
                    }

                    is PurchaseResult.AlreadyOwned -> {
                        // The buy was rejected because the user already owns this
                        // product and nothing changed. A blocked plan swap lands here,
                        // so surface it as an error rather than a false success.
                        Log.w(TAG, "Item already owned, purchase did not change anything")
                        PurchaseResponse(
                            status = PurchaseStatus.Error,
                            error = "You already own this subscription."
                        )
                    }

                    is PurchaseResult.Error -> {
                        Log.e(TAG, "Purchase error: ${result.message}")
                        PurchaseResponse(status = PurchaseStatus.Error, error = result.message)
                    }
                }

                Log.d(TAG, "Replying with: $response")
                replyTo(message.event, response)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to purchase $productId", e)
                replyTo(message.event, PurchaseResponse(status = PurchaseStatus.Error, error = e.message))
            }
        }
    }

    private fun handleRestore(message: Message) {
        fragment.lifecycleScope.launch {
            try {
                val ids = billingStore.currentSubscriptionIds()
                Log.d(TAG, "Restore subscription IDs: $ids")
                replyTo(message.event, RestoreResponse(subscriptionIds = ids))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore purchases", e)
                replyTo(message.event, RestoreResponse(subscriptionIds = emptyList(), error = e.message))
            }
        }
    }

    companion object {
        private const val TAG = "PaywallComponent"
    }
}

// Request/Response models

@Serializable
internal data class PricesRequest(
    val products: List<Product>
) {
    @Serializable
    data class Product(
        @SerialName("googleStoreProductId")
        val googleStoreProductId: String?,
        @SerialName("googleStoreBasePlanId")
        val googleStoreBasePlanId: String? = null
    )
}

@Serializable
internal data class PricesResponse(
    val prices: Map<String, String>? = null,
    val error: String? = null
)

@Serializable
internal data class PurchaseRequest(
    @SerialName("googleStoreProductId")
    val googleStoreProductId: String?,
    @SerialName("googleStoreBasePlanId")
    val googleStoreBasePlanId: String? = null,
    @SerialName("googleStoreProrationMode")
    val googleStoreProrationMode: String? = null,
    val correlationId: String
)

@Serializable
internal data class RestoreResponse(
    val subscriptionIds: List<String>,
    val error: String? = null
)

@Serializable
internal data class PurchaseResponse(
    val status: PurchaseStatus,
    val error: String? = null
)

@Serializable
internal enum class PurchaseStatus {
    @SerialName("success") Success,
    @SerialName("pending") Pending,
    @SerialName("cancelled") Cancelled,
    @SerialName("error") Error
}
