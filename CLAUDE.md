# PurchaseKit Android

Android library for Google Play Billing integration with Hotwire Native.

## Structure

- `purchasekit/src/main/java/dev/purchasekit/android/PaywallComponent.kt` - Bridge component (thin routing layer)
- `purchasekit/src/main/java/dev/purchasekit/android/BillingStore.kt` - Google Play Billing operations (prices, purchases)
- `purchasekit/src/main/java/dev/purchasekit/android/Environment.kt` - Detects debug vs release environment
- `purchasekit/src/main/java/dev/purchasekit/android/Version.kt` - Library version constant

## PaywallComponent

Hotwire Native bridge component that handles:

| Message | Request | Response |
|---------|---------|----------|
| `prices` | Product IDs | Localized prices + environment |
| `purchase` | Product ID + correlation UUID | Status (success/pending/cancelled/error) |

### Prices flow

1. Web sends `prices` message with product IDs
2. Component fetches from Google Play via `queryProductDetails`
3. Returns localized `formattedPrice` for each product
4. Includes `environment` (sandbox/production) for the web to pass to SaaS

### Purchase flow

1. Web sends `purchase` message with `googleStoreProductId` and `correlationId` (UUID)
2. Component calls `launchBillingFlow` with `obfuscatedAccountId` set to UUID
3. UUID links the purchase to the SaaS Purchase::Intent
4. Returns status to web (success keeps spinner, cancelled re-enables form)

## Environment detection

`Environment.current(context)` returns `.Sandbox` or `.Production`:

| Build type | Environment |
|------------|-------------|
| Debug (debuggable flag set) | sandbox |
| Release | production |

**Note:** Unlike Apple's sandbox, Google Play doesn't have separate environments. The `testPurchase` flag only appears for license tester accounts configured in Play Console.

## BillingStore

Singleton that manages Google Play Billing connection and operations:

- `connect()` - Establishes connection to Google Play
- `prices(productIds)` - Fetches localized prices for subscription products
- `purchase(activity, productId, correlationId)` - Launches purchase flow
- `acknowledgePurchase(purchase)` - Acknowledges successful purchase

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
