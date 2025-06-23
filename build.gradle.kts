import dev.equo.ide.gradle.EquoIdeTask
import groovy.json.JsonSlurper
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.jvm.tasks.Jar
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.io.path.walk

val gitlabEclipsePluginProjectId = System.getenv().getOrDefault("CI_PROJECT_ID", "62043363").toInt()
val isLocalBuild by lazy { System.getenv("CI").isNullOrEmpty() }

plugins {
  kotlin("jvm") version "2.1.21"

  // Deploy artifacts to this project's Maven repository (e.g. GitLab Package Registry).
  `maven-publish`

  id("com.github.node-gradle.node").version("7.1.0") apply false

  // Provide Equo IDE as a sandbox and support listing available Eclipse categories/features.
  id("dev.equo.ide") version "1.7.8"

  // Support resolving Eclipse plug-ins as Maven dependencies.
  id("dev.equo.p2deps") version "1.7.8"

  id("io.gitlab.arturbosch.detekt") version "1.23.8"

  id("com.github.gmazzo.buildconfig") version "5.6.6"
}

allprojects {
  group = "com.gitlab.eclipse"
  version = "0.7.3"
  ext["bundleVersion"] = "0.7.3.${Instant.now().toEpochMilli()}"

  repositories {
    gradlePluginPortal()
    mavenLocal()
    mavenCentral()
  }
}

tasks.test {
  useJUnitPlatform()
}

detekt {
  buildUponDefaultConfig = true // preconfigure defaults
  allRules = true // activate all available (even unstable) rules.
  config.setFrom("detekt.yml")
}

tasks.withType<Detekt>().configureEach {
  jvmTarget = JavaVersion.VERSION_17.toString()

  reports {
    html.required.set(true) // observe findings in your browser with structure and code snippets
  }
}

buildConfig {
  packageName(group.toString())

  buildConfigField(
    "Boolean",
    "IS_EQUO_IDE",
    System.getenv().getOrDefault("EQUO_IDE", "false").toBoolean()
  )

  buildConfigField(
    "Boolean",
    "CODE_SUGGESTIONS_TOOLTIP_ENABLED",
    isLocalBuild
  )

  buildConfigField(
    "String",
    "SNOWPLOW_COLLECTOR_URL",
    "\"${if (isLocalBuild) "http://localhost:9090" else "https://snowplowprd.trx.gitlab.net"}\""
  )
  buildConfigField(
    "Boolean",
    "OAUTH_ENABLED",
    false
  )
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
  implementation(project(":gitlab-language-server"))

  implementation("org.slf4j:slf4j-api:2.0.17")
  implementation("org.apache.logging.log4j:log4j-core:2.25.0")
  implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.0")

  implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

  // NOTE: This dependency is needed for equoIde, we should make sure it's not included in the final plugin bundle.
  implementation("com.google.guava:guava:33.4.8-jre")
  implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.21")
  implementation("io.insert-koin:koin-core:4.1.0")

  testImplementation(kotlin("test"))
  testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
  testImplementation("io.mockk:mockk:1.14.4")

  testImplementation("org.eclipse.platform:org.eclipse.text:3.14.300")
  testImplementation("org.eclipse.platform:org.eclipse.ui.workbench:3.135.100")
  testImplementation("org.eclipse.platform:org.eclipse.ui.editors:3.20.0")
  testImplementation("org.eclipse.platform:org.eclipse.swt:3.130.0")

  detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")

  implementation("com.github.scribejava:scribejava-core:8.3.3")
  implementation("org.nanohttpd:nanohttpd:2.3.1")
}

val eclipseRelease = "4.33"
// Declare OSGi bundles (Eclipse plug-ins) that are required in our plug-in's manifest.
val eclipseDependencies = mapOf(
  "org.eclipse.e4.core.services" to "2.4.400",
  "org.eclipse.e4.ui.css.swt.theme" to "0.14.400",
  "org.osgi.service.event" to "1.4.1",
  "org.eclipse.core.runtime" to "0.0.0",
  "org.eclipse.core.resources" to "0.0.0",
  "org.eclipse.core.net" to "0.0.0",
  "org.eclipse.core.filesystem" to "0.0.0",
  "org.eclipse.equinox.security" to "0.0.0",
  "org.eclipse.lsp4j.jsonrpc" to "0.23.1",
  "org.eclipse.lsp4j" to "0.23.1",
  "org.eclipse.osgi" to "0.0.0",
  "org.eclipse.swt" to "0.0.0",
  "org.eclipse.ui" to "0.0.0",
  "org.eclipse.ui.ide" to "3.22.0",
  "org.eclipse.ui.editors" to "0.0.0",
  "org.eclipse.ui.workbench" to "3.133.0",
  "org.eclipse.ui.workbench.texteditor" to "0.0.0",
  "org.eclipse.text" to "0.0.0",
  "org.eclipse.jface.text" to "0.0.0",
  "org.eclipse.jface.notifications" to "0.0.0",
  "org.eclipse.jgit" to "[7.0.0,8.0.0)",
  "org.eclipse.jdt.core" to "0.0.0",
  "com.google.gson" to "2.11.0",
)

