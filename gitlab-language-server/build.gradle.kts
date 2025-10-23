import org.gradle.jvm.tasks.Jar

plugins {
  kotlin("jvm") version "2.2.21"
  `maven-publish`

  // Support resolving Eclipse plug-ins as Maven dependencies.
  id("dev.equo.p2deps") version "1.7.8"
}

kotlin {
  jvmToolchain(21)
}

val eclipseRelease = "4.33"
// Declare OSGi bundles (Eclipse plug-ins) that are required in our plug-in's manifest.
val eclipseDependencies = mapOf(
  "org.eclipse.lsp4j.jsonrpc" to "0.23.1",
  "org.eclipse.lsp4j" to "0.23.1",
  "org.eclipse.jface.text" to "3.25.200",
)
p2deps {
  into(listOf("compileOnly", "testImplementation")) {
    p2repo("https://download.eclipse.org/eclipse/updates/$eclipseRelease/")
    p2repo("https://download.eclipse.org/lsp4e/releases/latest/")

    eclipseDependencies.forEach {
      install(it.key)
    }
  }
}

// TODO: Explore using Eclipse-ExtensibleAPI?
tasks.withType<Jar> {
  manifest {
    attributes["Bundle-ManifestVersion"] = "2"
    attributes["Bundle-Name"] = "GitLab Language Server Client"
    attributes["Bundle-SymbolicName"] = "com.gitlab.eclipse.${project.name};singleton:=true"
    attributes["Bundle-Vendor"] = "GitLab Inc."
    attributes["Bundle-Version"] = ext["bundleVersion"]

    attributes["Automatic-Module-Name"] = "com.gitlab.eclipse.${project.name}"

    attributes["Require-Bundle"] = eclipseDependencies.map {
      "${it.key};bundle-version=\"${it.value}\""
    }.joinToString(separator = ",")
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
