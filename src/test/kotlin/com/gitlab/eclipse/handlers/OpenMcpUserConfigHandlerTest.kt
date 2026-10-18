package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mcp.McpConfigService
import com.gitlab.eclipse.mcp.McpUserConfigLocation
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.PartInitException
import org.osgi.framework.Bundle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/*
 * User-facing wording, spelled out rather than imported: the production constants would make a
 * test pass whatever they were changed to (same reasoning as OpenMcpWorkspaceConfigHandlerTest).
 */
private const val APPDATA_MISSING_TEXT =
  "GitLab Duo reads no MCP user configuration because the APPDATA environment variable is not set."
private const val HOME_MISSING_TEXT =
  "Could not find your home directory, so the MCP user configuration cannot be opened."
private const val XDG_NOT_ABSOLUTE_TEXT =
  "The MCP user configuration cannot be opened: XDG_CONFIG_HOME is not an absolute path. " +
    "Set it to an absolute path and restart Eclipse."
private const val HOME_NOT_ABSOLUTE_TEXT =
  "The MCP user configuration cannot be opened: HOME is not an absolute path. " +
    "Set it to an absolute path and restart Eclipse."
private const val XDG_INVALID_TEXT =
  "The MCP user configuration cannot be opened: XDG_CONFIG_HOME is not a valid path. Fix it and restart Eclipse."
private const val CREATE_FAILED_TEXT = "Could not create the MCP user configuration. See the Error Log."
private const val OPEN_FAILED_TEXT = "Could not open the MCP user configuration. See the Error Log."

/**
 * The handler on a real temporary disk: [env] and [userHome] feed a real [McpUserConfigLocation], the
 * editor seam records what it was asked to open (answering [opens]), and notices are collected.
 */
private class UserConfigFixture(isWindows: Boolean = false) {
  val disk: Path = createTempDirectory("mcp-user-config")
  val home: Path = Files.createDirectories(disk.resolve("home"))
  val env = mutableMapOf("HOME" to home.toString())
  var userHome: String? = home.toString()
  var opens: (Path) -> Boolean = { true }
  val opened = mutableListOf<Path>()
  val notices = mutableListOf<String>()

  val handler = OpenMcpUserConfigHandler(
    location = McpUserConfigLocation(isWindows = isWindows, env = { env[it] }, userHome = { userHome }),
    openEditor = { _, path ->
      opened.add(path)
      opens(path)
    },
    notify = { notices += it },
  )

  fun run() = handler.execute(ExecutionEvent())

  /** Where the pre-fix handler wrote: `user.home/.gitlab/duo/mcp.json`. */
  val legacyConfig: Path get() = home.resolve(".gitlab/duo/mcp.json")
}

