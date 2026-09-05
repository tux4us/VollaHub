import org.jetbrains.kotlin.gradle.dsl.JvmTarget   // Diese Zeile bleibt oben
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

// Signing-Konfiguration wird aus keystore.properties gelesen (nicht versioniert,
// siehe .gitignore). Datei existiert nur lokal bzw. wird in CI aus Secrets erzeugt.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasSigningConfig = keystorePropertiesFile.exists()
if (hasSigningConfig) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.volla.hub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.volla.hub"
        minSdk = 24
        targetSdk = 36
        versionCode = 23
        versionName = "6.4"
    }

    signingConfigs {
        create("release") {
            if (hasSigningConfig) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ohne keystore.properties (z.B. frischer Klon eines Mitwirkenden)
            // bleibt der Release-Build unsigned buildbar, statt den Build hart
            // abzubrechen. Ein unsignierter Release kann nicht installiert werden,
            // aber Kompilierung/Tests funktionieren weiterhin.
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx.v1171)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.swipe.refresh.layout)
    //implementation(libs.markwon.core)
    implementation(libs.jsoup)
    implementation(libs.androidx.work)
    implementation(libs.osmdroid.android)
    implementation(libs.gson)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.rules)
}