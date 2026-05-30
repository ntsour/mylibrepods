import java.util.Properties

val appVersionName = "0.2.9"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
//    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
}

val props = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(props["RELEASE_STORE_FILE"] as String)
            storePassword = props["RELEASE_STORE_PASSWORD"] as String
            keyAlias = props["RELEASE_KEY_ALIAS"] as String
            keyPassword = props["RELEASE_KEY_PASSWORD"] as String
        }
    }
    namespace = "io.nikos.propods"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.nikos.propods.multipeer"
        minSdk = 33
        targetSdk = 37
        versionCode = 50
        versionName = appVersionName
        buildConfigField("String", "FLAVOR", "\"\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
            buildConfigField("Boolean", "PLAY_BUILD", "false")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            buildConfigField("Boolean", "PLAY_BUILD", "false")
            signingConfig = signingConfigs.getByName("release")
            versionNameSuffix = "-debug"
        }
        create("playRelease") {
            initWith(getByName("release"))
            buildConfigField("Boolean", "PLAY_BUILD", "true")
            versionNameSuffix = "-play"
        }
        create("playDebug") {
            initWith(getByName("debug"))
            buildConfigField("Boolean", "PLAY_BUILD", "true")
            versionNameSuffix = "-youshouldnothavethis"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            res.directories += "src/main/res-apple"
        }
    }

    ndkVersion = "30.0.14904198"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.annotations)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.billing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.aboutlibraries)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.backdrop)
//    implementation(libs.hilt)
//    implementation(libs.hilt.compiler)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    add("playReleaseImplementation", libs.billing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(kotlin("test"))
}

aboutLibraries {
    export {
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}

val rootModuleDir = rootProject.file("../root-module-manual")
val releaseDir = rootProject.file("../release")

fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

fun registerRootModuleZipTask(
    name: String,
    buildType: String
) = tasks.register<Zip>(name) {

    val variantTask = "assemble${cap(buildType)}"
    dependsOn(variantTask)

    val apkPath = "outputs/apk/$buildType/app-$buildType.apk"

    from(rootModuleDir)

    duplicatesStrategy = DuplicatesStrategy.WARN

    from(layout.buildDirectory.file(apkPath)) {
        into("system/priv-app/ProPods")
        rename { "ProPods.apk" }
    }

    delete(layout.buildDirectory.dir("outputs/rootModuleZips"))

    archiveFileName.set("ProPods-FOSS-v$appVersionName-$buildType.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/rootModuleZips"))
}

val zipRelease = registerRootModuleZipTask("zipReleaseModule", "release")

val zipDebug = registerRootModuleZipTask("zipDebugModule", "debug")

val collect = tasks.register<Copy>("collectReleaseArtifacts") {

    dependsOn(
        zipRelease,
        zipDebug,
        "bundlePlayRelease"
    )

    into(releaseDir)

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename(".*", "ProPods-FOSS-v$appVersionName-release.apk")
    }

    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename(".*", "ProPods-FOSS-v$appVersionName-debug.apk")
    }

    from(layout.buildDirectory.dir("outputs/bundle/playRelease")) {
        include("*.aab")
    }

    from(layout.buildDirectory.dir("outputs/rootModuleZips")) {
        include("*.zip")
    }
}

tasks.register("packageReleaseArtifacts") {
    dependsOn(collect)
}
