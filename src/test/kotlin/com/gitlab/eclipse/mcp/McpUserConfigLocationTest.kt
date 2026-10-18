package com.gitlab.eclipse.mcp

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

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
        location(isWindows = false, env = env + ("XDG_CONFIG_HOME" to "﻿")).select() shouldBe
          McpUserConfigSelection.Chosen(McpUserConfigSource.HOME, "/home/ann", listOf(".gitlab", "duo", "mcp.json"))
        // U+001C is white space to isBlank() but not to trim() (so the server uses it).
        location(isWindows = false, env = env + ("XDG_CONFIG_HOME" to "\u001C")).select() shouldBe
          McpUserConfigSelection.Chosen(McpUserConfigSource.XDG_CONFIG_HOME, "\u001C", listOf("gitlab", "duo", "mcp.json"))
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
      data class Row(val windows: Boolean, val env: Map<String, String>, val home: String?, val joins: Pair<String, String>?)
      val rows = listOf(
        Row(true, mapOf("APPDATA" to APPDATA), "C:\\Users\\Ann", APPDATA to "GitLab"),
        Row(true, mapOf("APPDATA" to "$APPDATA\\", "XDG_CONFIG_HOME" to "D:\\xdg"), "C:\\Users\\Ann", "$APPDATA\\" to "GitLab"),
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
})