class OpenMcpUserConfigHandlerTest : DescribeSpec({
  extension(LoggingKotestExtension)

  describe("execute") {
    it("creates the template where LS 9.3.0 reads it with XDG_CONFIG_HOME set, and opens that file") {
      val fixture = UserConfigFixture()
      val xdg = fixture.disk.resolve("xdg")
      fixture.env["XDG_CONFIG_HOME"] = xdg.toString()

      fixture.run().shouldBeNull()

      val config = xdg.resolve("gitlab/duo/mcp.json")
      Files.readString(config) shouldBe McpConfigService.DEFAULT_CONFIG_TEMPLATE
      fixture.opened shouldContainExactly listOf(config)
      Files.exists(fixture.legacyConfig) shouldBe false
      fixture.notices.shouldBeEmpty()
    }

    it("creates the template under APPDATA\\GitLab\\duo on Windows, ignoring XDG_CONFIG_HOME and the home directory") {
      val fixture = UserConfigFixture(isWindows = true)
      // An absolute path on the test's OS stands in for %APPDATA%: the Windows rule is the same join.
      val appData = fixture.disk.resolve("AppData/Roaming")
      fixture.env["APPDATA"] = appData.toString()
      fixture.env["XDG_CONFIG_HOME"] = fixture.disk.resolve("xdg").toString()

      fixture.run()

      val config = appData.resolve("GitLab/duo/mcp.json")
      Files.readString(config) shouldBe McpConfigService.DEFAULT_CONFIG_TEMPLATE
      fixture.opened shouldContainExactly listOf(config)
      Files.exists(fixture.legacyConfig) shouldBe false
      Files.exists(fixture.disk.resolve("xdg")) shouldBe false
    }

    it("uses HOME/.gitlab/duo/mcp.json without XDG_CONFIG_HOME, even when user.home differs") {
      val fixture = UserConfigFixture()
      fixture.userHome = fixture.disk.resolve("passwd-home").toString()

      fixture.run()

      Files.readString(fixture.legacyConfig) shouldBe McpConfigService.DEFAULT_CONFIG_TEMPLATE
      fixture.opened shouldContainExactly listOf(fixture.legacyConfig)
      Files.exists(fixture.disk.resolve("passwd-home")) shouldBe false
    }

    it("keeps an existing config and is safe to run again: same file opened, content untouched") {
      val fixture = UserConfigFixture()
      Files.createDirectories(fixture.legacyConfig.parent)
      Files.writeString(fixture.legacyConfig, "{ \"mcpServers\": { \"mine\": {} } }")

      fixture.run()
      fixture.run()

      Files.readString(fixture.legacyConfig) shouldBe "{ \"mcpServers\": { \"mine\": {} } }"
      fixture.opened shouldContainExactly listOf(fixture.legacyConfig, fixture.legacyConfig)
      fixture.notices.shouldBeEmpty()
    }

    it("logs neither paths nor environment values nor exception messages") {
      val messages = mutableListOf<String>()
      val log = mockk<ILog>()
      every { log.info(capture(messages)) } just Runs
      every { log.error(capture(messages)) } just Runs
      every { log.error(any(), any()) } just Runs
      every { log.warn(capture(messages)) } just Runs
      every { Platform.getLog(any<Bundle>()) } returns log
      val fixture = UserConfigFixture()
      val xdg = fixture.disk.resolve("xdg-secret")
      fixture.env["XDG_CONFIG_HOME"] = xdg.toString()

      fixture.run() // success
      fixture.opens = { throw PartInitException("cannot open $it") }
      fixture.run() // open failure
      Files.delete(xdg.resolve("gitlab/duo/mcp.json"))
      Files.createDirectories(xdg.resolve("gitlab/duo/mcp.json"))
      fixture.run() // creation failure
      fixture.env["XDG_CONFIG_HOME"] = "relative-secret"
      fixture.run() // refusal

      messages shouldHaveSize 4 // one line per run: opened, open failure, creation failure, refusal
      messages.forEach { message ->
        message shouldNotContain fixture.disk.toString()
        message shouldNotContain "secret"
      }
      fixture.notices.forEach { it shouldNotContain "secret" }
      verify(exactly = 0) { log.error(any(), any()) }
    }

    it("tells the user when the config cannot be created, without opening anything or throwing") {
      val fixture = UserConfigFixture()
      Files.createDirectories(fixture.legacyConfig) // HOME/.gitlab/duo/mcp.json is a directory

      fixture.run().shouldBeNull()

      fixture.notices shouldContainExactly listOf(CREATE_FAILED_TEXT)
      fixture.opened.shouldBeEmpty()
    }

    it("tells the user when no editor could be opened (no page, or the editor failed), without throwing") {
      listOf<(Path) -> Boolean>({ false }, { throw PartInitException("cannot open $it") }).forEach { open ->
        val fixture = UserConfigFixture()
        fixture.opens = open

        fixture.run().shouldBeNull()

        fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
        Files.readString(fixture.legacyConfig) shouldBe McpConfigService.DEFAULT_CONFIG_TEMPLATE
      }
    }

    describe("tells the user why nothing was opened, and creates nothing anywhere") {
      it("when the location is missing, not absolute or invalid") {
        data class Case(val windows: Boolean, val env: Map<String, String>, val userHome: String?, val notice: String)
        val cases = listOf(
          Case(true, emptyMap(), "C:\\Users\\Ann", APPDATA_MISSING_TEXT),
          Case(false, emptyMap(), null, HOME_MISSING_TEXT),
          Case(false, mapOf("XDG_CONFIG_HOME" to "relative/cfg"), "ignored", XDG_NOT_ABSOLUTE_TEXT),
          Case(false, mapOf("HOME" to ""), "ignored", HOME_NOT_ABSOLUTE_TEXT),
          Case(false, mapOf("XDG_CONFIG_HOME" to "/x/\u0000"), "ignored", XDG_INVALID_TEXT),
        )
        cases.forEach { case ->
          val fixture = UserConfigFixture(isWindows = case.windows)
          fixture.env.clear()
          fixture.env.putAll(case.env)
          fixture.userHome = case.userHome

          fixture.run().shouldBeNull()

          fixture.notices shouldContainExactly listOf(case.notice)
          fixture.opened.shouldBeEmpty()
          Files.exists(fixture.legacyConfig) shouldBe false
        }
      }
    }
  }
})
