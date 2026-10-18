package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class InstanceSecretStoreTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var root: FakeSecurePreferences
  var configuredUrl = "https://gitlab.com"
  val notices = mutableListOf<String>()

  fun newStore() = InstanceSecretStore(
    root = { root },
    configuredInstanceUrl = { configuredUrl },
    notify = { notices.add(it) },
  )

  beforeEach {
    root = FakeSecurePreferences()
    configuredUrl = "https://gitlab.com"
    notices.clear()
  }

  describe("storageFor") {
    it("stores the tokens of different instances under different nodes") {
      val store = newStore()

      store.storageFor("https://a.example")!!.putSecret("personal_access_token", "token-a")
      store.storageFor("https://b.example")!!.putSecret("personal_access_token", "token-b")

      store.storageFor("https://a.example")!!.getSecret("personal_access_token") shouldBe "token-a"
      store.storageFor("https://b.example")!!.getSecret("personal_access_token") shouldBe "token-b"
      root.node("instances/https:\\2f\\2fa.example").get("personal_access_token", null) shouldBe "token-a"
    }

    it("uses one node for spellings of the same instance") {
      val store = newStore()
      store.storageFor("https://Example.com:443/gitlab/")!!.putSecret("personal_access_token", "token-a")

      store.storageFor("https://example.com/gitlab")!!.getSecret("personal_access_token") shouldBe "token-a"
    }

    it("never opens the legacy node for an instance") {
      root.node("hosts/gitlab.com").put("personal_access_token", "legacy", true)
      configuredUrl = "https://self.example"

      newStore().storageFor("https://self.example")!!.getSecret("personal_access_token").shouldBeNull()
    }

    it("has no storage for a URL that cannot name an instance") {
      newStore().storageFor("https://user:pw@example.com").shouldBeNull()
      newStore().storageFor(null).shouldBeNull()
    }

    it("opens the storage of a key directly") {
      val store = newStore()
      store.storageFor(GitLabInstanceKey.GITLAB_COM).putSecret("oauth_token", "json")

      store.storageFor("https://gitlab.com/")!!.readSecret("oauth_token") shouldBe "json"
    }
  }
})