p2deps {
  into(listOf("compileOnly", "testImplementation")) {
    p2repo("https://download.eclipse.org/eclipse/updates/$eclipseRelease/")
    p2repo("https://download.eclipse.org/lsp4e/releases/latest/")
    p2repo("https://download.eclipse.org/egit/updates/")

    eclipseDependencies.forEach {
      install(it.key)
    }
  }
}

tasks.withType<Jar> {
  // NOTE: Ensure Kotlin available in the Eclipse OSGi bundle by packing it into our Jar.
  // The kotlin-osgi-bundle packages these as valid bundles but Erran couldn't figure out how to use pure OSGi to depend on Kotlin Standard Library/Runtime.
  // We could ship a separate Eclipse plug-in to expose Kotlin libraries on the classpath and require that the usual OSGi way.
  val kotlinLibraries = listOf(
    "kotlin-reflect",
    "kotlin-stdlib",
    "kotlinx-coroutines-core-jvm",
    "koin-core-jvm",
    "slf4j-api",
    "log4j",
    "scribejava-core",
    "nanohttpd"
  )

  duplicatesStrategy = DuplicatesStrategy.EXCLUDE

  configurations.runtimeClasspath.get()
    .filter { runtimeLib -> kotlinLibraries.any { lib -> runtimeLib.name.startsWith(lib) } }
    .map { zipTree(it) }
    .also { from(it) }

  // Tweak the plug-in project's to generate a valid OSGi bundle which is a requirement for shipping an Eclipse plug-in Jar.
  manifest {
    attributes["Bundle-ActivationPolicy"] = "lazy"
    attributes["Bundle-Activator"] = "com.gitlab.eclipse.GitLabEclipseStartup"
    attributes["Bundle-ManifestVersion"] = "2"
    attributes["Bundle-Name"] = "GitLab for Eclipse"
    attributes["Bundle-RequiredExecutionEnvironment"] = "JavaSE-21"
    attributes["Bundle-SymbolicName"] = "com.gitlab.eclipse.${project.name};singleton:=true"
    attributes["Bundle-Vendor"] = "GitLab Inc."
    attributes["Bundle-Version"] = ext["bundleVersion"]

    attributes["Automatic-Module-Name"] = "com.gitlab.eclipse.${project.name}"

    attributes["Require-Bundle"] = eclipseDependencies.map {
      "${it.key};bundle-version=\"${it.value}\""
    }.joinToString(separator = ",")
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

tasks.withType<EquoIdeTask> {
  dependsOn(
    provider {
      subprojects.map { subproject ->
        subproject.tasks.named("jar")
      }
    }
  )
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

  p2repo("https://download.eclipse.org/lsp4e/releases/latest/")
  install("org.eclipse.lsp4j.jsonrpc")
  install("org.eclipse.lsp4j")

  // Install the GitLab for Eclipse plug-in project.
  dogfood()
}

tasks.register("lspDownloadGenericPackageJson") {
  val outputDir = rootProject.layout.buildDirectory.dir("gitlab-lsp")
  val outputFile = outputDir.get().file("generic_packages.json")

  outputs.dir(outputDir)

  doLast {
    val url =
      "https://gitlab.com/api/v4/projects/gitlab-org%2Feditor-extensions%2Fgitlab-lsp/packages?package_type=generic&sort=desc"

    URI(url).toURL().openStream().use { input ->
      outputFile.asFile.outputStream().use { output ->
        input.copyTo(output)
      }
    }
  }
}

// Parse package.json to get gitlab-lsp version
fun extractGitLabLspVersion(): String {
  return JsonSlurper().parse(file("${rootProject.projectDir}/package.json"))
    .let { it as? Map<String, Any> ?: error("Unexpected format for package.json.") }
    .let {
      it["dependencies"] as? Map<String, String> ?: error("Invalid dependencies in package.json.")
    }
    .let {
      it["@gitlab-org/gitlab-lsp"]
        ?: error("Unable to find @gitlab-org/gitlab-lsp under dependencies in package.json.")
    }
}

tasks.register("lspDownloadGenericPackageFilesJson") {
  dependsOn(":lspDownloadGenericPackageJson")

  val buildDir = rootProject.layout.buildDirectory.get().asFile
  val gitlabLspDir = buildDir.resolve("gitlab-lsp")

  inputs.file(gitlabLspDir.resolve("generic_packages.json"))
  outputs.dir(gitlabLspDir)

  @Suppress("UNCHECKED_CAST")
  doLast {
    val slurper = JsonSlurper()

    val gitlabLspVersion = extractGitLabLspVersion()

    // Parse generic_packages.json to find package URL
    val lspPackages =
      slurper.parse(gitlabLspDir.resolve("generic_packages.json")) as? List<Map<String, Any>>
        ?: error("Unable to parse generic_packages.json")

    val packageUrl = lspPackages.find { it["version"] == gitlabLspVersion }
      ?.let { it["id"] as? Number }
      ?.let { "https://gitlab.com/api/v4/projects/gitlab-org%2Feditor-extensions%2Fgitlab-lsp/packages/$it/package_files" }
      ?: error("Unable to find generic package for @gitlab-org/gitlab-lsp v$gitlabLspVersion.")

    // Download package_files.json
    URI(packageUrl).toURL().openStream().use { input ->
      gitlabLspDir.resolve("package_files.json").outputStream().use { output ->
        input.copyTo(output)
      }
    }
  }
}

tasks.register("lspDownloadBinaries") {
  dependsOn(":lspDownloadGenericPackageFilesJson")

  val buildDir = rootProject.layout.buildDirectory
  val gitlabLspDir = buildDir.get().asFile.resolve("gitlab-lsp")
  val outputFile = gitlabLspDir.resolve("lsp-binaries.tar.gz")

  outputs.dir(gitlabLspDir)

  doLast {
    val packageFiles = JsonSlurper().parse(gitlabLspDir.resolve("package_files.json")) as? List<Map<String, Any>>
      ?: error("Unexpected format for package_files.json")

    val lspTarball = packageFiles.firstOrNull {
      val fileName = it["file_name"]?.toString()
        ?: return@firstOrNull false

      return@firstOrNull fileName.startsWith("gitlab-lsp") && fileName.endsWith(".tar.gz")
    } ?: error("Unable to find gitlab-lsp tarball in package_files.json")

    val lspTarballId = lspTarball["id"] as? Number
      ?: error("Invalid value for id for gitlab-lsp tarball.")

    URI(
      "https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/package_files/$lspTarballId/download"
    ).toURL()
      .openStream().use { input ->
        outputFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }

    copy {
      from(tarTree(outputFile))
      into(gitlabLspDir)
    }

    outputFile.delete()
  }
}

subprojects {
  if (project.name.startsWith("gitlab-language-server.")) {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    extra["downloadPlatformDependentBinary"] = fun() {
      val targetPlatform = project.name.replace("gitlab-language-server.", "")
      val languageServerPlatform = when (targetPlatform) {
        "cocoa.macosx.aarch64" -> "macos-arm64"
        "cocoa.macosx.x86_64" -> "macos-x64"
        "gtk.linux.x86_64" -> "linux-x64"
        "win32.win32.x86_64" -> "win-x64.exe"
        else -> error("Expected a Language Server binary to be declared for OSGi platform.")
      }
      val platformFilter = when (targetPlatform) {
        "cocoa.macosx.aarch64" -> "(& (osgi.ws=cocoa) (osgi.os=macosx) (osgi.arch=aarch64))"
        "cocoa.macosx.x86_64" -> "(& (osgi.ws=cocoa) (osgi.os=macosx) (osgi.arch=x86_64))"
        "gtk.linux.x86_64" -> "(& (osgi.ws=gtk) (osgi.os=linux) (osgi.arch=x86_64))"
        "win32.win32.x86_64" -> "(& (osgi.ws=win32) (osgi.os=win32) (osgi.arch=x86_64))"
        else -> error("Expected a Language Server binary to be declared for OSGi platform.")
      }

      tasks.withType<Jar> jar@{
        val gitlabLspDir = rootProject.layout.buildDirectory.get().asFile.resolve("gitlab-lsp")
        val versionsFolder = gitlabLspDir.resolve("installed_versions")
        val installedLspVersion = versionsFolder.resolve(targetPlatform)
        val currentLspVersion = extractGitLabLspVersion()

        if (System.getenv("EQUO_IDE") != "true" || !installedLspVersion.exists() || installedLspVersion.readText() != currentLspVersion) {
          dependsOn(":lspDownloadBinaries")

          from(gitlabLspDir) {
            include("bin/gitlab-lsp-$languageServerPlatform")
            rename { "gitlab-lsp" }
          }

          from(gitlabLspDir) {
            include("bin/vendor/**")
            include("bin/webviews/**")
            include("bin/tree-sitter.wasm")
          }
        }

        manifest {
          attributes["Bundle-ManifestVersion"] = "2"
          attributes["Bundle-Name"] = "GitLab Language Server ($targetPlatform)"
          attributes["Bundle-SymbolicName"] = "com.gitlab.eclipse.${project.name};singleton:=true"
          attributes["Bundle-Vendor"] = "GitLab Inc."
          attributes["Bundle-Version"] = ext["bundleVersion"]

          attributes["Automatic-Module-Name"] = "com.gitlab.eclipse.${project.name}"
          attributes["Fragment-Host"] = "com.gitlab.eclipse.gitlab-language-server"
          attributes["Eclipse-PlatformFilter"] = platformFilter
        }

        doLast {
          if (!gitlabLspDir.exists()) {
            return@doLast
          }

          if (!versionsFolder.exists()) {
            versionsFolder.mkdir()
          }

          if (!installedLspVersion.exists()) {
            installedLspVersion.createNewFile()
            installedLspVersion.setWritable(true)
          }

          installedLspVersion.writeText(currentLspVersion)
        }
      }
    }
  }
}

/**
 * 1. Uploads Eclipse P2 repository/update site artifacts to a new
 *    generic package registry version.
 * 2. Creates assets-links.json which can be used to create
 *    permalinks to the generic packages in a GitLab release
 *    for the current `CI_COMMIT_TAG`.
 */
tasks.create("publishToGitLab") {
  val gitlabPublishDryRun = providers.gradleProperty("gitlabPublishDryRun")
  doLast {
    val apiUrl = URI(System.getenv("CI_API_V4_URL").removeSuffix("/"))
    val commitTag = System.getenv("CI_COMMIT_TAG").removePrefix("v")
    val token = System.getenv("CI_JOB_TOKEN") ?: error("You must set CI_JOB_TOKEN to publish to the package registry.")

    val projectUri: URI = apiUrl.resolve("${apiUrl.rawPath}/projects/$gitlabEclipsePluginProjectId")
    val httpClient: HttpClient = HttpClient.newHttpClient()

    val artifacts = mutableMapOf<String, Path>()
    val eclipseRepository = Path("update-site/target/repository")
    @OptIn(ExperimentalPathApi::class)
    for (artifact in eclipseRepository.walk()) {
      val relativePath = artifact.pathString.removePrefix("${eclipseRepository.pathString}/")
      artifacts[relativePath] = artifact
    }

    if (artifacts.isNotEmpty()) {
      logger.quiet("Saved asset links JSON as asset-links.json")
    } else {
      logger.quiet("No artifacts found for publishing")
    }

    artifacts.forEach { (relativePath, artifact) ->
      if (gitlabPublishDryRun.getOrElse("false").toBoolean()) {
        logger.quiet("Skipped publishing file $relativePath")
      } else {
        val artifactDestinationUri: URI =
          "${projectUri.rawPath}/packages/generic/gitlab-eclipse-plugin/$commitTag/$relativePath"
            .let(projectUri::resolve)
        logger.quiet("Publishing file $artifactDestinationUri")
        val request = HttpRequest.newBuilder()
          .uri(artifactDestinationUri)
          .header("JOB-TOKEN", token)
          .PUT(HttpRequest.BodyPublishers.ofFile(artifact))
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString()).apply {
          val status = statusCode()
          if (status >= 400) {
            error("Failed to publish file: Response Status: $status - Body: ${body()}")
          }
        }
      }
    }

    val releaseDir = rootProject.layout.buildDirectory.get().asFile.resolve("release")
    releaseDir.mkdirs()

    val assetLinksFile = releaseDir.resolve("asset-links.json")
    assetLinksFile.writeText(
      artifacts.keys.joinToString(separator = ",", prefix = "[", postfix = "]") { relativePath ->
        val url: URI = projectUri.resolve(
          "${projectUri.rawPath}/packages/generic/gitlab-eclipse-plugin/$commitTag/$relativePath"
        )
        """{ "direct_asset_path": "/$relativePath", "name": "Eclipse Update Site ($relativePath)", "url": "$url" }"""
      }
    )
  }
}
