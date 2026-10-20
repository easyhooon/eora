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

// Shared POM and Maven Central setup for every published module. Signing and Central credentials come from
// ~/.gradle/gradle.properties (signingInMemoryKey*, mavenCentralUsername/Password), never from this repository.
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
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
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

            // Manual release: uploads stay in the Central Portal until someone presses Publish there, because a
            // published version can never be removed.
            publishToMavenCentral(automaticRelease = false)
            signAllPublications()
        }
    }
}
