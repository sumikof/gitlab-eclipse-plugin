package com.gitlab.eclipse.lsp.configuration

import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class GitLabLanguageServerConfigurationParamsTest : DescribeSpec({
  describe("duo settings serialisation") {
    // The language server reads these from `settings.duoChat.enabled` and
    // `settings.duo.enabledWithoutGitlabProject`. Gson maps Kotlin property names
    // verbatim, so the nesting and spelling below are the wire contract.
    it("uses the key paths the language server expects") {
      val json = Gson().toJsonTree(
        GitLabLanguageServerConfigurationParams(
          duoChat = GitLabLanguageServerConfigurationParams.DuoChat(enabled = true),
          duo = GitLabLanguageServerConfigurationParams.Duo(enabledWithoutGitlabProject = false)
        )
      ).asJsonObject

      json["duoChat"].asJsonObject["enabled"].asBoolean shouldBe true
      json["duo"].asJsonObject["enabledWithoutGitlabProject"].asBoolean shouldBe false
    }

    it("omits the duo settings when they are not populated") {
      val json = Gson().toJsonTree(GitLabLanguageServerConfigurationParams()).asJsonObject

      json.has("duoChat") shouldBe false
      json.has("duo") shouldBe false
    }
  }
})
