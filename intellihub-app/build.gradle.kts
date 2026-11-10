plugins {
    java
    alias(libs.plugins.springBoot)
}

dependencies {
    implementation(project(":intellihub-all"))
    implementation(libs.springBootStarterWeb)
    testImplementation(libs.springBootStarterTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
