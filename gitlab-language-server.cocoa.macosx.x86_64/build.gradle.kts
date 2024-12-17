plugins {
    java
    `maven-publish`
    id("com.github.node-gradle.node")
}

@Suppress("UNCHECKED_CAST")
val downloadPlatformDependentBinary = extra["downloadPlatformDependentBinary"] as () -> Unit
downloadPlatformDependentBinary()

val gitlabEclipsePluginProjectId = System.getenv().getOrDefault("CI_PROJECT_ID", "62043363").toInt()
publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }

    repositories {
        maven("https://gitlab.com/api/v4/projects/$gitlabEclipsePluginProjectId/packages/maven") {
            name = "gitlab-maven"

            if (System.getenv("CI") == "true") {
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN") ?: error("CI_JOB_TOKEN must be set to deploy artifacts in CI")
                }

                authentication {
                    create("header", HttpHeaderAuthentication::class)
                }
            }
        }
    }
}
