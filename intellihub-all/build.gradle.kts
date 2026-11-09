plugins {
    `java-library`
}

dependencies {
    api(project(":intellihub-core"))
    api(project(":intellihub-llm"))
    api(project(":intellihub-prompt"))
    api(project(":intellihub-rag"))
    api(project(":intellihub-agent"))
    api(project(":intellihub-spring-boot-starter"))
}
