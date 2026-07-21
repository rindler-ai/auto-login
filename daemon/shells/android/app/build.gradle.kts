plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.rindler.autologin"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.rindler.autologin"
        minSdk = 26
        targetSdk = 34
        // versionName stays 0.1.0 deliberately while the app is pre-release: nobody
        // is running it, so the number tracks "not shipped yet" rather than counting
        // up through fixes. versionCode still increments — the release gate requires
        // it to be strictly monotonic, and 17 was the email-OTP-linking predecessor.
        // 23 -> 24: re-cut of v0.1.0 with host-neutral endpoints. AUTHORIZE_URL and
        // PRIVACY_POLICY_URL were realigned to the product's API host / apex domain
        // (CATALOG_URL/HUB_URL already matched), tracking the current server routes.
        // Real hosts are never committed here; they are injected at build time from
        // repo Variables (the hostname-guard + gitleaks block a committed host).
        // 24 -> 25: re-cut of v0.1.0 sending the device fingerprint (ANDROID_ID) at pair
        // so the server dedups a re-pair (reinstall/sign-out) to one device row (#4564).
        versionCode = 25
        versionName = "0.1.0"

        // Backend URLs are build params. The DEFAULTS here are PROD (a plain
        // release APK ships against prod); the `debug` build type below points at
        // dev, so `./gradlew assembleDebug` targets dev with NO flags. Either can
        // still be overridden explicitly:
        //   ./gradlew assembleDebug -PhubUrl=wss://<host>/v1/devices/connect \
        //                           -PcatalogUrl=https://<host>/api/configs/catalog
        // IMPORTANT: HUB_URL must point at the SAME hub server that mints the
        // pairing token (your hub's backend), or every pairing code 401s
        // "invalid" — the token exists on one server and is redeemed against another.
        val hub = (project.findProperty("hubUrl") as String?)
            ?: "wss://your-hub.example/v1/devices/connect"
        buildConfigField("String", "HUB_URL", "\"$hub\"")
        val catalog = (project.findProperty("catalogUrl") as String?)
            ?: "https://your-hub.example/api/configs/catalog"
        buildConfigField("String", "CATALOG_URL", "\"$catalog\"")
        val privacy = (project.findProperty("privacyPolicyUrl") as String?)
            ?: "https://your-hub.example/privacy"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacy\"")
        // The web origin hosting /devices/authorize — the sign-in enrollment page the
        // app opens so a user pairs by signing in, not by typing a code. A self-host /
        // open-source build ships a placeholder; a branded build overrides it via
        // -PauthorizeUrl (or the debug default below).
        val authorize = (project.findProperty("authorizeUrl") as String?)
            ?: "https://your-hub.example"
        buildConfigField("String", "AUTHORIZE_URL", "\"$authorize\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            it.useTestNG()
        }
    }

    signingConfigs {
        // Release signing. A release-signed APK (v2/v3, non-debug certificate)
        // avoids the debug-certificate install warning that a plain `assembleDebug`
        // APK triggers on sideload. Supply a keystore via ~/.gradle/gradle.properties
        // or -P flags:
        //   RELEASE_STORE_FILE=/abs/path/release.jks   RELEASE_STORE_PASSWORD=…
        //   RELEASE_KEY_ALIAS=autologin                RELEASE_KEY_PASSWORD=…
        // Generate one: keytool -genkeypair -v -keystore release.jks -alias autologin \
        //   -keyalg RSA -keysize 2048 -validity 10000
        // Without these properties this config is inert (see the release buildType).
        create("release") {
            (project.findProperty("RELEASE_STORE_FILE") as String?)?.let { path ->
                storeFile = file(path)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds target DEV so a plain `assembleDebug` pairs against the
            // dev backend (the same server that mints tokens). A -P
            // override still wins; without one these dev hosts are used, NOT the
            // prod defaults in defaultConfig.
            val hub = (project.findProperty("hubUrl") as String?)
                ?: "wss://your-hub.example/v1/devices/connect"
            buildConfigField("String", "HUB_URL", "\"$hub\"")
            val catalog = (project.findProperty("catalogUrl") as String?)
                ?: "https://your-hub.example/api/configs/catalog"
            buildConfigField("String", "CATALOG_URL", "\"$catalog\"")
            val privacy = (project.findProperty("privacyPolicyUrl") as String?)
                ?: "https://your-hub.example/privacy"
            buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacy\"")
            val authorize = (project.findProperty("authorizeUrl") as String?)
                ?: "https://your-hub.example"
            buildConfigField("String", "AUTHORIZE_URL", "\"$authorize\"")
        }
        release {
            // Sign with the release keystore when one is supplied (see signingConfigs
            // above). A release-signed, non-debuggable APK is what removes the
            // debug-certificate install warning. If no keystore is provided the APK
            // is UNSIGNED and must be signed with `apksigner` before it will install.
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    // The shared Go custody core (built by `make android` -> libs/custody.aar).
    implementation(files("libs/custody.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // LifecycleEventEffect: re-check the RECEIVE_SMS grant when Settings resumes, so the
    // toggle can never claim "On" after Android auto-revokes the permission from an
    // unused daemon (or the user revokes it in system Settings).
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Chrome Custom Tabs for sign-in enrollment: open the hub's /devices/authorize
    // page in a trusted in-app browser tab so the user signs in with their account and
    // the device auto-pairs (no code to copy).
    implementation("androidx.browser:browser:1.8.0")

    // SMS auto-read needs no third-party lib: RECEIVE_SMS (granted at the Settings
    // toggle) + a manifest SMS_RECEIVED receiver (sms/SmsReceiver) read incoming
    // verification texts in the background. The play-services SMS User Consent API +
    // lifecycle-process were removed with the consent flow (reverted): they
    // prompted per text and needed a foreground Activity, so they never worked closed.

    // Native secure storage (the shell's job; Go never stores).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Async favicon/logo loading for site rows (Coil for Compose). Same favicon
    // favicon service, so a newly-added site shows its real logo.
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("org.testng:testng:7.10.2")
}
