# PurchaseKit Android

Android library providing a Hotwire Native bridge component for Google Play Billing via [PurchaseKit](https://purchasekit.dev).

## Installation

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

## Usage

Register the bridge component with Hotwire Native:

```kotlin
import dev.purchasekit.android.PaywallComponent

Hotwire.registerBridgeComponents(
    BridgeComponentFactory("paywall", ::PaywallComponent)
)

Hotwire.config.jsonConverter = KotlinXJsonConverter()
```

The component automatically acknowledges purchases after a successful transaction. PurchaseKit handles fulfillment via webhooks.

## Web setup

Use the [purchasekit gem](https://github.com/purchasekit/purchasekit) to set up the web-side bridge component. It provides:

- Rails helper to render a paywall
- SDK to interact with the PurchaseKit dashboard
- Automatic message handling with the native component
- Optional Pay gem integration for automatic `Pay::Subscription` creation

## Bridge component

The `paywall` bridge component handles the following messages from the web:

| Message | Description |
|---------|-------------|
| `prices` | Returns localized prices for requested product IDs |
| `purchase` | Initiates Google Play purchase flow with `googleStoreProductId` and `correlationId` |

### Prices

Request:
```json
{ "products": [{ "googleStoreProductId": "monthly" }, { "googleStoreProductId": "yearly" }] }
```

Response:
```json
{
  "prices": { "monthly": "$9.99", "yearly": "$99.99" },
  "environment": "sandbox"
}
```

Environment is `sandbox` (debug builds) or `production` (release builds).

### Purchase

Request:
```json
{ "googleStoreProductId": "monthly", "correlationId": "uuid" }
```

Response:
```json
{ "status": "success" }
```

Status values: `success`, `pending`, `cancelled`, `error`

## Requirements

- Android API 26+
- Kotlin 2.0+
- Hotwire Native Android 1.2.0+

## Testing

### License testers

Add test accounts in Play Console → Setup → License testing. License testers can make purchases without being charged.

### Internal testing track

For full end-to-end testing:

1. Create an internal testing track in Play Console
2. Upload your APK or AAB
3. Add testers and share the opt-in link
4. Testers install via the Play Store

## Releasing

```bash
bin/release 1.2.0
```

This bumps the version in `purchasekit/src/main/java/dev/purchasekit/android/Version.kt`, commits, tags, and pushes. JitPack picks up the new version automatically from the git tag.
