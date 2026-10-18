package com.gitlab.eclipse.lsp

import com.google.gson.JsonParser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.io.File
import java.util.Properties

private const val PINNED_LSP_VERSION = "9.30.0"
private const val PINNED_TARBALL_SHA256 = "95dce7b5e950c116adfb93dbc312e992e81c09667f068a66d470963e282d4857"

/**
 * Locates the Gradle root (the directory holding `settings.gradle.kts`) by walking up from
 * `user.dir`, so the test reads the same `package.json` and `gradle.properties` the build reads.
 */
private fun projectRoot(): File =
  generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("Unable to locate the project root from ${System.getProperty("user.dir")}")

/**
 * Pins the bundled GitLab Language Server release and the sha256 of its tarball. The Gradle
 * `lspDownloadBinaries` task verifies the downloaded tarball against the same digest; this test
 * keeps the version and digest from drifting apart.
 */
class LanguageServerDistributionPinTest : DescribeSpec({
  describe("package.json") {
    it("pins @gitlab-org/gitlab-lsp to $PINNED_LSP_VERSION") {
      val packageJson = JsonParser.parseString(File(projectRoot(), "package.json").readText()).asJsonObject
      val pinned = packageJson.getAsJsonObject("dependencies")?.get("@gitlab-org/gitlab-lsp")?.asString

      pinned shouldBe PINNED_LSP_VERSION
    }
  }

  describe("gradle.properties") {
    val properties = Properties().apply {
      File(projectRoot(), "gradle.properties").inputStream().use { load(it) }
    }

    it("pins the tarball digest of the $PINNED_LSP_VERSION release") {
      properties.getProperty("gitlabLspTarballSha256") shouldBe PINNED_TARBALL_SHA256
    }

    it("holds the digest as 64 lowercase hex characters") {
      properties.getProperty("gitlabLspTarballSha256").orEmpty() shouldMatch Regex("^[0-9a-f]{64}$")
    }
  }
})
