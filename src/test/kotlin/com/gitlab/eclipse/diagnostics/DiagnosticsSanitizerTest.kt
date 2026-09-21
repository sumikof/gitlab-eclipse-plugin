package com.gitlab.eclipse.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 設計 §10。参照実装 `src/common/diagnostics/sanitizer.ts` の 8 パターンについて、
 * **陽性(伏せられる)と陰性(伏せられない)の両方**を押さえる。
 *
 * 陰性が主役であることに注意: 過剰置換は診断を読めなくするので、漏れと同じくらい問題になる。
 */
class DiagnosticsSanitizerTest : DescribeSpec({
  val sanitizer = DiagnosticsSanitizer()

  describe("GitLab トークン") {
    it("4 つの接頭辞すべてを伏せる") {
      listOf("glpat-", "glptt-", "gloas-", "glpsc-").forEach { prefix ->
        val redacted = sanitizer.sanitize("token is ${prefix}abcDEF123_-xyz here")
        redacted shouldContain "[REDACTED_GITLAB_TOKEN]"
        redacted shouldNotContain "abcDEF123"
      }
    }

    it("大文字小文字を問わない") {
      sanitizer.sanitize("GLPAT-AbCdEf123") shouldContain "[REDACTED_GITLAB_TOKEN]"
    }

    it("同じ行に複数あってもすべて伏せる") {
      val redacted = sanitizer.sanitize("a glpat-one b glpat-two c")
      redacted shouldNotContain "one"
      redacted shouldNotContain "two"
      redacted shouldBe "a [REDACTED_GITLAB_TOKEN] b [REDACTED_GITLAB_TOKEN] c"
    }

    it("接頭辞に似ているだけの語は伏せない") {
      // 参照実装の文字クラスは `-` を必須にしている
      sanitizer.sanitize("global glpatient glossary") shouldBe "global glpatient glossary"
    }
  }

  describe("HTTP ヘッダのトークン") {
    it("3 つのヘッダ名それぞれで値を伏せ、ヘッダ名は残す") {
      listOf("PRIVATE-TOKEN", "JOB-TOKEN", "X-GITLAB-TOKEN").forEach { header ->
        val redacted = sanitizer.sanitize("$header: secretvalue123")
        redacted shouldContain header
        redacted shouldContain "[REDACTED_TOKEN]"
        redacted shouldNotContain "secretvalue123"
      }
    }
  }

  describe("Bearer / Basic") {
    it("Bearer の値を伏せ、語は残す") {
      val redacted = sanitizer.sanitize("Authorization: Bearer abc.def-123_x")
      redacted shouldBe "Authorization: Bearer [REDACTED_TOKEN]"
    }

    it("Basic の値を伏せる") {
      sanitizer.sanitize("Basic dXNlcjpwYXNz=") shouldBe "Basic [REDACTED_CREDENTIALS]"
    }

    it("文中の bearer という語だけでは伏せない") {
      // 後続に空白区切りのトークン様文字列が要る
      sanitizer.sanitize("the bearer of bad news") shouldContain "bad news"
    }
  }

  describe("パスワード") {
    it("コロン区切りの値を伏せる") {
      val redacted = sanitizer.sanitize("password: hunter2")
      redacted shouldContain "[REDACTED_PASSWORD]"
      redacted shouldNotContain "hunter2"
    }

    it("イコール区切りの値を伏せる") {
      sanitizer.sanitize("password=hunter2") shouldNotContain "hunter2"
    }

    it("引用符付きの値を伏せる") {
      sanitizer.sanitize("\"password\": \"hunter2\"") shouldNotContain "hunter2"
    }

    it("区切り記号のない文中の password は伏せない") {
      // 参照実装のコメント: "Invalid password format" のような文を壊さないため
      sanitizer.sanitize("Invalid password format") shouldBe "Invalid password format"
    }
  }

  describe("資格情報つき URL") {
    it("ユーザ名とパスワードを伏せ、ホストとパスは残す") {
      val redacted = sanitizer.sanitize("https://user:secret@gitlab.com/group/project.git")
      redacted shouldNotContain "secret"
      redacted shouldNotContain "user:"
      redacted shouldContain "gitlab.com"
      redacted shouldContain "/group/project.git"
    }

    it("資格情報のない URL はそのまま残す") {
      val url = "https://gitlab.com/group/project.git"
      sanitizer.sanitize(url) shouldBe url
    }
  }

  describe("ファイルパス") {
    it("Windows のユーザ名を伏せ、後続のパスは残す") {
      val redacted = sanitizer.sanitize("C:\\Users\\taro\\workspace\\file.kt")
      redacted shouldNotContain "taro"
      redacted shouldContain "[REDACTED_USER]"
      redacted shouldContain "workspace"
    }

    it("Linux のユーザ名を伏せる") {
      sanitizer.sanitize("/home/taro/project") shouldContain "/home/[REDACTED_USER]"
    }

    it("macOS のユーザ名を伏せる") {
      sanitizer.sanitize("/Users/taro/project") shouldContain "/Users/[REDACTED_USER]"
    }

    it("ユーザディレクトリでないパスはそのまま残す") {
      val path = "/opt/eclipse/plugins"
      sanitizer.sanitize(path) shouldBe path
    }
  }

  describe("既知トークンの実値置換(設計 §10.1)") {
    it("接頭辞を持たない self-managed 形式のトークンでも伏せる") {
      val token = "aB3xY9zQ7wE2rT5u"
      val redacted = sanitizer.sanitize("Authorization failed for $token", listOf(token))
      redacted shouldNotContain token
      redacted shouldContain "[REDACTED_TOKEN]"
    }

    it("同じトークンが複数箇所にあってもすべて伏せる") {
      val token = "aB3xY9zQ7wE2rT5u"
      val redacted = sanitizer.sanitize("$token then $token", listOf(token))
      redacted shouldBe "[REDACTED_TOKEN] then [REDACTED_TOKEN]"
    }

    it("正規表現のメタ文字を含むトークンをリテラルとして扱う") {
      val token = "a+b.c*d(e)f[g]"
      val redacted = sanitizer.sanitize("value=$token end", listOf(token))
      redacted shouldNotContain token
      redacted shouldContain "[REDACTED_TOKEN]"
    }

    it("短い値でも伏せる(Codex round 2 P1: 漏洩より過剰置換を選ぶ)") {
      // 長さ下限は撤廃した。短い設定値は他のどのパターンにも当たらないため、
      // 除外すると素通しになる。過剰置換は体裁の問題にすぎない。
      sanitizer.sanitize("the cat sat", listOf("cat")) shouldContain "[REDACTED_TOKEN]"
    }

    it("空文字やブランクを渡しても本文を壊さない") {
      sanitizer.sanitize("hello", listOf("", "   ")) shouldBe "hello"
    }
  }

  describe("JSON の機密キー(設計 §10.1)") {
    it("access_token / refresh_token の値を伏せ、キーは残す") {
      listOf("access_token", "refresh_token", "private_token").forEach { key ->
        val redacted = sanitizer.sanitize("""{"$key":"vAlUe123456"}""")
        redacted shouldContain key
        redacted shouldNotContain "vAlUe123456"
      }
    }

    it("空白を挟んだ形でも伏せる") {
      sanitizer.sanitize("""{ "access_token" : "vAlUe123456" }""") shouldNotContain "vAlUe123456"
    }

    it("機密でないキーはそのまま残す") {
      val json = """{"project_id":"1234567"}"""
      sanitizer.sanitize(json) shouldBe json
    }
  }

  describe("クエリパラメータの機密キー(設計 §10.1)") {
    it("private_token の値を伏せる") {
      val redacted = sanitizer.sanitize("GET /api/v4/user?private_token=vAlUe123456 HTTP/1.1")
      redacted shouldNotContain "vAlUe123456"
      redacted shouldContain "private_token=[REDACTED]"
    }

    it("2 つ目以降のパラメータでも伏せ、他のパラメータは残す") {
      val redacted = sanitizer.sanitize("/api?page=2&access_token=vAlUe123456&per_page=20")
      redacted shouldNotContain "vAlUe123456"
      redacted shouldContain "page=2"
      redacted shouldContain "per_page=20"
    }
  }

  describe("全体") {
    it("空文字を壊さない") {
      sanitizer.sanitize("") shouldBe ""
    }

    it("秘匿を含まない診断レポートをそのまま返す") {
      val report = """
        # GitLab for Eclipse Diagnostics

        ## Versions

        - IDE: Eclipse 4.36
        - Language Server version: 9.3.0
      """.trimIndent()
      sanitizer.sanitize(report) shouldBe report
    }

    it("複数種類が混在していてもすべて伏せる") {
      val redacted = sanitizer.sanitize(
        "glpat-aaa at /home/taro with PRIVATE-TOKEN: bbb and https://u:p@gitlab.com/x"
      )
      redacted shouldNotContain "glpat-aaa"
      redacted shouldNotContain "taro"
      redacted shouldNotContain "bbb"
      redacted shouldNotContain ":p@"
    }
  }
})
