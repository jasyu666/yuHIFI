plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 加 -PskipNative 可跳过 NDK/CMake 编译，只验证 Kotlin 层能否通过，
// 在原生工具链就绪之前用来快速排错
val skipNative = project.hasProperty("skipNative")

android {
    namespace = "com.hifiprobe"
    compileSdk = 35

    if (!skipNative) {
        // 与安装的 NDK / CMake 版本严格对应，改这里要同步改 SDK Tools 里装的版本
        ndkVersion = "27.0.12077973"
    }

    defaultConfig {
        applicationId = "com.hifiprobe"
        minSdk = 26
        targetSdk = 35
        /*
         * ★★ 每出一次包都必须往上走一位 —— 不是"改了功能才走"。
         *
         *   versionName 会出现在**导出的诊断报告末尾**（MainActivity 的页脚）
         *   和 `/debug/state` 的 JSON 里，是判断「这份报告是哪个构建跑出来的」
         *   的第一依据。
         *
         *   实测教训：从 p50 到 p67 一直没动，报告里始终写着 "0.1-p50"。
         *   拿到报告的人（包括我自己）会以为在跑三周前的构建，
         *   去查一堆早就修掉的问题 —— 白费一整轮。
         *
         * ★ 两条线用**两套版本号**：
         *   · 这里（`main`，内部版）—— `0.1-pXX`，跟着构建序号走，
         *     方便对着归档的 APK 文件名认人。
         *   · `release`（正式版）—— `0.1-release` 这种**产品版本**，
         *     不带构建序号；具体哪个构建看 versionCode。
         */
        versionCode = 116                 // 与归档文件名里的 pXX 对齐
        versionName = "0.1-p116"

        if (!skipNative) {
            ndk {
                // 目前只有 arm64-v8a：FFmpeg 是为该 ABI 单独交叉编译的。
                // 要支持 armeabi-v7a 需再用 NDK 跑一次 FFmpeg configure + make，
                // 并把产物补进 jniLibs/armeabi-v7a/。目标机型是 arm64，暂不需要。
                abiFilters += listOf("arm64-v8a")
            }

            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                    arguments += listOf("-DANDROID_STL=c++_shared")
                }
            }
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
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
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // UacParser 是纯 Kotlin、不依赖任何 Android 类，
    // 因此可以直接在 JVM 上对真实设备描述符做回归测试
    testImplementation("junit:junit:4.13.2")
}
