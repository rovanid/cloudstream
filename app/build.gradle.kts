import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

abstract class GenerateGitHashTask : DefaultTask() {

    @get:InputDirectory
    @get:org.gradle.api.tasks.PathSensitive(
        org.gradle.api.tasks.PathSensitivity.RELATIVE
    )
    abstract val repositoryDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val repository = repositoryDir.get().asFile

        val hash = try {
            val process = ProcessBuilder(
                "git",
                "rev-parse",
                "--short=7",
                "HEAD"
            )
                .directory(repository)
                .redirectErrorStream(true)
                .start()

            val result = process.inputStream
                .bufferedReader()
                .readText()
                .trim()

            val exitCode = process.waitFor()

            if (
                exitCode == 0 &&
                result.matches(Regex("[0-9a-fA-F]{7}"))
            ) {
                result
            } else {
                throw GradleException(
                    "Gagal membaca Git commit hash: $result"
                )
            }
        } catch (e: Exception) {
            throw GradleException(
                "Gagal mengambil Git commit hash",
                e
            )
        }

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)

        logger.lifecycle("Git commit hash untuk APK: $hash")
    }
}

val generateGitHash =
    tasks.register<GenerateGitHashTask>("generateGitHash") {
        repositoryDir.set(layout.projectDirectory.dir(".."))
        outputDir.set(
            layout.buildDirectory.dir("generated/git")
        )
    }

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Disable dependency metadata in APK and Android App Bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash,
                GenerateGitHashTask::outputDir
            )
        }
    }

    signingConfigs {
        if (System.getenv("SIGNING_KEY_ALIAS") != null) {
            create("prerelease") {
                val tmpFilePath =
                    System.getProperty("user.home") +
                        "/work/_temp/keystore/"

                val prereleaseStoreFile: File? =
                    File(tmpFilePath).listFiles()?.first()

                storeFile = prereleaseStoreFile?.let { file(it) }
                storePassword =
                    System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword =
                    System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.rovanid.cloudstreamvip"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        manifestPlaceholders["target_sdk_version"] =
            libs.versions.targetSdk.get()

        val localProperties =
            gradleLocalProperties(rootDir, project.providers)

        buildConfigField(
            "long",
            "BUILD_DATE",
            "${System.currentTimeMillis()}"
        )

        buildConfigField(
            "String",
            "SIMKL_CLIENT_ID",
            "\"" +
                (
                    System.getenv("SIMKL_CLIENT_ID")
                        ?: localProperties["simkl.id"]
                ) +
                "\""
        )

        buildConfigField(
            "String",
            "SIMKL_CLIENT_SECRET",
            "\"" +
                (
                    System.getenv("SIMKL_CLIENT_SECRET")
                        ?: localProperties["simkl.secret"]
                ) +
                "\""
        )

        buildConfigField(
            "String",
            "MAL_KEY",
            "\"" +
                (
                    System.getenv("MAL_KEY")
                        ?: localProperties["mal.key"]
                ) +
                "\""
        )

        buildConfigField(
            "String",
            "ANILIST_KEY",
            "\"" +
                (
                    System.getenv("ANILIST_KEY")
                        ?: localProperties["anilist.key"]
                ) +
                "\""
        )

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }

        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("state")

    productFlavors {
        create("stable") {
            dimension = "state"
        }

        create("prerelease") {
            dimension = "state"
            applicationIdSuffix = ".prerelease"

            if (signingConfigs.names.contains("prerelease")) {
                signingConfig =
                    signingConfigs.getByName("prerelease")
            } else {
                logger.warn("No prerelease signing config!")
            }

            versionNameSuffix = "-PRE"
            versionCode =
                (System.currentTimeMillis() / 60000).toInt()
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility =
            JavaVersion.toVersion(javaTarget.target)

        targetCompatibility =
            JavaVersion.toVersion(javaTarget.target)
    }

    java {
        toolchain {
            languageVersion.set(
                JavaLanguageVersion.of(
                    libs.versions.jdkToolchain.get()
                )
            )
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    namespace = "com.lagradost.cloudstream3"
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)

    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    // Android Core & Lifecycle
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json)

    // Design & UI
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Coil Image Loading
    implementation(libs.bundles.coil)

    // Media 3 (ExoPlayer)
    implementation(libs.bundles.media3)
    implementation(libs.video)

    // FFmpeg Decoding
    implementation(libs.bundles.nextlib)

    // Anime database
    implementation(libs.anime.db)

    // Playback
    implementation(libs.colorpicker)
    implementation(libs.newpipeextractor)
    implementation(libs.juniversalchardet)

    // Extensions & Other Libraries
    implementation(libs.jsoup)
    implementation(libs.ksoup)
    implementation(libs.rhino)
    implementation(libs.safefile)

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    implementation(libs.conscrypt.android)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.zipline)

    implementation(
        "com.google.code.gson:gson:2.11.0"
    )

    implementation(
        "me.xdrop:fuzzywuzzy:1.4.0"
    )

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp)

    implementation(libs.bundles.compose)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.io.core)

    implementation(project(":library"))
    implementation(project(":shared"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")

    from(
        android.sourceSets.getByName("main").java.directories
    )
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")

    from(
        "build/intermediates/compile_app_classes_jar/prereleaseDebug/bundlePrereleaseDebugClassesToCompileJar",
        "../library/build/libs"
    )

    into("build/app-classes")

    include("classes.jar", "library-jvm*.jar")

    rename("library-jvm.*.jar", "library-jvm.jar")
}

tasks.register<Jar>("makeJar") {
    duplicatesStrategy = DuplicatesStrategy.FAIL

    dependsOn(tasks.getByName("copyJar"))

    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )

    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName.set("classes")
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
        jvmDefault.set(JvmDefaultMode.ENABLE)

        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
        )
    }
}

dokka {
    moduleName = "App"

    dokkaSourceSets {
        configureEach {
            suppress = name != "prereleaseDebug"
            analysisPlatform = KotlinPlatform.JVM
            displayName = "JVM"

            documentedVisibilities(
                VisibilityModifier.Public,
                VisibilityModifier.Protected
            )

            sourceLink {
                localDirectory = file("..")

                remoteUrl(
                    "https://github.com/recloudstream/cloudstream/tree/master"
                )

                remoteLineSuffix = "#L"
            }
        }
    }
}
