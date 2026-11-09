plugins {
    `java-library`
}

dependencies {
    api(libs.springBootStarter)
    api(libs.springBootAutoconfigure)
    compileOnly(project(":intellihub-core"))
    compileOnly(project(":intellihub-llm"))
    compileOnly(project(":intellihub-prompt"))
    compileOnly(project(":intellihub-rag"))
    compileOnly(project(":intellihub-agent"))
    annotationProcessor(libs.springBootConfigurationProcessor)
}
