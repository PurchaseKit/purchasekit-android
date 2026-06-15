# PurchaseKit Android

Android library for Google Play Billing integration with Hotwire Native.

## Structure

- `purchasekit/src/main/java/dev/purchasekit/android/PaywallComponent.kt` - Bridge component (thin routing layer)
- `purchasekit/src/main/java/dev/purchasekit/android/BillingStore.kt` - Google Play Billing operations (prices, purchases)
- `purchasekit/src/main/java/dev/purchasekit/android/Version.kt` - Library version constant

## PaywallComponent

Hotwire Native bridge component that handles:

| Message | Request | Response |
|---------|---------|----------|
| `prices` | Product IDs + optional base plan IDs | Localized prices |
| `purchase` | Product ID + optional base plan ID + correlation UUID + optional proration mode | Status (success/pending/cancelled/error) |
| `restore` | (none) | List of active subscription IDs |

### Prices flow

1. Web sends `prices` message with product IDs
2. Component fetches from Google Play via `queryProductDetails`
3. Returns localized `formattedPrice` for each product

### Purchase flow

1. Web sends `purchase` message with `googleStoreProductId` and `correlationId` (UUID)
2. Component calls `launchBillingFlow` with `obfuscatedAccountId` set to UUID
3. UUID links the purchase to the SaaS Purchase::Intent
4. Returns status to web (success keeps spinner, cancelled re-enables form)

### Plan upgrades and downgrades

Switching base plans within one umbrella subscription (for example monthly to annual on the same product) requires the Google purchase token of the existing subscription. The purchase flow looks it up via `queryPurchasesAsync` and attaches `SubscriptionUpdateParams` with a replacement mode. Apple handles intra-group swaps for free, so this is Android only.

- Replacement mode defaults to `CHARGE_PRORATED_PRICE`, the closest match to Apple's "refund unused time".
- Override it with the `proration_mode` paywall option in the gem. Accepted values: `charge_prorated_price`, `with_time_proration`, `charge_full_price`, `without_proration`, `deferred`.
- Buying a different product the user does not own (for example Vendor to Employer) starts a new subscription with no token attached.
- If Google still reports `ITEM_ALREADY_OWNED`, the component returns an error instead of a false success, so a blocked swap no longer looks like it worked.

### Restore flow

1. Web sends `restore` message (no request data)
2. Component calls `billingStore.currentSubscriptionIds()` which queries `SUBS` purchases
3. Filters to `PURCHASED` state and returns order IDs (with renewal suffix stripped)
4. Web dispatches `purchasekit--paywall:restore` DOM event with the IDs
5. Developer matches IDs against stored subscriptions (IDs match `subscription_id` from webhook payloads)

## Environment detection

Unlike iOS, Android doesn't report environment from the client. Google Play doesn't have true sandbox/production separation - all purchases go through production infrastructure.

The PurchaseKit server determines environment by checking Google's `testPurchase` flag when processing webhooks:
- `testPurchase: true` → sandbox (license tester purchase)
- `testPurchase: false` or absent → production

## BillingStore

Singleton that manages Google Play Billing connection and operations:

- `connect()` - Establishes connection to Google Play
- `prices(queries)` - Fetches localized prices for subscription products (accepts `List<ProductQuery>` with optional `basePlanId`)
- `purchase(activity, productId, basePlanId, correlationId, prorationMode)` - Launches purchase flow. When the user already owns the product, attaches `SubscriptionUpdateParams` so the base plan is swapped instead of failing. `basePlanId` and `prorationMode` are optional
- `acknowledgePurchase(purchase)` - Acknowledges successful purchase
- `currentSubscriptionIds()` - Returns order IDs of active subscriptions (for restore)

## Google Play Billing notes

- Uses Google Play Billing Library 7.x
- Requires `com.android.vending.BILLING` permission (added in manifest)
- `obfuscatedAccountId` is used for correlation (similar to Apple's `appAccountToken`)
- Purchases must be acknowledged within 3 days or they're automatically refunded
- Subscription renewals and cancellations come via RTDN (not through the app)

## Installation

### JitPack (recommended)

Add JitPack to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.purchasekit:purchasekit-android:0.1.0")
}
```

### Local development

Include the module in your `settings.gradle.kts`:

```kotlin
include(":purchasekit")
project(":purchasekit").projectDir = file("path/to/purchasekit")
```

Add the dependency:

```kotlin
dependencies {
    implementation(project(":purchasekit"))
}
```

## Usage

Register the bridge component in your Application class:

```kotlin
import dev.purchasekit.android.PaywallComponent

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Hotwire.registerBridgeComponents(
            BridgeComponentFactory("paywall", ::PaywallComponent)
        )

        Hotwire.config.jsonConverter = KotlinXJsonConverter()
    }
}
```

## Testing

### Emulator limitations

Google Play Billing requires being signed into a Google account on the device. Emulators can fetch prices if signed in, but **subscription purchases require a physical device**. The Google Play purchase flow does not complete reliably on emulators, even with a signed-in account and license tester configured.

### License testers

For testing purchases without being charged:

1. Go to Play Console → Setup → License testing
2. Add tester email addresses
3. Testers can make purchases that don't charge their payment method

### Internal testing track

For testing the full purchase flow:

1. Create an internal testing track in Play Console
2. Upload your APK/AAB
3. Add testers to the track
4. Testers install via the Play Store link

### Subscription test durations

Google Play doesn't have accelerated test subscriptions like Apple's sandbox. Test subscriptions renew at their normal intervals. Use license testers to avoid charges.

## Dependencies

- `com.android.billingclient:billing-ktx` - Google Play Billing
- `dev.hotwire:core` - Hotwire Native bridge
- `dev.hotwire:navigation-fragments` - Hotwire Native navigation
- `org.jetbrains.kotlinx:kotlinx-serialization-json` - JSON serialization
