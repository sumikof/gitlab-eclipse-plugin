package com.gitlab.eclipse.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class McpConfigServiceTest : DescribeSpec({
  describe("path resolution") {
    it("resolves the user config path under the home directory") {
      val service = McpConfigService(homeDir = Path.of("/home/tester"))
      service.getUserConfigPath() shouldBe Path.of("/home/tester/.gitlab/duo/mcp.json")
    }

    it("resolves the workspace config path under the workspace root") {
      val service = McpConfigService(homeDir = Path.of("/home/tester"))
      service.getWorkspaceConfigPath(Path.of("/ws/root")) shouldBe
        Path.of("/ws/root/.gitlab/duo/mcp.json")
    }
  }

  describe("template") {
    it("is valid JSON with comments containing an mcpServers object") {
      McpConfigService.DEFAULT_CONFIG_TEMPLATE.contains("\"mcpServers\"") shouldBe true
    }
  }

  describe("ensureConfigFile") {
    it("creates the file with the template when it does not exist") {
      val tmp = kotlin.io.path.createTempDirectory("mcp-test")
      val service = McpConfigService()
      val target = tmp.resolve(".gitlab/duo/mcp.json")

      service.ensureConfigFile(target)

      java.nio.file.Files.exists(target) shouldBe true
      java.nio.file.Files.readString(target) shouldBe McpConfigService.DEFAULT_CONFIG_TEMPLATE
    }

    it("does not overwrite an existing file") {
      val tmp = kotlin.io.path.createTempDirectory("mcp-test")
      val service = McpConfigService()
      val target = tmp.resolve(".gitlab/duo/mcp.json")
      java.nio.file.Files.createDirectories(target.parent)
      java.nio.file.Files.writeString(target, "custom-content")

      service.ensureConfigFile(target)

      java.nio.file.Files.readString(target) shouldBe "custom-content"
    }

    it("throws when the target path is an existing directory") {
      val tmp = kotlin.io.path.createTempDirectory("mcp-test")
      val service = McpConfigService()
      val target = tmp.resolve(".gitlab/duo/mcp.json")
      java.nio.file.Files.createDirectories(target)

      shouldThrow<IllegalStateException> { service.ensureConfigFile(target) }
    }
  }
})
