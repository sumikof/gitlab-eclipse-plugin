package com.gitlab.eclipse.utils.system

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class SystemUtilsTest : DescribeSpec({
  val defaultOSValue = System.getProperty("os.name")
  val defaultArchValue = System.getProperty("os.arch")

  afterSpec {
    System.setProperty("os.name", defaultOSValue)
    System.setProperty("os.arch", defaultArchValue)
  }

  listOf(
    "Windows 10" to OS.WINDOWS,
    "Linux" to OS.LINUX,
    "Unix" to OS.LINUX,
    "Aix" to OS.LINUX,
    "macOS" to OS.MAC,
    "macOS" to OS.MAC,
    "Mac OS x" to OS.MAC, // darwin
  ).forEach { (os, result) ->
    it("should compute os for $os") {
      System.setProperty("os.name", os)

      SystemUtils.os shouldBe result
    }
  }

  listOf(
    "x86_64" to Arch.X86_64,
    "aarch64" to Arch.ARM64,
  ).forEach { (arch, result) ->
    it("should compute arch for $arch") {
      System.setProperty("os.arch", arch)

      SystemUtils.arch shouldBe result
    }
  }

  listOf(
    "Windows 10" to true,
    "Linux" to false,
    "macOS" to false,
  ).forEach { (os, result) ->
    it("should compute isWindows for $os") {
      System.setProperty("os.name", os)

      SystemUtils.isWindows() shouldBe result
    }
  }
})
