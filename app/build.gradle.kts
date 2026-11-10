import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.aboutlibraries)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val releaseStoreFilePath: String? = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("PIHOLE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? = keystoreProperties.getProperty("storePassword")
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("PIHOLE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = keystoreProperties.getProperty("keyAlias")
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("PIHOLE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = keystoreProperties.getProperty("keyPassword")
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("PIHOLE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasReleaseKeystore = releaseStoreFilePath != null &&
    rootProject.file(releaseStoreFilePath).exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.devadam.piholeviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devadam.pauseforpihole"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFilePath != null) {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.matching {
    it.name in setOf("packageRelease", "bundleRelease", "signReleaseBundle", "assembleRelease")
}.configureEach {
    doFirst {
        if (!hasReleaseKeystore) {
            throw GradleException(
                "Release keystore is not configured or missing. " +
                    "Provide storeFile, storePassword, keyAlias, and keyPassword in root keystore.properties " +
                    "(or via PIHOLE_KEYSTORE_* environment variables) before building a release APK or AAB."
            )
        }
    }
}



