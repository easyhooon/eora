plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

tasks.register<Exec>("checkEoraApiParity") {
    description = "Checks that eora and eora-noop expose the same Eora API."
    dependsOn(":eora:compileDebugKotlin", ":eora-noop:compileDebugKotlin")
    commandLine("bash", "scripts/check-eora-api-parity.sh")
}

tasks.register<Exec>("checkReleaseLeak") {
    description = "Checks that the sample's prodRelease APK contains no Eora implementation code, UI or permissions."
    dependsOn(":sample:assembleProdRelease")
    commandLine("bash", "scripts/check-release-leak.sh")
}

// Shared POM for every published module. License is intentionally missing until it is chosen (docs/OPEN_QUESTIONS.md #4),
// so only publishToMavenLocal is expected to work for now; Maven Central rejects a POM without a license.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates(
                groupId = "io.github.easyhooon",
                artifactId = project.name,
                version = rootProject.libs.versions.eora.get(),
            )
            pom {
                name.set(project.name)
                description.set("Shake-to-report QA evidence library for STG Android apps")
                inceptionYear.set("2026")
                url.set("https://github.com/easyhooon/eora")
                developers {
                    developer {
                        id.set("easyhooon")
                        name.set("Lee jihun")
                        email.set("mraz3068@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/easyhooon/eora")
                    connection.set("scm:git:git://github.com/easyhooon/eora.git")
                    developerConnection.set("scm:git:ssh://git@github.com/easyhooon/eora.git")
                }
            }
        }
    }
}
