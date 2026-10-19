package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.system.Arch
import com.gitlab.eclipse.utils.system.OS
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LanguageServerTargetTest : DescribeSpec({
  describe("languageServerTarget") {
    // Every OS/arch combination the plugin can detect, so a new enum value cannot slip through unmapped.
    val expected = mapOf(
      (OS.MAC to Arch.ARM64) to LanguageServerTarget(
        "com.gitlab.eclipse.gitlab-language-server.cocoa.macosx.aarch64",
        "gitlab-lsp-macos-arm64",
      ),
      (OS.MAC to Arch.X86_64) to LanguageServerTarget(
        "com.gitlab.eclipse.gitlab-language-server.cocoa.macosx.x86_64",
        "gitlab-lsp-macos-x64",
      ),
      (OS.LINUX to Arch.X86_64) to LanguageServerTarget(
        "com.gitlab.eclipse.gitlab-language-server.gtk.linux.x86_64",
        "gitlab-lsp-linux-x64",
      ),
      (OS.WINDOWS to Arch.ARM64) to LanguageServerTarget(
        "com.gitlab.eclipse.gitlab-language-server.win32.win32.aarch64",
        "gitlab-lsp-win-arm64.exe",
      ),
      (OS.WINDOWS to Arch.X86_64) to LanguageServerTarget(
        "com.gitlab.eclipse.gitlab-language-server.win32.win32.x86_64",
        "gitlab-lsp-win-x64.exe",
      ),
    )

    expected.forEach { (platform, target) ->
      val (os, arch) = platform
      it("maps $os/$arch to its fragment and binary") {
        languageServerTarget(os, arch) shouldBe target
      }
    }

    it("reports linux/aarch64 as unsupported instead of falling back to the x86_64 fragment") {
      languageServerTarget(OS.LINUX, Arch.ARM64).shouldBeNull()
    }

    Arch.entries.forEach { arch ->
      it("reports an unknown OS on $arch as unsupported") {
        languageServerTarget(OS.UNKNOWN, arch).shouldBeNull()
      }
    }

    it("covers every OS/arch combination") {
      val supported = OS.entries.flatMap { os -> Arch.entries.map { os to it } }
        .filter { (os, arch) -> languageServerTarget(os, arch) != null }
        .toSet()
      supported shouldBe expected.keys
    }
  }

  describe("platformLabel") {
    it("names the platform the way the error log reports it") {
      platformLabel(OS.LINUX, Arch.ARM64) shouldBe "linux/aarch64"
      platformLabel(OS.UNKNOWN, Arch.X86_64) shouldBe "unknown/x86_64"
    }
  }
})
