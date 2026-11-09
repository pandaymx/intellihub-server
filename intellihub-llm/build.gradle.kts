plugins {
    `java-library`
}

dependencies {
    api(project(":intellihub-core"))
    api(libs.springAiStarterModelOpenai)
}
