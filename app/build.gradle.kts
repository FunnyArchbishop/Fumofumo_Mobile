plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.funnyarchbishop.fumofumo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.funnyarchbishop.fumofumo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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

    testOptions {
        unitTests.all {
            it.testLogging { showStandardStreams = true }
        }

        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// 单元测试可以把真实模型路径传进去，例如：
//   gradle testDebugUnitTest -DtestGlb=D:/m/model.glb -DtestUsdc=D:/m/scene.usdc
tasks.withType<Test>().configureEach {
    listOf("testGlb", "testUsdc", "testModel", "testGlbDir").forEach { key ->
        systemProperty(key, System.getProperty(key) ?: "")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
