package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
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

  "hostMatchesInstance: http remote with same host matches (scheme difference ignored)" {
    configurer().hostMatchesInstance("http://gitlab.example.com/group/proj.git", instance) shouldBe true
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

  // --- transportConfigCallback ---

  "transportConfigCallback: sets plugin-owned ssh session factory on SshTransport only" {
    val ssh = mockk<SshTransport>(relaxed = true)
    configurer().transportConfigCallback().configure(ssh)
    verify(exactly = 1) { ssh.setSshSessionFactory(any()) }
  }

  "transportConfigCallback: leaves non-ssh transport untouched" {
    val plain = mockk<Transport>()
    configurer().transportConfigCallback().configure(plain)
    verify { plain wasNot Called }
  }

  // --- applyAuth ---

  "applyAuth: sets credentials provider and transport config callback, returns command" {
    val command = mockk<FetchCommand>(relaxed = true)
    val result = configurer().applyAuth(command, "https://gitlab.example.com/g/p.git", instance)
    result shouldBe command
    verify(exactly = 1) { command.setCredentialsProvider(any<UsernamePasswordCredentialsProvider>()) }
    verify(exactly = 1) { command.setTransportConfigCallback(any()) }
  }

  "applyAuth: sets null credentials provider for non-matching host but still sets callback" {
    val command = mockk<FetchCommand>(relaxed = true)
    configurer().applyAuth(command, "git@gitlab.example.com:g/p.git", instance)
    verify(exactly = 1) { command.setCredentialsProvider(isNull()) }
    verify(exactly = 1) { command.setTransportConfigCallback(any()) }
  }
})
