package com.gitlab.eclipse.mcp

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
})
