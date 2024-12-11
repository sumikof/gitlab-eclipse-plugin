import org.gradle.jvm.tasks.Jar

plugins {
    java
    `maven-publish`
}

tasks.withType<Jar> {
    manifest {
        attributes["Bundle-ManifestVersion"] = "2"
        attributes["Bundle-Name"] = "GitLab Language Server Client"
        attributes["Bundle-SymbolicName"] = "com.gitlab.eclipse.${project.name};singleton:=true"
        attributes["Bundle-Vendor"] = "GitLab Inc."
        attributes["Bundle-Version"] = ext["bundleVersion"]

        attributes["Automatic-Module-Name"] = "com.gitlab.eclipse.${project.name}"
    }
}

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
