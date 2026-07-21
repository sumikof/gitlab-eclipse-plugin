package com.gitlab.eclipse.api.http

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val KEY = "jdk.http.auth.tunneling.disabledSchemes"

class TunnelAuthSchemesTest : DescribeSpec({
  lateinit var saved: String

  beforeEach { saved = System.getProperty(KEY) ?: " " }
  afterEach {
    if (saved == " ") System.clearProperty(KEY) else System.setProperty(KEY, saved)
  }

  it("leaves an unset property untouched (never wipes net.properties/admin policy)") {
    System.clearProperty(KEY)
    relaxTunnelBasicAuthScheme()
    System.getProperty(KEY).shouldBeNull()
  }
  it("removes only Basic, preserving other schemes and honoring admin policy") {
    System.setProperty(KEY, "Basic, NTLM")
    relaxTunnelBasicAuthScheme()
    System.getProperty(KEY) shouldBe "NTLM"
  }
  it("is idempotent") {
    System.setProperty(KEY, "Basic")
    relaxTunnelBasicAuthScheme()
    relaxTunnelBasicAuthScheme()
    System.getProperty(KEY) shouldBe ""
  }
})
