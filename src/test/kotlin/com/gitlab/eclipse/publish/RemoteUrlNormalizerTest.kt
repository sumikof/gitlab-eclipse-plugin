package com.gitlab.eclipse.publish

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RemoteUrlNormalizerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("normalize") {
    it("drops a trailing .git and a trailing slash") {
      RemoteUrlNormalizer.normalize("https://gitlab.com/g/p.git") shouldBe "https://gitlab.com/g/p"
      RemoteUrlNormalizer.normalize("https://gitlab.com/g/p/") shouldBe "https://gitlab.com/g/p"
      RemoteUrlNormalizer.normalize("https://gitlab.com/g/p.git/") shouldBe "https://gitlab.com/g/p"
    }

    it("lower-cases the scheme and host but never the path") {
      RemoteUrlNormalizer.normalize("HTTPS://GitLab.COM/Group/Repo.git") shouldBe
        "https://gitlab.com/Group/Repo"
    }

    it("normalises an scp-like ssh remote without inventing a scheme") {
      RemoteUrlNormalizer.normalize("git@GitLab.com:Group/Repo.git") shouldBe "git@gitlab.com:Group/Repo"
    }

    it("keeps ssh and https remotes distinct") {
      RemoteUrlNormalizer.normalize("ssh://git@gitlab.com/g/p.git") shouldNotBe
        RemoteUrlNormalizer.normalize("https://gitlab.com/g/p.git")
    }

    it("keeps the port, because it distinguishes two remotes on one host") {
      RemoteUrlNormalizer.normalize("ssh://git@gitlab.com:2222/g/p.git") shouldBe
        "ssh://git@gitlab.com:2222/g/p"
    }

    it("trims surrounding whitespace") {
      RemoteUrlNormalizer.normalize("  https://gitlab.com/g/p.git  ") shouldBe "https://gitlab.com/g/p"
    }

    it("is idempotent") {
      val once = RemoteUrlNormalizer.normalize("HTTPS://GitLab.com/G/P.git/")
      RemoteUrlNormalizer.normalize(once) shouldBe once
    }

    it("leaves something it cannot parse alone rather than mangling it") {
      RemoteUrlNormalizer.normalize("not a url") shouldBe "not a url"
    }

    it("distinguishes two projects that differ only in path case") {
      RemoteUrlNormalizer.normalize("https://gitlab.com/g/Repo.git") shouldNotBe
        RemoteUrlNormalizer.normalize("https://gitlab.com/g/repo.git")
    }
  }
})
