package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.utils.system.OS
import com.gitlab.eclipse.utils.system.SystemUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

private const val APPDATA = "C:\\Users\\Ann\\AppData\\Roaming"

/** The location with this OS and these environment variables; [userHome] stands in for `user.home`. */
private fun location(
  isWindows: Boolean,
  env: Map<String, String> = emptyMap(),
  userHome: String? = null,
) = McpUserConfigLocation(isWindows = isWindows, env = env::get, userHome = { userHome })

class McpUserConfigLocationTest : DescribeSpec({
  describe("select (the LS 9.3.0 getDuoConfigDir rule)") {
    describe("on Windows") {
      it("uses APPDATA with GitLab/duo/mcp.json") {
        location(isWindows = true, env = mapOf("APPDATA" to APPDATA), userHome = "C:\\Users\\Ann").select() shouldBe
          McpUserConfigSelection.Chosen(McpUserConfigSource.APPDATA, APPDATA, listOf("GitLab", "duo", "mcp.json"))
      }

      it("has no user config when APPDATA is unset or empty, and does not fall back to the home directory") {
        listOf(emptyMap(), mapOf("APPDATA" to "")).forEach { env ->
          location(isWindows = true, env = env, userHome = "C:\\Users\\Ann").select() shouldBe
            McpUserConfigResolution.Missing(McpUserConfigSource.APPDATA)
        }
      }
    }

    describe("on Linux") {
      it("uses XDG_CONFIG_HOME with gitlab/duo/mcp.json and ignores APPDATA") {
        val env = mapOf("XDG_CONFIG_HOME" to "/home/ann/.config", "APPDATA" to "/appdata", "HOME" to "/home/ann")
        location(isWindows = false, env = env, userHome = "/home/ann").select() shouldBe
          McpUserConfigSelection.Chosen(
            McpUserConfigSource.XDG_CONFIG_HOME,
            "/home/ann/.config",
            listOf("gitlab", "duo", "mcp.json"),
          )
      }

      it("uses HOME with .gitlab/duo/mcp.json when XDG_CONFIG_HOME is unset, empty or blank") {
        listOf(null, "", "  \t").forEach { xdg ->
          val env = buildMap {
            put("HOME", "/home/ann")
            if (xdg != null) put("XDG_CONFIG_HOME", xdg)
          }
          location(isWindows = false, env = env, userHome = "/home/ann").select() shouldBe
            McpUserConfigSelection.Chosen(McpUserConfigSource.HOME, "/home/ann", listOf(".gitlab", "duo", "mcp.json"))
        }
      }

      it("decides blankness as JavaScript's trim() does, not as Kotlin's isBlank() does") {
        val env = mapOf("HOME" to "/home/ann")
        // U+FEFF is white space to trim() (so the server uses HOME) but not to isBlank().
        location(isWindows = false, env = env + ("XDG_CONFIG_HOME" to "\uFEFF")).select() shouldBe
          McpUserConfigSelection.Chosen(McpUserConfigSource.HOME, "/home/ann", listOf(".gitlab", "duo", "mcp.json"))
        // U+001C is white space to isBlank() but not to trim() (so the server uses it).
        location(isWindows = false, env = env + ("XDG_CONFIG_HOME" to "\u001C")).select() shouldBe
          McpUserConfigSelection.Chosen(
            McpUserConfigSource.XDG_CONFIG_HOME,
            "\u001C",
            listOf("gitlab", "duo", "mcp.json"),
          )
      }

      it("falls back to user.home when HOME is unset, as os.homedir() falls back to the passwd entry") {
        location(isWindows = false, userHome = "/home/ann").select() shouldBe
          McpUserConfigSelection.Chosen(McpUserConfigSource.HOME, "/home/ann", listOf(".gitlab", "duo", "mcp.json"))
      }

      it("has no user config when HOME is unset and user.home is unknown (null, empty or the JDK's \"?\")") {
        listOf(null, "", "?").forEach { userHome ->
          location(isWindows = false, userHome = userHome).select() shouldBe
            McpUserConfigResolution.Missing(McpUserConfigSource.HOME)
        }
      }
    }

    describe("on macOS (the server's non-win32 branch: no ~/Library location)") {
      it("uses XDG_CONFIG_HOME when it is set and HOME otherwise, exactly as on Linux") {
        location(isWindows = false, env = mapOf("XDG_CONFIG_HOME" to "/Users/ann/.config", "HOME" to "/Users/ann"))
          .select() shouldBe
          McpUserConfigSelection.Chosen(
            McpUserConfigSource.XDG_CONFIG_HOME,
            "/Users/ann/.config",
            listOf("gitlab", "duo", "mcp.json"),
          )
        location(isWindows = false, env = mapOf("HOME" to "/Users/ann"), userHome = "/Users/ann").select() shouldBe
          McpUserConfigSelection.Chosen(McpUserConfigSource.HOME, "/Users/ann", listOf(".gitlab", "duo", "mcp.json"))
      }
    }

    describe("matches LS 9.3.0 (values from running the server's own getDuoConfigDir, see issue #121)") {
      /*
       * Each row: OS, environment, user.home, and what the server's path.join gives — as the value and
       * segments it joins, or null where getDuoConfigDir returns undefined. Rows whose server result is a
       * relative path are refused later, by resolve().
       */
      data class Row(
        val windows: Boolean,
        val env: Map<String, String>,
        val home: String?,
        val joins: Pair<String, String>?,
      )
      val rows = listOf(
        Row(true, mapOf("APPDATA" to APPDATA), "C:\\Users\\Ann", APPDATA to "GitLab"),
        Row(
          true,
          mapOf("APPDATA" to "$APPDATA\\", "XDG_CONFIG_HOME" to "D:\\xdg"),
          "C:\\Users\\Ann",
          "$APPDATA\\" to "GitLab",
        ),
        Row(true, mapOf("APPDATA" to "C:/Users/Ann/AppData/Roaming"), null, "C:/Users/Ann/AppData/Roaming" to "GitLab"),
        Row(true, mapOf("APPDATA" to "   "), null, "   " to "GitLab"),
        Row(true, mapOf("APPDATA" to "relative\\dir"), null, "relative\\dir" to "GitLab"),
        Row(true, emptyMap(), "C:\\Users\\Ann", null),
        Row(false, mapOf("HOME" to "/home/ann"), "/home/ann", "/home/ann" to ".gitlab"),
        Row(false, mapOf("XDG_CONFIG_HOME" to "/home/ann/.config/"), "/home/ann", "/home/ann/.config/" to "gitlab"),
        Row(false, mapOf("XDG_CONFIG_HOME" to " /x ", "HOME" to "/home/ann"), null, " /x " to "gitlab"),
        Row(false, mapOf("XDG_CONFIG_HOME" to "rel/cfg"), null, "rel/cfg" to "gitlab"),
        Row(false, mapOf("APPDATA" to "/appdata", "HOME" to "/home/ann"), null, "/home/ann" to ".gitlab"),
        Row(false, mapOf("HOME" to ""), "/home/ann", "" to ".gitlab"),
      )
      rows.forEach { row ->
        it("${if (row.windows) "win32" else "posix"} ${row.env} home=${row.home}") {
          val selection = location(row.windows, row.env, row.home).select()
          val joins = row.joins
          if (joins == null) {
            (selection is McpUserConfigResolution.Missing) shouldBe true
          } else {
            val chosen = selection as McpUserConfigSelection.Chosen
            chosen.value shouldBe joins.first
            chosen.segments shouldBe listOf(joins.second, "duo", "mcp.json")
          }
        }
      }
    }
  }

  describe("resolve (the path, on this OS)") {
    it("joins an absolute value with the segments") {
      location(isWindows = false, env = mapOf("XDG_CONFIG_HOME" to "/home/ann/.config")).resolve() shouldBe
        McpUserConfigResolution.Target(
          Path.of("/home/ann/.config/gitlab/duo/mcp.json"),
          McpUserConfigSource.XDG_CONFIG_HOME,
        )
    }

    it("passes on a missing value: no path at all") {
      location(isWindows = true, userHome = "C:\\Users\\Ann").resolve() shouldBe
        McpUserConfigResolution.Missing(McpUserConfigSource.APPDATA)
      location(isWindows = false).resolve() shouldBe McpUserConfigResolution.Missing(McpUserConfigSource.HOME)
    }

    it("refuses a value that is not absolute (the server would resolve it against its working directory)") {
      val home = mapOf("HOME" to "/home/ann")
      listOf("rel/cfg", " /x ", "~/.config").forEach { xdg ->
        location(isWindows = false, env = home + ("XDG_CONFIG_HOME" to xdg)).resolve() shouldBe
          McpUserConfigResolution.NotAbsolute(McpUserConfigSource.XDG_CONFIG_HOME)
      }
      listOf("", "relative/home").forEach { value ->
        location(isWindows = false, env = mapOf("HOME" to value), userHome = "/home/ann").resolve() shouldBe
          McpUserConfigResolution.NotAbsolute(McpUserConfigSource.HOME)
      }
    }

    it("refuses a value that is not a valid path, without throwing") {
      location(isWindows = false, env = mapOf("XDG_CONFIG_HOME" to "/home/ann/\u0000cfg")).resolve() shouldBe
        McpUserConfigResolution.Invalid(McpUserConfigSource.XDG_CONFIG_HOME)
    }

    it("matches LS 9.3.0 on POSIX: the server's absolute results, and a refusal where it gets a relative one") {
      // Server result (from running its getDuoConfigDir) -> what this command does.
      val rows = listOf(
        Triple(mapOf("HOME" to "/home/ann"), "/home/ann/.gitlab/duo/mcp.json", McpUserConfigSource.HOME),
        Triple(
          mapOf("XDG_CONFIG_HOME" to "/home/ann/.config/", "HOME" to "/home/ann"),
          "/home/ann/.config/gitlab/duo/mcp.json",
          McpUserConfigSource.XDG_CONFIG_HOME,
        ),
        Triple(
          mapOf("XDG_CONFIG_HOME" to "  ", "HOME" to "/home/ann"),
          "/home/ann/.gitlab/duo/mcp.json",
          McpUserConfigSource.HOME,
        ),
        Triple(
          mapOf("APPDATA" to "/appdata", "HOME" to "/home/ann"),
          "/home/ann/.gitlab/duo/mcp.json",
          McpUserConfigSource.HOME,
        ),
        Triple(
          mapOf("XDG_CONFIG_HOME" to "/Users/ann/.config"),
          "/Users/ann/.config/gitlab/duo/mcp.json",
          McpUserConfigSource.XDG_CONFIG_HOME,
        ),
      )
      rows.forEach { (env, serverPath, source) ->
        location(isWindows = false, env = env).resolve() shouldBe
          McpUserConfigResolution.Target(Path.of(serverPath), source)
      }
      // " /x /gitlab/duo/mcp.json", "rel/cfg/gitlab/duo/mcp.json", ".gitlab/duo/mcp.json": relative on the server.
      location(isWindows = false, env = mapOf("XDG_CONFIG_HOME" to " /x ")).resolve() shouldBe
        McpUserConfigResolution.NotAbsolute(McpUserConfigSource.XDG_CONFIG_HOME)
      location(isWindows = false, env = mapOf("XDG_CONFIG_HOME" to "rel/cfg")).resolve() shouldBe
        McpUserConfigResolution.NotAbsolute(McpUserConfigSource.XDG_CONFIG_HOME)
      location(isWindows = false, env = mapOf("HOME" to "")).resolve() shouldBe
        McpUserConfigResolution.NotAbsolute(McpUserConfigSource.HOME)
    }

    it("defaults to this JVM's OS, environment and user.home") {
      McpUserConfigLocation().resolve() shouldBe
        McpUserConfigLocation(
          isWindows = SystemUtils.os == OS.WINDOWS,
          env = System::getenv,
          userHome = { System.getProperty("user.home") },
        ).resolve()
    }

    it("normalizes lexically, as the server's path.join does") {
      location(isWindows = false, env = mapOf("XDG_CONFIG_HOME" to "/home/ann/x/../.config/./")).resolve() shouldBe
        McpUserConfigResolution.Target(
          Path.of("/home/ann/.config/gitlab/duo/mcp.json"),
          McpUserConfigSource.XDG_CONFIG_HOME,
        )
    }
  }
})
