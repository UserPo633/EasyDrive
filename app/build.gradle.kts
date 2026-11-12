import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 서명 정보는 저장소에 올리지 않는 keystore.properties 에서 읽습니다.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 화면 캡처 테스트(Robolectric)는 내려받을 라이브러리가 커서 -Pscreens 를 줄 때만 포함합니다.
//   ./gradlew testDebugUnitTest -Pscreens --tests "*ScreenshotTest*"  → app/build/screens/*.png
val withScreens = project.hasProperty("screens")

android {
    namespace = "com.easydrive.app"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.easydrive.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // APK 크기를 줄이기 위해 R8을 켜고, SDK 클래스는 proguard-rules.pro에서 모두 보존합니다.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets { if (withScreens) getByName("test").java.srcDir("src/screens/java") }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all { t ->
                t.maxHeapSize = "1200m"
                // -Probolectric.repo=<Maven 미러 주소> 로 Robolectric 런타임을 받을 저장소를 바꿀 수 있습니다.
                (project.findProperty("robolectric.repo") as String?)?.let { t.systemProperty("robolectric.dependency.repo.url", it) }
            }
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR 생성(인수·반납 기록을 휴대폰으로 가져가기). 순수 자바 라이브러리, Apache 2.0
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    if (withScreens) {
        testImplementation("org.robolectric:robolectric:4.14.1")
        testImplementation("androidx.compose.ui:ui-test-junit4")
        testImplementation("androidx.test:core:1.6.1")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
}
