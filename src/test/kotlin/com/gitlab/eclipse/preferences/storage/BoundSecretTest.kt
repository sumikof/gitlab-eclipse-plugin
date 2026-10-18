package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The PAT field's binding to the instance URL in the URL field (#115, design §4.6). */
class BoundSecretTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var store: InstanceSecretStore
  lateinit var secret: BoundSecret

  fun stored(url: String): String? = store.storageFor(url)!!.getSecret("personal_access_token")

  beforeEach {
    val root = FakeSecurePreferences()
    store = InstanceSecretStore(root = { root }, configuredInstanceUrl = { "https://a.example" }, notify = {})
    store.storageFor("https://a.example")!!.putSecret("personal_access_token", "token-a")
    secret = BoundSecret(store::storageFor, "personal_access_token")
  }

  describe("load") {
    it("shows the token of the instance in the URL field") {
      secret.load("https://a.example") shouldBe "token-a"
      secret.load("https://b.example") shouldBe ""
    }

    it("shows nothing for a URL that cannot name an instance") {
      secret.load("https://user:pw@a.example") shouldBe ""
    }
  }

  describe("needsReload") {
    it("is needed only when the instance changes, not for another spelling of it") {
      secret.needsReload("https://a.example") shouldBe true
      secret.load("https://a.example")

      secret.needsReload("https://a.example/") shouldBe false
      secret.needsReload("https://A.example:443") shouldBe false
      secret.needsReload("https://b.example") shouldBe true
    }
  }

  describe("store") {
    it("writes nothing when the token was not changed") {
      secret.load("https://b.example")
      store.storageFor("https://b.example")!!.putSecret("personal_access_token", "written-elsewhere")

      secret.store("https://b.example", "")

      stored("https://b.example") shouldBe "written-elsewhere"
    }

    it("writes a changed token under the instance in the URL field") {
      secret.load("https://b.example")

      secret.store("https://b.example/", "token-b")

      stored("https://b.example") shouldBe "token-b"
      stored("https://a.example") shouldBe "token-a"
    }

    it("removes the token when the field is cleared") {
      secret.load("https://a.example")

      secret.store("https://a.example", "")

      stored("https://a.example").shouldBeNull()
    }

    it("never writes the token it showed for one instance under another") {
      secret.load("https://a.example")

      secret.store("https://b.example", "token-a")

      stored("https://b.example").shouldBeNull()
    }

    it("writes nothing for a URL that cannot name an instance") {
      secret.load("https://user:pw@a.example")

      secret.store("https://user:pw@a.example", "typed")

      stored("https://a.example") shouldBe "token-a"
    }

    it("treats what it wrote as the loaded value") {
      secret.load("https://a.example")
      secret.store("https://a.example", "token-a2")
      store.storageFor("https://a.example")!!.putSecret("personal_access_token", "written-elsewhere")

      secret.store("https://a.example", "token-a2")

      stored("https://a.example") shouldBe "written-elsewhere"
    }
  }
})
