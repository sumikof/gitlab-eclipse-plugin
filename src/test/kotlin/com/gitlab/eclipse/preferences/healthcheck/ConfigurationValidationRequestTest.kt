package com.gitlab.eclipse.preferences.healthcheck

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ConfigurationValidationRequestTest : DescribeSpec({
  describe("toString") {
    it("redacts the token and keeps the base url") {
      val request = ConfigurationValidationRequest(baseUrl = "https://gitlab.example.com", token = "s3cret")

      "$request" shouldBe "ConfigurationValidationRequest(baseUrl=https://gitlab.example.com, token=***)"
    }

    it("reflects a changed baseUrl") {
      val request = ConfigurationValidationRequest(baseUrl = "https://other.example.com", token = "s3cret")

      "$request" shouldBe "ConfigurationValidationRequest(baseUrl=https://other.example.com, token=***)"
    }
  }
})
