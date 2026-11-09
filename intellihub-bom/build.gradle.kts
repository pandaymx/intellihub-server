plugins {
    `java-platform`
    `maven-publish`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform(libs.springBootDependencies))
    api(platform(libs.springAiBom))

    constraints {
        api(project(":intellihub-core"))
        api(project(":intellihub-llm"))
        api(project(":intellihub-prompt"))
        api(project(":intellihub-rag"))
        api(project(":intellihub-agent"))
        api(project(":intellihub-spring-boot-starter"))
        api(project(":intellihub-all"))
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenBom") {
            from(components["javaPlatform"])
        }
    }
}
