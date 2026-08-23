import java.time.Instant
import java.util.Properties

/** ストアに表示されるバージョン。変更のたびにここを上げる。 */
val appVersionName = "1.9"

/**
 * 署名情報はリポジトリに入れず、Git 管理外の keystore.properties から読む。
 * ファイルが無い場合は未署名でビルドする（普段の開発やデバッグビルドはこれで通る）。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

/**
 * Play Console は一度使った versionCode を二度と受け付けない。
 * リリースビルドでは「2026-01-01 からの経過分数」を使い、ビルドするたびに必ず増える値にする。
 * 手で上げ忘れてもアップロードが弾かれない。
 */
fun playVersionCode(): Int {
    val epochSeconds = 1_767_225_600L // 2026-01-01T00:00:00Z
    return ((Instant.now().epochSecond - epochSeconds) / 60L).toInt()
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.coinarina3d.myapp"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.coinarina3d.myapp"
        minSdk = 24
        targetSdk = 36
        // デバッグビルド用の固定値。Play に上げるのはリリースビルドだけなので据え置きでよい。
        // 1 のような小さい値だと、過去に入れたビルドより古いと判定されて再インストールが弾かれる。
        versionCode = 1000
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // keystore.properties があれば署名する。Play Console は未署名を受け付けない。
            signingConfig = signingConfigs.findByName("release")
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
        compose = true
        buildConfig = true
    }
}

// リリースビルドだけ versionCode を自動採番する
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val generated = playVersionCode()
        variant.outputs.forEach { output ->
            output.versionCode.set(generated)
            output.versionName.set(appVersionName)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // お知らせを定期チェックするバックグラウンド処理
    implementation(libs.androidx.work.runtime.ktx)
    // アプリ内で Google アカウントを選ぶための Credential Manager
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // play-services 経由で fragment 1.2.5 が compile 側に来てしまい、
    // registerForActivityResult が lint エラー（1.3.0 未満）になるため明示的に揃える
    implementation(libs.androidx.fragment)
    testImplementation(libs.junit)
    // JVM テストでは android の org.json がスタブなので実装を足す
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}