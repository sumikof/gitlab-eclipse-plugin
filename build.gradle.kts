import java.time.Instant

plugins {
  kotlin("jvm") version "2.0.20"

  // Provide Equo IDE as a sandbox and support listing available Eclipse categories/features.
  id("dev.equo.ide") version "1.7.7"

  // Support resolving Eclipse plug-ins as Maven dependencies.
  id("dev.equo.p2deps") version "1.7.7"

  // Deploy artifacts to this project's Maven repository (e.g. GitLab Package Registry).
  `maven-publish`
}

group = "com.gitlab.eclipse"
version = "0.2.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    if (System.getenv("CI") == "true") {
        maven("https://gitlab.com/api/v4/groups/67713089/-/packages/maven") {
            name = "gitlab-maven"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

val arch = when (System.getProperty("os.arch")) {
  "aarch64" -> "aarch64"
  else -> "x86_64"
}
val osgiPlatform = when (System.getProperty("os.name")) {
  "Mac OS X" -> "cocoa.macosx.$arch"
  "Windows 11" -> "win32.win32.$arch"
  else -> "gtk.linux.$arch"
}
// Transform the string `${osgi.platform}` into an explicit artifactId
// for transient Maven dependencies since Gradle does not support
// properties inside of artifact name/versions.
//
// See also https://github.com/jmini/ecentral/issues/21.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.name.contains("\${osgi.platform}")) {
            useTarget(requested.toString().replace("\${osgi.platform}", osgiPlatform))
            because("prefer $osgiPlatform over \${osgi.platform}")
        }
    }
}

dependencies {
    // See note below around manually packing the Kotlin Standard Library/Runtime classes into the GitLab for Eclipse plug-in bundle.
    runtimeOnly(kotlin("osgi-bundle"))

    // 1. Must be available for compiling kotlin on linux.
    // 2. Must be available as a runtime dependency for running Equo on linux.
    // See also https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/issues/15
    implementation("org.eclipse.platform:org.eclipse.swt.\${osgi.platform}:+")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
}

val eclipseRelease = "4.33"
p2deps {
    into(listOf("compileOnly", "testImplementation")) {
        p2repo("https://download.eclipse.org/eclipse/updates/${eclipseRelease}/")

        install("org.eclipse.jdt.core")
        install("org.eclipse.osgi")
        install("org.eclipse.swt")
        install("org.eclipse.equinox.security")
        install("org.eclipse.ui")
    }
}

tasks.withType<Jar> {
    // NOTE: Ensure Kotlin available in the Eclipse OSGi bundle by packing it into our Jar.
    // The kotlin-osgi-bundle packages these as valid bundles but Erran couldn't figure out how to use pure OSGi to depend on Kotlin Standard Library/Runtime.
    // We could ship a separate Eclipse plug-in to expose Kotlin libraries on the classpath and require that the usual OSGi way.
    val kotlinLibraries = listOf(
        "kotlin-runtime-2.0.20.jar",
        "kotlin-stdlib-2.0.20.jar",
    )
    configurations.runtimeClasspath.get()
        .filter { kotlinLibraries.contains(it.name) }
        .map { zipTree(it) }
        .also { from(it) }

    // Tweak the plug-in project's to generate a valid OSGi bundle which is a requirement for shipping an Eclipse plug-in Jar.
    manifest {
        attributes["Bundle-ActivationPolicy"] = "lazy"
        attributes["Bundle-ManifestVersion"] = "2"
        attributes["Bundle-Name"] = "GitLab Eclipse Plugin"
        attributes["Bundle-RequiredExecutionEnvironment"] = "JavaSE-21"
        attributes["Bundle-SymbolicName"] = "${project.name};singleton:=true"
        attributes["Bundle-Vendor"] = "GitLab Inc."
        attributes["Bundle-Version"] = "0.2.0.${Instant.now().toEpochMilli()}"

        attributes["Automatic-Module-Name"] = project.name

        // Declare OSGi bundles (Eclipse plug-ins) that are required in our plug-in's manifest.
        val eclipseDependencies = listOf(
           "org.eclipse.swt",
           "org.eclipse.ui",
        )
        attributes["Require-Bundle"] = eclipseDependencies.joinToString(separator = ",")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }

    repositories {
        maven("https://gitlab.com/api/v4/projects/62043363/packages/maven") {
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

tasks.register("checkReleaseVersion") {
    doLast {
        val commitTag = System.getenv("CI_COMMIT_TAG") ?: null
        val isTagPipeline = !commitTag.isNullOrEmpty()
        if (isTagPipeline) {
            if (project.version.toString().endsWith("-SNAPSHOT")) {
                error("The project version '${project.version}' must not contain -SNAPSHOT.")
            }

            if (commitTag != "v${project.version}") {
                error("The commit tag '$commitTag' did not match semantic version: v${project.version}")
            }
        }
    }
}

tasks.register("checkSnapshotVersion") {
    doLast {
        if (!project.version.toString().endsWith("-SNAPSHOT")) {
            error("The project version '${project.version}' must contain -SNAPSHOT.")
        }
    }
}

// Configure Equo IDE with basic to provide the GitLab for Eclipse plug-in.
equoIde {
    equoIde.branding.title("GitLab for Eclipse Equo Sandbox")

    // Bundle Eclipse Platform.
    platform()

    // Bundle Java Developer Tools (which helps testing Code Suggestions).
    jdt()

    // Strangely required to start in our Ubuntu docker image but not on Mac OS...
    // See also https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/issues/15
    install("org.apache.felix.scr")

    // Install the GitLab for Eclipse plug-in project.
    dogfood()
}
