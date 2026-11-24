plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Robolectric 4.14.1 uses API 28 for FinanceBackupTest and API 35 for its app resources.
// Resolve each SDK separately so Gradle does not collapse the two versions of the same module.
val robolectricSdkJars = listOf("9-robolectric-4913185-2-i7", "15-robolectric-12650502-i7").map { version ->
    configurations.detachedConfiguration(dependencies.create("org.robolectric:android-all-instrumented:$version"))
        .apply { isTransitive = false }
}
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(robolectricSdkJars)
    into(robolectricSdkDirectory)
}

android {
    namespace = "com.dga.montra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dga.montra"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        it.useJUnitPlatform()
        it.dependsOn(prepareRobolectricSdks)
        it.inputs.files(prepareRobolectricSdks)
        it.systemProperty("robolectric.dependency.dir", robolectricSdkDirectory.get().asFile.absolutePath)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.core:core:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
