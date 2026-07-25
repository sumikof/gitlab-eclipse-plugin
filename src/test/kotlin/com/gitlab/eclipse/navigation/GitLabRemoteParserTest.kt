package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class GitLabRemoteParserTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("parseGitLabRemote against instance https://gitlab.com") {
    // ported verbatim from git_remote_parser.test.ts:4-124
    data class Case(
      val input: String,
      val host: String,
      val hostname: String,
      val protocol: String,
      val namespace: String,
      val projectPath: String,
    )

    val cases = listOf(
      Case(
        input = "git@gitlab.com:fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "ssh:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "gitlab-ci@gitlab-mydomain.com:fatihacet/gitlab-vscode-extension.git",
        host = "gitlab-mydomain.com",
        hostname = "gitlab-mydomain.com",
        protocol = "ssh:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "ssh://git@gitlab.com:fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "ssh:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "git://git@gitlab.com:fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "ssh:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "http://git@gitlab.com/fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "http:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "http://gitlab.com/fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "http:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "https://git@gitlab.com/fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "https:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "https://gitlab.com/fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "https:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "git@gitlab.com:group/subgroup/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "ssh:",
        namespace = "group/subgroup",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "http://gitlab.com/group/subgroup/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "http:",
        namespace = "group/subgroup",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "https://gitlab.com/fatihacet/gitlab-vscode-extension",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "https:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "https://gitlab.company.com/fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.company.com",
        hostname = "gitlab.company.com",
        protocol = "https:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "https://gitlab.company.com:8443/fatihacet/gitlab-vscode-extension.git",
        host = "gitlab.company.com:8443",
        hostname = "gitlab.company.com",
        protocol = "https:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "https://gitlab.company.com:8443/fatihacet/gitlab-vscode-extension/",
        host = "gitlab.company.com:8443",
        hostname = "gitlab.company.com",
        protocol = "https:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "[git@example.com:2222]:fatihacet/gitlab-vscode-extension.git",
        host = "example.com",
        hostname = "example.com",
        protocol = "ssh:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "git@example.com:2222/fatihacet/gitlab-vscode-extension.git",
        host = "example.com",
        hostname = "example.com",
        protocol = "ssh:",
        namespace = "2222/fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "ssh://git@example.com:2222/fatihacet/gitlab-vscode-extension.git",
        host = "example.com",
        hostname = "example.com",
        protocol = "ssh:",
        namespace = "fatihacet",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "git@gitlab.com:/gitlab-org/gitlab-vscode-extension.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "ssh:",
        namespace = "gitlab-org",
        projectPath = "gitlab-vscode-extension",
      ),
      Case(
        input = "git@gitlab.com:/group/subgroup/project.git",
        host = "gitlab.com",
        hostname = "gitlab.com",
        protocol = "ssh:",
        namespace = "group/subgroup",
        projectPath = "project",
      ),
    )
    cases.forEach { case ->
      it("parses '${case.input}'") {
        val r = GitLabRemoteParser.parseGitLabRemote(case.input, "https://gitlab.com")!!
        r.host shouldBe case.host
        r.hostname shouldBe case.hostname
        r.protocol shouldBe case.protocol
        r.namespace shouldBe case.namespace
        r.projectPath shouldBe case.projectPath
        r.namespaceWithPath shouldBe "${case.namespace}/${case.projectPath}"
      }
    }
  }

  describe("custom instance route") {
    it("strips the custom route prefix") {
      val r = GitLabRemoteParser.parseGitLabRemote(
        "https://example.com/gitlab/fatihacet/gitlab-vscode-extension",
        "https://example.com/gitlab",
      )!!
      r.namespaceWithPath shouldBe "fatihacet/gitlab-vscode-extension"
      r.host shouldBe "example.com"
    }
    it("still parses a remote without the custom route") {
      val r = GitLabRemoteParser.parseGitLabRemote(
        "git@example.com:fatihacet/gitlab-vscode-extension.git",
        "https://example.com/gitlab",
      )!!
      r.namespaceWithPath shouldBe "fatihacet/gitlab-vscode-extension"
    }
  }

  describe("failure cases") {
    it("returns null for a remote without a namespace") {
      GitLabRemoteParser.parseGitLabRemote("git@host:no-namespace-repo.git", null) shouldBe null
    }
    it("returns null for a relative path") {
      GitLabRemoteParser.parseGitLabRemote("../relative/path", null) shouldBe null
    }
  }

  describe("remoteMatchesInstance (parseProject rule)") {
    it("same protocol requires host+port match") {
      val r = GitLabRemoteParser.parseGitLabRemote("https://example.com:4321/f/p.git")!!
      GitLabRemoteParser.remoteMatchesInstance(r, "https://example.com:1234") shouldBe false
    }
    it("different protocol matches on hostname only (ssh remote vs https instance with port)") {
      val r = GitLabRemoteParser.parseGitLabRemote("git@example.com:gitlab-org/gitlab-vscode-extension.git")!!
      GitLabRemoteParser.remoteMatchesInstance(r, "https://example.com:1234") shouldBe true
    }
    it("different hosts never match") {
      val r = GitLabRemoteParser.parseGitLabRemote("git@gitlab.com:gitlab-org/gitlab-vscode-extension.git")!!
      GitLabRemoteParser.remoteMatchesInstance(r, "https://example.com") shouldBe false
    }
    it("same host+protocol matches") {
      val r = GitLabRemoteParser.parseGitLabRemote("git@gitlab.com:gitlab-org/x.git")!!
      GitLabRemoteParser.remoteMatchesInstance(r, "https://gitlab.com") shouldBe true // ssh vs https → hostname rule
    }
  }
})
