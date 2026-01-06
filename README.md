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

Use the [purchasekit gem](https://github.com/purchasekit/purchasekit) to add the paywall to your Rails app. The gem handles all communication with the native component automatically.

## Requirements

- Android API 28+
- Kotlin 2.0+
- Hotwire Native Android 1.2.0+

## Releasing

```bash
bin/release 1.2.0
```

This bumps the version in `purchasekit/src/main/java/dev/purchasekit/android/Version.kt`, commits, tags, and pushes. JitPack picks up the new version automatically from the git tag.
