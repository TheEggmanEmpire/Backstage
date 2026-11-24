//:backstage — the Backstage AI agent as an Android library.
// No plugin versions here: they come from the including build (Dextop or backstage/ standalone).
import java.net.URI

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eggman.backstage"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "PLAY_DISTRIBUTION", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "PLAY_DISTRIBUTION", "true")
            val reportEndpoint = providers.environmentVariable("BACKSTAGE_PLAY_REPORT_ENDPOINT").orElse("").get()
            if (reportEndpoint.isNotBlank()) {
                val endpoint = URI(reportEndpoint)
                require(endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null &&
                    endpoint.fragment == null && endpoint.query == null) {
                    "The Play report endpoint must be a public HTTPS URL"
                }
            }
            buildConfigField("String", "PLAY_REPORT_ENDPOINT", "\"$reportEndpoint\"")
        }
    }
    sourceSets["main"].aidl.srcDir("../privileged-aidl")
    sourceSets["play"].apply {
        java.srcDir("../companion-protocol/kotlin")
        aidl.srcDir("../companion-protocol/aidl")
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Gate (scripts/test.sh --only lint, CI): any lint error fails:backstage:lintDebug. There is no
        // baseline; a false positive is suppressed where it occurs (@SuppressLint / tools:ignore) with a reason.
        abortOnError = true
        // Dependency versions are pinned to upstream Dextop's; these checks only ask the network for newer
        // releases, so their result would depend on the day lint runs.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric tests (receivers, notifications, manifest checks) need the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric runs framework code on the JVM. Its android-all runtime jar is resolved by Gradle (same repositories
// as every other dependency) and handed over in offline mode, so test runs never download anything themselves.
val robolectricAndroidAll: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

tasks.withType<Test>().configureEach {
    val androidAll: FileCollection = robolectricAndroidAll
    inputs.files(androidAll).withPropertyName("robolectricAndroidAll").withPathSensitivity(PathSensitivity.NONE)
    maxHeapSize = "1536m"
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=" + androidAll.singleFile.parentFile.absolutePath)
    })
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// ------------------------------------------------------------------------------------------- legal notices
// The repository root: this module is backstage/android, both in the standalone build and inside Dextop's.
val repoRoot: File = projectDir.resolve("../..").canonicalFile

// Which commit is the Corresponding Source of this build (GPL-3.0 §6): -Pbackstage.sourceRevision (set by
// scripts/build-apk.sh) or `git rev-parse HEAD`, plus "-dirty" when the checkout differs from that commit.
val sourceRevision: String = (findProperty("backstage.sourceRevision")?.toString()?.takeIf { it.isNotBlank() }) ?: run {
    fun git(vararg args: String): String? = try {
        val run = providers.exec {
            commandLine(listOf("git", "-C", repoRoot.path) + args)
            isIgnoreExitValue = true
        }
        if (run.result.get().exitValue == 0) run.standardOutput.asText.get().trim() else null
    } catch (_: Exception) { null }
    val head = git("rev-parse", "HEAD") ?: return@run "unknown"
    val dirty = git("status", "--porcelain").orEmpty().isNotEmpty()
    if (dirty) "$head-dirty" else head
}
val sourceUrl = "https://github.com/TheEggmanEmpire/Backstage"

android.defaultConfig {
    buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
    buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
}

/**
 * assets/backstage/THIRD_PARTY_NOTICES.txt: NOTICE.md, the GPL-3.0 text, one entry per native library of the APK
 * (licenses/native-libraries.tsv) and every license/notice text those entries name, in full and once. Native code
 * is not covered by Flutter's NOTICES or the Maven POMs, and BSD/MIT/(L)GPL require these texts to travel with the
 * binary. scripts/lib/verify_apk.py fails an APK whose native libraries are not all listed in this asset.
 */
