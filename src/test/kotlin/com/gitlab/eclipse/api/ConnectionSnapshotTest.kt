package com.gitlab.eclipse.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ConnectionSnapshotTest : DescribeSpec({
  describe("toString") {
    it("redacts the token and keeps the diagnostic components") {
      val snapshot = ConnectionSnapshot(
        instanceUrl = "https://gitlab.example.com",
        token = "s3cret-token-value",
        authFingerprint = "fp-abc",
        configGeneration = 7L,
      )

      "$snapshot" shouldBe
        "ConnectionSnapshot(instanceUrl=https://gitlab.example.com, token=***, authFingerprint=fp-abc, configGeneration=7)"
    }

    // A2(a): 非秘匿成分は値を変えたら出力も変わる。これが無いと toString を定数にしても通る。
    it("reflects a changed instanceUrl") {
      val snapshot = ConnectionSnapshot("https://other.example.com", "s3cret-token-value", "fp-abc", 7L)

      "$snapshot" shouldBe
        "ConnectionSnapshot(instanceUrl=https://other.example.com, token=***, authFingerprint=fp-abc, configGeneration=7)"
    }

    it("reflects a changed authFingerprint") {
      val snapshot = ConnectionSnapshot("https://gitlab.example.com", "s3cret-token-value", "fp-xyz", 7L)

      "$snapshot" shouldBe
        "ConnectionSnapshot(instanceUrl=https://gitlab.example.com, token=***, authFingerprint=fp-xyz, configGeneration=7)"
    }

    it("reflects a changed configGeneration") {
      val snapshot = ConnectionSnapshot("https://gitlab.example.com", "s3cret-token-value", "fp-abc", 99L)

      "$snapshot" shouldBe
        "ConnectionSnapshot(instanceUrl=https://gitlab.example.com, token=***, authFingerprint=fp-abc, configGeneration=99)"
    }
  }
})
