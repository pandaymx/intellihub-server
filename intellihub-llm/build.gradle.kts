plugins {
    `java-library`
}

dependencies {
    api(project(":intellihub-core"))
    api(libs.caffeine)
    api(libs.springAiStarterModelOpenai)
    api(libs.springAiDeepseek)
    api(libs.springAiAnthropic)
    api(libs.springAiGoogleGenai)
    api(libs.springAiOllama)
    api(libs.springAiMistralAi)
    api(libs.springAiBedrockConverse)
    api(libs.jinferSpringAi)
    compileOnly(libs.jinferModelsAll)
}
