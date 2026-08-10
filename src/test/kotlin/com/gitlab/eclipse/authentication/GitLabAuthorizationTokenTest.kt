package com.gitlab.eclipse.authentication

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class GitLabAuthorizationTokenTest : DescribeSpec({
  describe("toString") {
    it("redacts both tokens and keeps the lifetime components") {
      val token = GitLabAuthorizationToken(
        accessToken = "access-s3cret",
        refreshToken = "refresh-s3cret",
        expiresIn = 7200,
        createdAt = 1_000L,
        tokenExpirationTimestamp = Instant.ofEpochSecond(8_080L),
      )

      "$token" shouldBe
        "GitLabAuthorizationToken(accessToken=***, refreshToken=***, expiresIn=7200, " +
        "createdAt=1000, tokenExpirationTimestamp=1970-01-01T02:14:40Z)"
    }

    it("reflects a changed expiresIn") {
      val token = GitLabAuthorizationToken("access-s3cret", "refresh-s3cret", 60, 1_000L, Instant.ofEpochSecond(8_080L))

      "$token" shouldBe
        "GitLabAuthorizationToken(accessToken=***, refreshToken=***, expiresIn=60, " +
        "createdAt=1000, tokenExpirationTimestamp=1970-01-01T02:14:40Z)"
    }

    it("reflects a changed createdAt") {
      val token = GitLabAuthorizationToken(
        accessToken = "access-s3cret",
        refreshToken = "refresh-s3cret",
        expiresIn = 7200,
        createdAt = 5_000L,
        tokenExpirationTimestamp = Instant.ofEpochSecond(8_080L),
      )

      "$token" shouldBe
        "GitLabAuthorizationToken(accessToken=***, refreshToken=***, expiresIn=7200, " +
        "createdAt=5000, tokenExpirationTimestamp=1970-01-01T02:14:40Z)"
    }

    // tokenExpirationTimestamp は §7.3.1 の exemption(有効期限であってトークンではない)= 出力に残す
    it("reflects a changed tokenExpirationTimestamp") {
      val token = GitLabAuthorizationToken(
        accessToken = "access-s3cret",
        refreshToken = "refresh-s3cret",
        expiresIn = 7200,
        createdAt = 1_000L,
        tokenExpirationTimestamp = Instant.ofEpochSecond(9_090L),
      )

      "$token" shouldBe
        "GitLabAuthorizationToken(accessToken=***, refreshToken=***, expiresIn=7200, " +
        "createdAt=1000, tokenExpirationTimestamp=1970-01-01T02:31:30Z)"
    }
  }
})
