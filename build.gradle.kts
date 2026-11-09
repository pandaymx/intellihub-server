import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.springBoot) apply false
}

val sdkReleaseVersion = providers.gradleProperty("intellihub.sdkRelease").map { it.toInt() }.getOrElse(21)

allprojects {
    group = "dev.ppmb"
    version = providers.gradleProperty("revision").getOrElse("0.0.1-SNAPSHOT")
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val managedConfigurations = listOf(
    "api",
    "implementation",
    "compileOnly",
    "runtimeOnly",
    "annotationProcessor",
    "testImplementation",
    "testCompileOnly",
    "testRuntimeOnly",
    "testAnnotationProcessor",
)

subprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "com.diffplug.spotless")

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
            val targetRelease = if (project.name == "intellihub-app") 25 else sdkReleaseVersion
            options.release.set(targetRelease)
        }

        tasks.withType<Javadoc>().configureEach {
            options.encoding = "UTF-8"
            (options as? StandardJavadocDocletOptions)?.addStringOption("Xdoclint:none", "-quiet")
        }

        configure<SpotlessExtension> {
            java {
                target("src/**/*.java")
                googleJavaFormat().aosp().skipJavadocFormatting()
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        val deps = dependencies
        managedConfigurations.forEach { configName ->
            configurations.matching { it.name == configName }.configureEach {
                deps.add(configName, deps.platform(rootProject.libs.springBootDependencies))
                deps.add(configName, deps.platform(rootProject.libs.springAiBom))
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    pluginManager.withPlugin("java-library") {
        apply(plugin = "maven-publish")

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                }
            }
        }
    }
}
