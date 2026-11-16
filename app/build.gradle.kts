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
        // 36 since Android 16 made this the Play requirement, and it changes behaviour rather than just
        // a number: edge-to-edge is enforced with no opt-out, and onBackPressed stops being called.
        // AppActivity already draws edge-to-edge and nothing in this app intercepts back, so the
        // consequences are handled in code rather than deferred. See RELEASE.md.
        targetSdk = 36
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

    // Android Lint runs on every push (see .github/workflows/android.yml). It is the only check here
    // that understands Android itself - API-level violations, missing accessibility labels,
    // unused resources, manifest problems - and until this phase it had never been executed on this
    // codebase, so its first run is expected to find real defects.
    //
    // There is no lint-baseline.xml on purpose. A baseline records today's findings and then hides
    // them, along with every later occurrence of the same issue: the errors below would become a
    // green build with no fix. Findings get fixed or suppressed one at a time, with a reason.
    lint {
        // Errors fail the build, so a new one cannot land. Warnings are annotated by
        // tools/annotate_lint.py and are still fixed rather than ignored - they just do not block.
        abortOnError = true
        warningsAsErrors = false
        // Room and view binding emit generated sources; linting them reports their code as ours.
        checkGeneratedSources = false
        // The ad and UMP SDKs are prebuilt AARs - their lint findings are not actionable here.
        checkDependencies = false
        xmlReport = true
        htmlReport = true
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
    // android.media.ExifInterface cannot read HEIF/AVIF orientation and its behaviour differs across
    // OEM builds; the androidx class reads what the picker actually hands over. Same API shape.
    implementation("androidx.exifinterface:exifinterface:1.4.2")
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
