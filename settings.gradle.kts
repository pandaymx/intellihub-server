pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        mavenCentral()
    }
}

rootProject.name = "intellihub-server"
