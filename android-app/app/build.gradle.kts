import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.firebase.appdistribution")
    id("com.google.gms.google-services")
}

val localProps = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}
val openAiApiKey = (localProps.getProperty("OPENAI_API_KEY") ?: "").replace("\"", "\\\"")
val testCohort = (localProps.getProperty("TEST_COHORT") ?: "manual-apk").replace("\"", "\\\"")
val firebaseAppId = localProps.getProperty("FIREBASE_APP_ID")?.trim().orEmpty()
val firebaseGroups = localProps.getProperty("FIREBASE_APP_DIST_GROUPS")?.trim().orEmpty()
val firebaseCredentialsFile = localProps.getProperty("FIREBASE_APP_DIST_SERVICE_CREDENTIALS_FILE")?.trim().orEmpty()
val firebaseTestersFile = rootProject.file(
    localProps.getProperty("FIREBASE_APP_DIST_TESTERS_FILE")
        ?.takeIf { it.isNotBlank() }
        ?: "firebase-appdistribution/testers.txt"
)
val debugReleaseNotesFile = rootProject.file("firebase-appdistribution/release-notes-debug.txt")
val releaseReleaseNotesFile = rootProject.file("firebase-appdistribution/release-notes-release.txt")

android {
    namespace = "com.kidwatch.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kidwatch.monitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "TEST_COHORT", "\"$testCohort\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

fun com.google.firebase.appdistribution.gradle.AppDistributionExtension.applyKidWatchDefaults(
    artifactTypeValue: String,
    notesFile: java.io.File
) {
    artifactType = artifactTypeValue
    if (firebaseAppId.isNotBlank()) {
        appId = firebaseAppId
    }
    if (notesFile.exists()) {
        releaseNotesFile = notesFile.path
    }
    if (firebaseGroups.isNotBlank()) {
        groups = firebaseGroups
    }
    if (firebaseTestersFile.exists()) {
        testersFile = firebaseTestersFile.path
    }
    if (firebaseCredentialsFile.isNotBlank()) {
        serviceCredentialsFile = firebaseCredentialsFile
    }
}

android.buildTypes.named("debug").configure {
    extensions.configure<com.google.firebase.appdistribution.gradle.AppDistributionExtension>("firebaseAppDistribution") {
        applyKidWatchDefaults(
            artifactTypeValue = "APK",
            notesFile = debugReleaseNotesFile
        )
    }
}

android.buildTypes.named("release").configure {
    extensions.configure<com.google.firebase.appdistribution.gradle.AppDistributionExtension>("firebaseAppDistribution") {
        applyKidWatchDefaults(
            artifactTypeValue = "APK",
            notesFile = releaseReleaseNotesFile
        )
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("com.google.mlkit:face-detection:16.1.6")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
