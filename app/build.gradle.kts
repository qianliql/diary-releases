import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val signing = Properties().apply { rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "io.github.qianliql.diary"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.qianliql.diary"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0"
        resValue("string", "app_name", "日记")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += (providers.gradleProperty("targetAbi").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64")) }
    }
    if (signing.isNotEmpty()) signingConfigs.create("release") {
        storeFile = file(signing.getProperty("storeFile")); storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias"); keyPassword = signing.getProperty("keyPassword")
    }
    buildTypes {
        debug { applicationIdSuffix = ".qa"; resValue("string", "app_name", "日记·测试") }
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files("libs/sherpa-onnx.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
