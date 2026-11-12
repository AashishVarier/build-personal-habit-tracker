import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing reads from a git-ignored keystore.properties file or from environment
// variables. Nothing secret is ever stored in this repository.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envKey).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("storeFile", "HABIT_TRACKER_STORE_FILE")

android {
    namespace = "com.variableduck.build.habittracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.variableduck.build.habittracker"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "HABIT_TRACKER_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "HABIT_TRACKER_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "HABIT_TRACKER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric's Android 16 runtime reaches into FileDescriptor internals on JDK 17+.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

/** The app is offline only: fail the build if any library merges in the INTERNET permission. */
abstract class VerifyNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile.readText()
        val internet = Regex("""<uses-permission[^>]*android\.permission\.INTERNET["/]""")
        if (internet.containsMatchIn(manifest)) {
            throw GradleException("The merged manifest requests android.permission.INTERNET; this app must stay offline.")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyNoInternetPermission>("verify${variantName}NoInternetPermission") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check") { dependsOn(verify) }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(verify) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
