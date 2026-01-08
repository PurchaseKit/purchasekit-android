import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    `maven-publish`
}

android {
    namespace = "dev.purchasekit.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 28

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

//noinspection UseTomlInstead
dependencies {
    implementation("dev.hotwire:core:1.2.4")
    implementation("dev.hotwire:navigation-fragments:1.2.4")

    implementation("com.android.billingclient:billing-ktx:8.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.purchasekit"
            artifactId = "purchasekit"
            version = "0.3.3"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
