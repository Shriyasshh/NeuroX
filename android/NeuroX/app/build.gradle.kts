plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt"); id("org.jetbrains.kotlin.plugin.compose") }

android { namespace = "org.neurox.patient"; compileSdk = 35
    defaultConfig {
        applicationId = "org.neurox.patient"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug { buildConfigField("String", "DEFAULT_API_URL", "\"http://10.0.2.2:8000/\"") }
    release {
        buildConfigField("String", "DEFAULT_API_URL", "\"\"")
        val signingStoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
        val signingStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val signingKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
        val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        val signingConfigured = listOf(
            signingStoreFile,
            signingStorePassword,
            signingKeyAlias,
            signingKeyPassword,
        ).all { !it.isNullOrBlank() }
        if (signingConfigured) {
            signingConfig = signingConfigs.create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { animationsDisabled = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Phase 8 — Compose UI tests
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Scan the resolved release graph, including transitive Maven dependencies.
tasks.register("exportReleaseDependencies") {
    val report = layout.buildDirectory.file("reports/release-dependencies.json")
    outputs.file(report)
    doLast {
        val packages = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents.mapNotNull { component ->
                val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                id?.let { mapOf("name" to "${it.group}:${it.module}", "version" to it.version) }
            }.sortedBy { it["name"] }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.toJson(packages))
        }
    }
}
