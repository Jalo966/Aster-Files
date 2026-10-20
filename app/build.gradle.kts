plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aster.files"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.aster.files"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.jks")
            storePassword = "123456"
            keyAlias = "aster"
            keyPassword = "123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "SHOW_OPEN_SOURCE", "false")
            buildConfigField("boolean", "ENABLE_CRASH_LOG", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("standardDebug") {
            initWith(getByName("debug"))
            isDebuggable = true
            versionNameSuffix = "-standard-debug"
            buildConfigField("boolean", "SHOW_OPEN_SOURCE", "false")
            buildConfigField("boolean", "ENABLE_CRASH_LOG", "true")
            matchingFallbacks += "debug"
        }
        debug {
            // 包名保持 com.aster.files，与需求文档、FileProvider authority 一致；
            // 仅版本名追加 -debug，便于与正式包区分。
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "SHOW_OPEN_SOURCE", "true")
            buildConfigField("boolean", "ENABLE_CRASH_LOG", "true")
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("io.coil-kt:coil-gif:2.4.0")
    implementation("io.coil-kt:coil-video:2.4.0")
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-ui:1.1.1")
    implementation("androidx.media3:media3-session:1.1.1")
    implementation("com.github.penfeizhou.android.animation:apng:3.0.5")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.2")
    implementation("commons-net:commons-net:3.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.hierynomus:smbj:0.11.5")
    implementation("com.android.tools.build:apksig:8.1.0")
    implementation("androidx.compose.material:material:1.4.3")
    implementation("androidx.biometric:biometric:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android 单元测试使用真实 org.json 实现，避免 mockable android.jar 的方法桩。
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
