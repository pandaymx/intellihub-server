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
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        mavenCentral()
    }
}

rootProject.name = "intellihub-server"

include(":intellihub-bom")
include(":intellihub-core")
include(":intellihub-llm")
include(":intellihub-prompt")
include(":intellihub-rag")
include(":intellihub-agent")
include(":intellihub-spring-boot-starter")
include(":intellihub-all")
include(":intellihub-app")
