import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val reportBaseUrl = System.getenv("APCIDA_REPORT_BASE_URL")
    ?: localProperties.getProperty("apcida.reportBaseUrl")
    ?: "https://apcida-server.onrender.com/api/"
val reportApiKey = System.getenv("APCIDA_REPORT_API_KEY")
    ?: localProperties.getProperty("apcida.reportApiKey")
    ?: ""
val communityApiKey = System.getenv("APCIDA_COMMUNITY_API_KEY")
    ?: localProperties.getProperty("apcida.communityApiKey")
    ?: ""
// A public DNS identity, never a credential. Configured only for local debug pilot builds.
val pilotApprovedHost = localProperties.getProperty("apcida.pilotApprovedHost") ?: ""
// Empty until a separately approved stable HTTPS identity is provisioned. This is not a secret.
val productionApprovedHost = localProperties.getProperty("apcida.productionApprovedHost") ?: ""

android {
    namespace = "com.apcida.smishingdetector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apcida.smishingdetector"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REPORT_BASE_URL", buildConfigString(reportBaseUrl))
        buildConfigField("String", "REPORT_API_KEY", buildConfigString(reportApiKey))
        buildConfigField("String", "COMMUNITY_API_KEY", buildConfigString(communityApiKey))
        buildConfigField("String", "PRODUCTION_APPROVED_HOST", buildConfigString(productionApprovedHost))
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-eval"
            buildConfigField("String", "PILOT_APPROVED_HOST", buildConfigString(pilotApprovedHost))
        }
        release {
            buildConfigField("String", "PILOT_APPROVED_HOST", buildConfigString(""))
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true  // enables ViewBinding for XML layouts
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)

    // Navigation Component
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // MediaPipe LLM Inference
    debugImplementation(libs.mediapipe.tasks.genai)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Persistent SMS analysis queue
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
