import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 릴리스 서명: 키 파일·비밀번호는 저장소 밖(커밋 금지, .gitignore 처리)에서만 읽는다 — docs/RELEASE.md
 *  1) 루트의 keystore.properties(storeFile, storePassword, keyAlias, keyPassword) 또는
 *  2) 환경 변수 SYNE_KEYSTORE, SYNE_KEYSTORE_PASSWORD, SYNE_KEY_ALIAS, SYNE_KEY_PASSWORD
 * 둘 다 없으면 release는 서명되지 않은 채로 만들어진다(디버그 키로 몰래 서명하지 않는다).
 */
val releaseSigning: Map<String, String>? = run {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        val p = Properties().apply { f.inputStream().use { load(it) } }
        val m = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { p.getProperty(it).orEmpty().trim() }
        // 템플릿만 만들고 비밀번호를 아직 안 채웠으면 '설정 없음'으로 본다(packageReleaseApk가 무엇이 비었는지 알려 준다)
        if (m.values.all { it.isNotEmpty() }) m else null
    } else {
        val env = mapOf(
            "storeFile" to System.getenv("SYNE_KEYSTORE"), "storePassword" to System.getenv("SYNE_KEYSTORE_PASSWORD"),
            "keyAlias" to System.getenv("SYNE_KEY_ALIAS"), "keyPassword" to System.getenv("SYNE_KEY_PASSWORD"),
        )
        if (env.values.all { !it.isNullOrBlank() }) env.mapValues { it.value!! } else null
    }
}

android {
    namespace = "ai.syne.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.syne.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 앨범 아트 생성 서버(선택). 비우면 기기 안에서 그린다. 예: ./gradlew -Psyne.coverServer=https://… assembleRelease
        buildConfigField("String", "COVER_SERVER", "\"${(project.findProperty("syne.coverServer") as String?) ?: ""}\"")
    }

    // AAOS CarPropertyManager (속도·기어). 런타임에는 차량 시스템이 제공.
    useLibrary("android.car")

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // 성능 측정용: release와 같은 최적화(디버깅 불가), 디버그 키로 서명해 바로 설치 가능
        create("bench") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // android.util.Log 등은 JVM 단위 테스트에서 기본값 반환(오프라인 렌더 테스트용)
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // 데모 코스(JSON) 오프라인 렌더 테스트용
}

/** 제출용 APK: ./gradlew :app:packageReleaseApk → apk/SYNE_release.apk (서명 설정이 없으면 실패) */
tasks.register("packageReleaseApk") {
    dependsOn("assembleRelease")
    doLast {
        check(releaseSigning != null) {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                val p = Properties().apply { f.inputStream().use { load(it) } }
                val empty = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { p.getProperty(it).isNullOrBlank() }
                "keystore.properties에 비어 있는 값이 있습니다: ${empty.joinToString()} (docs/RELEASE.md 2장)"
            } else {
                "릴리스 서명 설정이 없습니다. docs/RELEASE.md대로 keystore.properties를 먼저 만드세요."
            }
        }
        val src = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(src.exists()) { "서명된 APK가 없습니다: $src" }
        val dst = rootProject.file("apk/SYNE_release.apk")
        dst.parentFile.mkdirs()
        src.copyTo(dst, overwrite = true)
        println("→ ${dst.path}")
    }
}
