pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK (Vehicle, NaviHelper)
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            content { includeGroup("ai.pleos.playground") }
        }
    }
}

rootProject.name = "syne"
include(":app")
include(":core")   // 차량·모바일 공통: 디자인 시스템, 시그니처 오브젝트, 앨범 번들 형식
include(":mobile") // 모바일 컴패니언 앱
