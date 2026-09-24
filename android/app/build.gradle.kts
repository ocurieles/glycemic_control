plugins {
    alias(libs.plugins.android.application) // AGP 9+: Kotlin ya viene integrado, sin plugin aparte.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

/**
 * `versionCode` automático (docs/08 F8): la cantidad de commits en la rama actual, que
 * es monótona creciente mientras el historial sea lineal (nunca baja al hacer commits
 * nuevos). Si no hay git disponible (p. ej. un checkout sin `.git`), cae a 1.
 */
fun gitCommitCount(): Int =
    try {
        val out = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get()
        out.trim().toInt()
    } catch (e: Exception) {
        logger.warn("No se pudo calcular versionCode desde git ({}); se usa 1.", e.message)
        1
    }

/**
 * Firma de release (docs/08 F8, docs/10 "APK"): el keystore vive **fuera del repo**.
 * Estas propiedades se leen de `~/.gradle/gradle.properties` (nunca de
 * `android/gradle.properties`, que sí está en git) o de variables de entorno
 * `ORG_GRADLE_PROJECT_*` / `-P` en la línea de comandos — nunca de un archivo del
 * proyecto. Si faltan, `release` queda sin firmar (assembleDebug/lint/test siguen
 * funcionando igual; solo `assembleRelease` no produce un APK instalable).
 */
fun gradleProp(name: String): String? = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = gradleProp("CHECKIN_RELEASE_STORE_FILE")
val releaseStorePassword = gradleProp("CHECKIN_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = gradleProp("CHECKIN_RELEASE_KEY_ALIAS")
val releaseKeyPassword = gradleProp("CHECKIN_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

if (!hasReleaseSigning) {
    logger.warn(
        "Firma de release no configurada: falta CHECKIN_RELEASE_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD " +
            "en ~/.gradle/gradle.properties (ver docs/10-despliegue.md). assembleRelease producirá un APK sin firmar.",
    )
}

android {
    namespace = "com.ingeint.checkin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ingeint.checkin"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount()
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // docs/02, docs/06: en debug la app apunta a los emuladores de Firebase por
            // defecto. Para probar contra el proyecto real (p. ej. fuera de la red de casa,
            // sin depender de esta Mac) sin necesitar el keystore de release todavía, pasar
            // `-PCHECKIN_USE_EMULATORS=false` — sigue siendo un APK debug-signed, se instala
            // igual con `adb install`/`installDebug`, pero habla con Firebase real.
            buildConfigField("boolean", "USE_EMULATORS", (gradleProp("CHECKIN_USE_EMULATORS") ?: "true"))
            // Host de los emuladores: "127.0.0.1" (con `adb reverse`) de forma normal, pero
            // eso solo sirve un teléfono a la vez por USB (rompe el túnel del otro al
            // desconectarlo). Para probar dos teléfonos reales al mismo tiempo, ambos en la
            // misma Wi-Fi que esta máquina, pasar `-PCHECKIN_EMULATOR_HOST=<IP de la Mac>`.
            buildConfigField("String", "EMULATOR_HOST", "\"${gradleProp("CHECKIN_EMULATOR_HOST") ?: "127.0.0.1"}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "USE_EMULATORS", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // Mismo contrato de vectores que usa el backend (docs/09, regla 7 de CLAUDE.md):
        // ReminderScheduleTest lee docs/schedule-vectors.json como recurso de classpath.
        getByName("test") {
            resources.srcDir("../../docs")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    // org.json real (no el stub de android.jar que lanza "not mocked" en tests JVM puros).
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
