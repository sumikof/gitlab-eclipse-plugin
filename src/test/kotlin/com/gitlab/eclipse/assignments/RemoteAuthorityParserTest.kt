package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RemoteAuthorityParserTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("parse") {
    it("fills in the default port for https and http") {
      RemoteAuthorityParser.parse("https://gitlab.com/g/p.git") shouldBe RemoteAuthority("gitlab.com", 443)
      RemoteAuthorityParser.parse("http://gitlab.local/g/p.git") shouldBe RemoteAuthority("gitlab.local", 80)
    }

    it("keeps an explicit http port") {
      RemoteAuthorityParser.parse("https://gitlab.local:8443/g/p.git") shouldBe
        RemoteAuthority("gitlab.local", 8443)
    }

    it("keeps an explicit ssh port, which the existing parser drops") {
      RemoteAuthorityParser.parse("ssh://git@gitlab.com:2222/g/p.git") shouldBe
        RemoteAuthority("gitlab.com", 2222)
    }

    it("distinguishes two ssh services on the same host by port") {
      // The whole reason this parser exists: degrading to a hostname comparison here would let a
      // different service on another port pass condition 3 (§11.1 / R3-5).
      RemoteAuthorityParser.parse("ssh://git@h.example:2222/x") shouldNotBe
        RemoteAuthorityParser.parse("ssh://git@h.example:3333/x")
    }

    it("claims no port for ssh:// without one, because an instance url never states one") {
      RemoteAuthorityParser.parse("ssh://git@gitlab.com/g/p.git") shouldBe
        RemoteAuthority("gitlab.com", null)
    }

    it("claims no port for an scp-like remote, which cannot express one") {
      RemoteAuthorityParser.parse("git@gitlab.com:g/p.git") shouldBe RemoteAuthority("gitlab.com", null)
    }

    it("drops the user info and lower-cases the host") {
      RemoteAuthorityParser.parse("https://user@GitLab.COM/g/p") shouldBe RemoteAuthority("gitlab.com", 443)
      RemoteAuthorityParser.parse("GIT@GitLab.com:g/p.git") shouldBe RemoteAuthority("gitlab.com", null)
    }

    it("handles a url with no path") {
      RemoteAuthorityParser.parse("https://gitlab.com") shouldBe RemoteAuthority("gitlab.com", 443)
    }

    it("returns null for something it cannot parse") {
      RemoteAuthorityParser.parse("") shouldBe null
      RemoteAuthorityParser.parse("   ") shouldBe null
      RemoteAuthorityParser.parse("just-a-word") shouldBe null
      RemoteAuthorityParser.parse("https://") shouldBe null
    }

    it("returns null rather than guessing when the port is not a number") {
      RemoteAuthorityParser.parse("https://gitlab.com:notaport/g/p") shouldBe null
    }
  }
})
