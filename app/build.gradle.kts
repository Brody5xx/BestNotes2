plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número de construcción: lo pone GitHub Actions solo (1, 2, 3...)
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.luisell.bestnotes2"
    compileSdk = 35

    defaultConfig {
        // Nombre interno distinto al de la app vieja: así las dos se instalan una al lado de la otra
        applicationId = "com.luisell.bestnotes2"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.0 (build $buildNumber)"
    }

    // Firma fija: así cada APK nuevo se instala ENCIMA del anterior sin borrar nada
    signingConfigs {
        create("fija") {
            storeFile = rootProject.file("app-keystore.jks")
            storePassword = "bestnotes2"
            keyAlias = "bestnotes2"
            keyPassword = "bestnotes2"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fija")
        }
        debug {
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Las pruebas de pantalla (Robolectric) se corren aquí antes de subir; en GitHub solo las rápidas
tasks.withType<Test>().configureEach {
    if (System.getenv("CI") != null) exclude("**/PantallaTest*")
}

dependencies {
    // Lista de notas rápida (solo dibuja las tarjetas que se ven)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Abrir fotos, audios y videos con otras apps
    implementation("androidx.core:core:1.13.1")
    // 🎬 "Achicar" videos (para que pesen menos al mandarlos)
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
}
