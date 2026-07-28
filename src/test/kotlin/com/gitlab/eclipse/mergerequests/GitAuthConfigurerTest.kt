package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

class GitAuthConfigurerTest : StringSpec({
  val instance = "https://gitlab.example.com"
  fun configurer(token: String = "secret-token"): GitAuthConfigurer {
    val tokenManager = mockk<GitLabTokenProviderManager> { every { getToken() } returns token }
    return GitAuthConfigurer(tokenManager)
  }

  // --- hostMatchesInstance (pure logic) ---

  "hostMatchesInstance: https remote with same host matches" {
    configurer().hostMatchesInstance("https://gitlab.example.com/group/proj.git", instance) shouldBe true
  }

  "hostMatchesInstance: different host does not match" {
    configurer().hostMatchesInstance("https://other.example.com/group/proj.git", instance) shouldBe false
  }

  "hostMatchesInstance: http remote does NOT match an https instance (no plaintext token leak)" {
    configurer().hostMatchesInstance("http://gitlab.example.com/group/proj.git", instance) shouldBe false
  }

  "hostMatchesInstance: http remote matches an http instance (schemes equal)" {
    configurer().hostMatchesInstance("http://gitlab.example.com/g/p.git", "http://gitlab.example.com") shouldBe true
  }

  "hostMatchesInstance: explicit default port matches the implied default port" {
    configurer().hostMatchesInstance("https://gitlab.example.com:443/g/p.git", instance) shouldBe true
  }

  "hostMatchesInstance: a different port on the same host does not match" {
    configurer().hostMatchesInstance("https://gitlab.example.com:8443/g/p.git", instance) shouldBe false
  }

  "hostMatchesInstance: scp-like remote is not an HTTP remote" {
    configurer().hostMatchesInstance("git@gitlab.example.com:group/proj.git", instance) shouldBe false
  }

  "hostMatchesInstance: ssh remote is not an HTTP remote" {
    configurer().hostMatchesInstance("ssh://git@gitlab.example.com/group/proj.git", instance) shouldBe false
  }

  "hostMatchesInstance: blank remote or instance does not match" {
    configurer().hostMatchesInstance("", instance) shouldBe false
    configurer().hostMatchesInstance("   ", instance) shouldBe false
    configurer().hostMatchesInstance("https://gitlab.example.com/g/p.git", "") shouldBe false
  }

  "hostMatchesInstance: host comparison is case-insensitive" {
    configurer().hostMatchesInstance("https://GitLab.Example.COM/group/proj.git", instance) shouldBe true
  }

  "hostMatchesInstance: unparseable remote does not match" {
    configurer().hostMatchesInstance("http://[invalid", instance) shouldBe false
  }

  // --- credentialsProviderFor ---

  "credentialsProviderFor: same host yields oauth2 username/password provider" {
    val provider = configurer().credentialsProviderFor("https://gitlab.example.com/g/p.git", instance)
    provider.shouldBeInstanceOf<UsernamePasswordCredentialsProvider>()
  }

  "credentialsProviderFor: different host yields no provider" {
    configurer().credentialsProviderFor("https://other.example.com/g/p.git", instance).shouldBeNull()
  }

  "credentialsProviderFor: ssh remote yields no provider" {
    configurer().credentialsProviderFor("git@gitlab.example.com:g/p.git", instance).shouldBeNull()
  }

  "credentialsProviderFor: empty token yields no provider even on host match" {
    configurer(token = "").credentialsProviderFor("https://gitlab.example.com/g/p.git", instance).shouldBeNull()
  }

  // --- transportConfigCallback (per-transport, keyed off each transport's own URI) ---

  "transportConfigCallback: sets plugin ssh factory on an SSH transport and no token (ssh URI)" {
    val ssh = mockk<SshTransport>(relaxed = true) {
      every { uri } returns URIish("ssh://git@gitlab.example.com/g/p.git")
    }
    configurer().transportConfigCallback(instance).configure(ssh)
    verify(exactly = 1) { ssh.setSshSessionFactory(any()) }
    verify(exactly = 0) { ssh.setCredentialsProvider(any()) }
  }

  "transportConfigCallback: sets oauth2 credentials on a GitLab HTTPS transport, no ssh factory" {
    val http = mockk<Transport>(relaxed = true) {
      every { uri } returns URIish("https://gitlab.example.com/g/p.git")
    }
    configurer().transportConfigCallback(instance).configure(http)
    verify(exactly = 1) { http.setCredentialsProvider(any<UsernamePasswordCredentialsProvider>()) }
  }

  "transportConfigCallback: leaves a non-GitLab HTTPS transport's default provider intact" {
    val http = mockk<Transport>(relaxed = true) {
      every { uri } returns URIish("https://other.example.com/g/p.git")
    }
    configurer().transportConfigCallback(instance).configure(http)
    verify(exactly = 0) { http.setCredentialsProvider(any()) }
  }

  "transportConfigCallback: an http downgrade of an https instance gets no token" {
    val http = mockk<Transport>(relaxed = true) {
      every { uri } returns URIish("http://gitlab.example.com/g/p.git")
    }
    configurer().transportConfigCallback(instance).configure(http)
    verify(exactly = 0) { http.setCredentialsProvider(any()) }
  }

  "transportConfigCallback: empty token yields no credentials even on the GitLab host" {
    val http = mockk<Transport>(relaxed = true) {
      every { uri } returns URIish("https://gitlab.example.com/g/p.git")
    }
    configurer(token = "").transportConfigCallback(instance).configure(http)
    verify(exactly = 0) { http.setCredentialsProvider(any()) }
  }

  // --- applyAuth ---

  "applyAuth: wires the transport-config callback and never sets a command-level provider" {
    val command = mockk<FetchCommand>(relaxed = true)
    val result = configurer().applyAuth(command, instance)
    result shouldBe command
    verify(exactly = 1) { command.setTransportConfigCallback(any()) }
    verify(exactly = 0) { command.setCredentialsProvider(any()) }
  }
})