abstract class ThirdPartyNoticesTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val table: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val runtimeTable: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val texts: ConfigurableFileCollection
    @get:Internal abstract val root: DirectoryProperty
    @get:Input abstract val revision: Property<String>
    @get:Input abstract val url: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:OutputDirectory abstract val resourceDir: DirectoryProperty

    @TaskAction fun generate() {
        val rootDir = root.get().asFile
        val rows = table.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val cols = line.split('\t')
            if (cols.size != 4) throw GradleException("licenses/native-libraries.tsv: expected 4 tab-separated columns: $line")
            cols
        }
        val runtimeRows = runtimeTable.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val cols = line.split('\t')
            if (cols.size != 4) throw GradleException("licenses/omp-runtime.tsv: expected 4 tab-separated columns: $line")
            cols
        }
        val ordered = LinkedHashSet<String>()
        listOf("NOTICE.md", "LICENSE").forEach { ordered += it }
        (rows + runtimeRows).forEach { cols -> cols[3].split(' ').filter { it.isNotBlank() && it != "-" }.forEach { ordered += it } }
        val files = ordered.flatMap { rel ->
            val f = File(rootDir, rel)
            when {
                f.isDirectory -> f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
                f.isFile -> listOf(f)
                else -> throw GradleException("licenses/native-libraries.tsv names $rel, which does not exist")
            }
        }.distinct()
        val out = StringBuilder()
        out.append("Backstage - third-party and license notices\n")
        out.append("Copyright (C) 2026 TheEggmanEmpire. Backstage is a modified version of Dextop, Copyright (C) NarYuki ")
            .append("and the Dextop contributors. GPL-3.0-or-later.\n")
        out.append("Corresponding source: ${url.get()} at commit ${revision.get()}\n\n")
        out.append("==== Native libraries in this APK ====\n\n")
        for (cols in rows) {
            out.append("== ${cols[0]} ==\n").append("Component: ${cols[1]}\n").append("License: ${cols[2]}\n")
            val texts = cols[3].split(' ').filter { it.isNotBlank() && it != "-" }
            if (texts.isNotEmpty()) out.append("Texts: ${texts.joinToString(", ")}\n")
            out.append('\n')
        }
        out.append("==== OMP runtime (GitHub flavor only) ====\n\n")
        for (cols in runtimeRows) {
            out.append("== ${cols[0]} ==\n").append("Source: ${cols[1]}\n").append("License: ${cols[2]}\n\n")
        }
        out.append("==== Notices and license texts ====\n")
        for (f in files) {
            out.append("\n---- ${f.relativeTo(rootDir).invariantSeparatorsPath} ----\n\n")
            out.append(f.readText().trimEnd()).append('\n')
        }
        val target = outputDir.get().asFile.resolve("backstage/THIRD_PARTY_NOTICES.txt")
        target.parentFile.mkdirs()
        target.writeText(out.toString())
        val license = resourceDir.get().asFile.resolve("raw/backstage_license_gpl3.txt")
        license.parentFile.mkdirs()
        rootDir.resolve("LICENSE").copyTo(license, overwrite = true)
    }
}

val noticeTable = repoRoot.resolve("licenses/native-libraries.tsv")
val runtimeNoticeTable = repoRoot.resolve("licenses/omp-runtime.tsv")
val thirdPartyNotices = tasks.register<ThirdPartyNoticesTask>("generateThirdPartyNotices") {
    table.set(noticeTable)
    runtimeTable.set(runtimeNoticeTable)
    root.set(repoRoot)
    revision.set(sourceRevision)
    url.set(sourceUrl)
    texts.from(repoRoot.resolve("NOTICE.md"), repoRoot.resolve("LICENSE"))
    (noticeTable.readLines() + runtimeNoticeTable.readLines()).filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
        line.split('\t').getOrNull(3)?.split(' ')?.filter { it.isNotBlank() && it != "-" }?.forEach { texts.from(repoRoot.resolve(it)) }
    }
    outputDir.set(layout.buildDirectory.dir("generated/thirdPartyNotices"))
    resourceDir.set(layout.buildDirectory.dir("generated/licenseResources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(thirdPartyNotices, ThirdPartyNoticesTask::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(thirdPartyNotices, ThirdPartyNoticesTask::resourceDir)
    }
}

dependencies {
    // Aligned with upstream Dextop:app (material3 1.5.0-alpha18 → Compose 1.11 line).
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Same artifacts/versions as upstream:app (deduplicated in the merged app).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // on-device wake word (Apache-2.0; pulls JNA 5.18.x, Apache-2.0/LGPL dual). Models are downloaded at runtime.
    implementation("com.alphacephei:vosk-android:0.3.75")
    //  end

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Robolectric 4.16 with the Android 15 (API 35) runtime; tests pin sdk=35 in robolectric.properties.
    testImplementation("org.robolectric:robolectric:4.16")
    robolectricAndroidAll("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    // Robolectric suite (src/test/.../robolectric): AndroidX Test core + Compose UI tests on the JVM. The
    // ui-test-manifest declares the empty ComponentActivity that createComposeRule launches; it is a test-only
    // dependency, so it never reaches the library's (or Dextop's) manifest. Version = the Compose UI that
    // material3 1.5.0-alpha18 resolves to.
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0") // aligns ui-test's espresso with test core 1.7
    testImplementation("androidx.compose.ui:ui-test-junit4:1.11.0-beta02")
    testImplementation("androidx.compose.ui:ui-test-manifest:1.11.0-beta02")
}
