plugins {
    java
    alias(libs.plugins.springBoot)
}

dependencies {
    implementation(project(":intellihub-all"))
    testImplementation(libs.springBootStarterTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
