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

  describe("HttpAgentOptions.toString") {
    it("redacts all three credential components") {
      val options = GitLabLanguageServerConfigurationParams.HttpAgentOptions(
        ca = "/home/user/ca.pem",
        cert = "/home/user/client.pem",
        certKey = "/home/user/client.key",
      )

      "$options" shouldBe "HttpAgentOptions(ca=***, cert=***, certKey=***)"
    }

    // §22.2: nullable 秘匿成分の null ケース。`!!` への退行を止める。
    it("says so when the components are absent") {
      val options = GitLabLanguageServerConfigurationParams.HttpAgentOptions(ca = null)

      "$options" shouldBe "HttpAgentOptions(ca=null, cert=null, certKey=null)"
    }
  }

  describe("GitLabLanguageServerConfigurationParams.toString") {
    it("redacts the token and delegates to the nested HttpAgentOptions") {
      val params = GitLabLanguageServerConfigurationParams(
        baseUrl = "https://gitlab.example.com",
        logLevel = "info",
        token = "s3cret",
        httpAgentOptions = GitLabLanguageServerConfigurationParams.HttpAgentOptions(
          ca = "/home/user/ca.pem",
          cert = "/home/user/client.pem",
          certKey = "/home/user/client.key",
        ),
        ignoreCertificateErrors = true,
      )

      "$params" shouldBe
        "GitLabLanguageServerConfigurationParams(baseUrl=https://gitlab.example.com, logLevel=info, " +
        "token=***, ignoreCertificateErrors=true, " +
        "httpAgentOptions=HttpAgentOptions(ca=***, cert=***, certKey=***))"
    }

    it("says so when the token and the agent options are absent") {
      val params = GitLabLanguageServerConfigurationParams(baseUrl = "https://gitlab.example.com")

      "$params" shouldBe
        "GitLabLanguageServerConfigurationParams(baseUrl=https://gitlab.example.com, logLevel=null, " +
        "token=null, ignoreCertificateErrors=false, httpAgentOptions=null)"
    }

    // A2(a): 非秘匿成分は値を変えたら出力も変わる。これが無いと toString を定数にしても通る。
    it("reflects a changed logLevel") {
      val params = GitLabLanguageServerConfigurationParams(baseUrl = "https://gitlab.example.com", logLevel = "debug")

      "$params" shouldBe
        "GitLabLanguageServerConfigurationParams(baseUrl=https://gitlab.example.com, logLevel=debug, " +
        "token=null, ignoreCertificateErrors=false, httpAgentOptions=null)"
    }
  }
})
