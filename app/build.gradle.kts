import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息从 keystore.properties 读取（该文件不进仓库，见 .gitignore）。
// 找不到就用 debug 签名 —— 仍然能安装，只是不能覆盖安装正式版。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.funnyarchbishop.fumofumo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.funnyarchbishop.fumofumo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile")
                ?: System.getenv("FUMOFUMO_KEYSTORE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("FUMOFUMO_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("FUMOFUMO_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("FUMOFUMO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
