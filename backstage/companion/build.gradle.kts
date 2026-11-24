import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val coreSources = tasks.register<Sync>("syncAccessibilityCore") {
    from("../android/src/main/kotlin") {
        include("com/eggman/backstage/a11y/AccessibilityBackend.kt")
        include("com/eggman/backstage/a11y/LocalAccessibilityBackend.kt")
        include("com/eggman/backstage/exec/ScreenModel.kt")
        include("com/eggman/backstage/privileged/ShellUserService.kt")
        include("com/eggman/backstage/privileged/PrivilegeProviders.kt")
        include("com/eggman/backstage/omp/OmpPayload.kt")
    }
    into(layout.buildDirectory.dir("generated/accessibilityCore"))
}

val versionProperties = Properties().apply {
    file("../version.properties").inputStream().use { load(it) }
}
val backstageVersion = providers.gradleProperty("backstage.version")
    .orElse(versionProperties.getProperty("version")).get()
val versionParts = backstageVersion.split('.', '-', '+').take(3).map { it.toIntOrNull() ?: 0 }
val upstreamBuildCode = file("../../upstream/dextop/pubspec.yaml").useLines { lines ->
    lines.first { it.startsWith("version:") }.substringAfterLast('+').trim().toInt()
}

android {
    namespace = "com.eggman.backstage.companion"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.eggman.backstage.agent"
        minSdk = 33
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
        versionCode = ((versionParts.getOrElse(0) { 0 } * 100 + versionParts.getOrElse(1) { 0 }) * 100 +
            versionParts.getOrElse(2) { 0 }) * 1000 + upstreamBuildCode % 1000 + 1
        versionName = backstageVersion
    }
    sourceSets["main"].apply {
        java.srcDir(coreSources.map { it.destinationDir })
        java.srcDir("../companion-protocol/kotlin")
        aidl.srcDir("../companion-protocol/aidl")
        aidl.srcDir("../privileged-aidl")
        assets.srcDir("../../build/omp-payload/assets")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    val signingKeys = listOf("BACKSTAGE_KEYSTORE", "BACKSTAGE_KEYSTORE_PASSWORD", "BACKSTAGE_KEY_ALIAS", "BACKSTAGE_KEY_PASSWORD")
    val supplied = signingKeys.count { !System.getenv(it).isNullOrBlank() }
    // Configuration also runs during a GitHub main-app debug build. A partial release-key environment must not
    // break that unrelated build; only this module's release signing task rejects it (the script checks it too).
    tasks.matching { it.name == "validateSigningRelease" }.configureEach {
        // Environment completeness is not one of AGP's own inputs. Without this, an earlier valid signing check
        // could make the task UP-TO-DATE and skip our guard after only some key variables are supplied.
        inputs.property("backstageSigningSuppliedValues", supplied)
        doFirst {
            require(supplied == 0 || supplied == signingKeys.size) { "Backstage Agent: supply all four BACKSTAGE_* signing values or none" }
        }
    }
    val releaseKey = if (supplied == signingKeys.size) signingConfigs.create("backstageRelease") {
        storeFile = file(System.getenv("BACKSTAGE_KEYSTORE"))
        storePassword = System.getenv("BACKSTAGE_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("BACKSTAGE_KEY_ALIAS")
        keyPassword = System.getenv("BACKSTAGE_KEY_PASSWORD")
        enableV2Signing = true
        enableV3Signing = true
    } else signingConfigs.getByName("debug")
    buildTypes["release"].apply {
        signingConfig = releaseKey
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    lint { abortOnError = true; disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild").configure {
    dependsOn(coreSources)
    doFirst { require(file("../../build/omp-payload/assets/omp/manifest.json").isFile) { "Backstage Agent: OMP payload missing; run scripts/fetch-omp-payload.sh" } }
}

// Include the notices for the bundled shell runtime and the native proot executables.
val licenseAssets = tasks.register<Sync>("companionLicenseAssets") {
    from("../../LICENSE", "../../licenses/texts/Apache-2.0.txt", "../../licenses/backstage-agent.txt")
    from("../../upstream/dextop/third_party/proot/COPYING") { rename { "proot-COPYING.txt" } }
    from("../../upstream/dextop/third_party/talloc/COPYING") { rename { "talloc-COPYING.txt" } }
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}
android.sourceSets["main"].assets.srcDir(licenseAssets.map { it.destinationDir })
tasks.named("preBuild").configure { dependsOn(licenseAssets) }

val robolectricAndroidAll: Configuration by configurations.creating { isCanBeConsumed = false; isTransitive = false }
tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=" + robolectricAndroidAll.singleFile.parentFile.absolutePath)
    })
}
dependencies {
    implementation("com.google.android.material:material:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    robolectricAndroidAll("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
