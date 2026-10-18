package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.FakeSecurePreferences
import com.gitlab.eclipse.preferences.storage.InstanceSecretStore
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PatTokenProviderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var store: InstanceSecretStore
  lateinit var provider: PatTokenProvider

  beforeEach {
    val root = FakeSecurePreferences()
    store = InstanceSecretStore(root = { root }, configuredInstanceUrl = { "https://a.example" }, notify = {})
    provider = PatTokenProvider(store)
  }

  fun save(instanceUrl: String, token: String) =
    store.storageFor(instanceUrl)!!.putSecret(PatTokenProvider.SECRET_NAME, token)

  describe("PatTokenProvider") {
    it("returns the token of the instance it is asked for") {
      save("https://a.example", "token-a")
      save("https://b.example", "token-b")

      provider.getToken("https://a.example") shouldBe "token-a"
      provider.getToken("https://b.example/") shouldBe "token-b"
      provider.hasToken("https://b.example") shouldBe true
    }

    it("never returns instance A's token for instance B") {
      save("https://a.example", "token-a")

      provider.getToken("https://b.example") shouldBe ""
      provider.hasToken("https://b.example") shouldBe false
      provider.getToken("http://a.example") shouldBe ""
      provider.getToken("https://a.example:8443") shouldBe ""
    }

    it("has no token for a URL that cannot name an instance") {
      save("https://a.example", "token-a")

      provider.getToken("https://user:pw@a.example") shouldBe ""
      provider.hasToken("") shouldBe false
    }

    it("has no token once it is removed") {
      save("https://a.example", "token-a")
      store.storageFor("https://a.example")!!.removeSecret(PatTokenProvider.SECRET_NAME)

      provider.getToken("https://a.example") shouldBe ""
    }
  }
})
