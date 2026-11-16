import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Release signing reads keystore.properties when it exists. It is git-ignored: the key belongs in CI
// secrets or in a keystore kept outside the repository, never in the tree. Without it the release
// build is simply unsigned, which is what CI uses to exercise R8.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.dga.montra"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dga.montra"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 runs on every release build, including the unsigned one in CI, so a shrinking
            // regression is a red build rather than a crash in a shipped APK. The rules live in
            // proguard-rules.pro; RELEASE.md explains what is kept and why.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Layouts are inflated through generated binding classes: a wrong id or a wrong widget type
        // is a compile error instead of a null at runtime. The token linters still read the XML.
        viewBinding = true
        buildConfig = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // viewModelScope and the stateIn/WhileSubscribed pattern the screens now use.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")

    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Ads. Debug builds serve Google's test unit IDs; see ads/AdConfig.kt.
    implementation("com.google.android.gms:play-services-ads:24.6.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    // JUnit 5 rather than 6: the platform 2.x line is not certified against Gradle 8.11, and these
    // tests use nothing that 5.x lacks.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Without this a failing suite prints one line; with it the class and method are in the log.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
