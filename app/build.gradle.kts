plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.nixi"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.nixi"
        minSdk = 26
        targetSdk = 34
        // versionCode trzymamy rosnąco, żeby telefon widział aktualizacje
        // (nie musimy jej podnosić przy każdym commicie — tylko przy wydaniu).
        versionCode = 10
        versionName = "1.3.5"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // Minify wyłączony celowo: pierwsze APK ma budować się pewnie.
            // Po stabilizacji można włączyć isMinifyEnabled + keep rules.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
    }

    testOptions {
        // testy jednostkowe nie potrzebują emulatora; nie wywalamy ich na
        // "not mocked" przy okazjonalnym dotknięciu klasy z android.jar
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// Logowanie testów: bez tego komunikat nieudanej asercji (np. statystyki
// detektora) nie trafia do logu CI i nie da się zdiagnozować, co zawiodło.
// Przydało się przy strojeniu wake-worda — zostawiamy na stałe.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
