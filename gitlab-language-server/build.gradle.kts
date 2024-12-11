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

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}