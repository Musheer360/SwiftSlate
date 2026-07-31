plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val baseVersion = "1.0"

android {
    namespace = "com.musheer360.swiftslate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.musheer360.swiftslate"
        minSdk = 23
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "$baseVersion-dev"

        // Ship exactly the locales that exist in res/, and nothing else.
        //
        // Derived from the directory listing on purpose. The previous hand-written
        // `resourceConfigurations` allow-list of 7 locales silently discarded the 43
        // translations contributed in #104 — they sat in the repo, were kept green by CI lint,
        // and never reached a single user, while issue #100 was answered with "Done — Italian
        // added". Deriving the list means a new values-<locale>/ ships the moment it lands, so
        // that failure mode cannot come back.
        //
        // The filter is still worth having: without it AndroidX/Compose contributes its own
        // strings for ~30 further locales that SwiftSlate does not translate, for ~64KB of APK
        // in languages the app cannot actually speak.
        //
        // Deliberately NOT translated (removed in this commit): bn, gu, kn, ml, mr, pa, ta, te,
        // ur, fil. English is an official language in India, Pakistan and the Philippines and is
        // the default for technology there, so native-language phone UI is the exception rather
        // than the norm — especially among users who sideload an APK and paste an API key from
        // an English-only provider console. Hindi is kept as the one Indian language with broad
        // native-UI adoption. Re-add a directory and it ships automatically.
        val locales = file("src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-night" }
            .map { it.name.removePrefix("values-") }
        androidResources.localeFilters += (listOf("en") + locales)
    }

    androidResources {
        // Generates <locale-config> from the locales in res/ and references it from the
        // manifest, which is what lets Android 13+ users pick a per-app language in
        // Settings > Apps > SwiftSlate > Language. Without it a heavily localized app is
        // still stuck following the system language.
        generateLocaleConfig = true
    }

    signingConfigs {
        val ksFile = System.getenv("KEYSTORE_FILE")
        val ksPassword = System.getenv("KEYSTORE_PASSWORD")
        val ksAlias = System.getenv("KEY_ALIAS")
        val ksKeyPassword = System.getenv("KEY_PASSWORD")
        if (ksFile != null && ksPassword != null && ksAlias != null && ksKeyPassword != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Installable side by side with a stable release: a different applicationId means
        // Android treats it as a separate app, so testing a pull request never touches the
        // installed stable build, its API keys, its commands or its accessibility setting.
        //
        // Shrunk and non-debuggable like release (a debuggable accessibility service is not
        // something to hand out), but signed with the local debug key so pull requests from
        // forks can build it without access to the release signing secrets.
        //
        // The label and icon are overridden in src/preview/res so the two are told apart on
        // the launcher and in Settings > Accessibility.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/com.android.tools/**"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.work:work-runtime:2.11.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
}
