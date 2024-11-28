plugins {
    java
    `maven-publish`
    id("com.github.node-gradle.node")
}

@Suppress("UNCHECKED_CAST")
val downloadPlatformDependentBinary = extra["downloadPlatformDependentBinary"] as () -> Unit
downloadPlatformDependentBinary()

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}