plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.chapter07"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.chapter07"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

        // mymymy: 从 gradle.properties 读取 OpenAI API Key，未配置则使用占位符
        // 配置方式：在项目根 gradle.properties 里加一行：OPENAI_API_KEY=sk-xxxxxxxx
        val openAiKey: String = (project.findProperty("OPENAI_API_KEY") as String?)
            ?.takeIf { it.isNotBlank() && !it.startsWith("__") }
            ?: "PLACEHOLDER_SET_OPENAI_API_KEY_IN_GRADLE_PROPERTIES"
        buildConfigField("String", "OPENAI_API_KEY", "\"" + openAiKey + "\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity:1.8.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    implementation("org.opencv:opencv:4.13.0")

    // WebRTC - 使用正确的 groupId
    implementation("com.infobip:google-webrtc:1.0.43591")

    // EasyPermissions
    implementation("pub.devrel:easypermissions:3.0.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0")

    implementation("androidx.cardview:cardview:1.0.0")
}