package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.equinox.security.storage.StorageException

/**
 * The one-time move of tokens from the host-less legacy node `gitlab/hosts/gitlab.com` (design §4.3). Driven
 * through [InstanceSecretStore], which runs it before any instance-scoped read or write.
 */
class LegacySecretMigrationTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var root: FakeSecurePreferences
  var configuredUrl = "https://gitlab.com"
  val notices = mutableListOf<String>()

  /** A new store is a new session: it remembers nothing but what secure storage holds. */
  fun newSession() = InstanceSecretStore(
    root = { root },
    configuredInstanceUrl = { configuredUrl },
    notify = { notices.add(it) },
  )

  fun legacy() = root.node("hosts/gitlab.com")

  beforeEach {
    root = FakeSecurePreferences()
    configuredUrl = "https://gitlab.com"
    notices.clear()
    legacy().put("personal_access_token", "legacy-pat", true)
    legacy().put("oauth_token", "legacy-oauth", true)
  }

  describe("when gitlab.com is configured") {
    it("moves the legacy PAT and OAuth token to the gitlab.com instance") {
      configuredUrl = "https://GitLab.com/"
      val store = newSession()

      val gitlabCom = store.storageFor("https://gitlab.com")!!
      gitlabCom.getSecret("personal_access_token") shouldBe "legacy-pat"
      gitlabCom.readSecret("oauth_token") shouldBe "legacy-oauth"
      root.node("instances/https:\\2f\\2fgitlab.com").encryptedOf("personal_access_token") shouldBe true
    }

    it("does not overwrite a token the gitlab.com instance already holds") {
      root.node("instances/https:\\2f\\2fgitlab.com").put("personal_access_token", "newer-pat", true)

      newSession().storageFor("https://gitlab.com")!!.getSecret("personal_access_token") shouldBe "newer-pat"
    }

    it("never hands the legacy token to another instance") {
      val store = newSession()

      store.storageFor("https://self.example")!!.getSecret("personal_access_token").shouldBeNull()
      store.storageFor("http://gitlab.com")!!.getSecret("personal_access_token").shouldBeNull()
    }
  }

  describe("when a self-managed instance is configured") {
    beforeEach { configuredUrl = "https://self.example" }

    it("moves nothing, to any instance") {
      val store = newSession()

      store.storageFor("https://self.example")!!.getSecret("personal_access_token").shouldBeNull()
      store.storageFor("https://gitlab.com")!!.getSecret("personal_access_token").shouldBeNull()
      store.storageFor("https://gitlab.com")!!.readSecret("oauth_token").shouldBeNull()
    }

    it("does not decrypt the legacy tokens, and says once that they were not moved") {
      val store = newSession()

      store.storageFor("https://self.example")
      store.storageFor("https://self.example")

      root.decryptedReads shouldBe 0
      notices shouldHaveSize 1
    }

    it("says nothing when there were no legacy tokens") {
      legacy().clear()

      newSession().storageFor("https://self.example")

      notices shouldHaveSize 0
    }
  }

  describe("idempotence") {
    it("records its decision unencrypted and saves it") {
      newSession().storageFor("https://gitlab.com")

      root.node("instances").get("legacyMigration", null) shouldBe "1"
      root.node("instances").encryptedOf("legacyMigration") shouldBe false
      (root.flushes > 0) shouldBe true
    }

    it("does not move a self-managed user's legacy token to gitlab.com after a later switch") {
      configuredUrl = "https://self.example"
      newSession().storageFor("https://self.example")

      configuredUrl = "https://gitlab.com"
      val later = newSession()

      later.storageFor("https://gitlab.com")!!.getSecret("personal_access_token").shouldBeNull()
      later.storageFor("https://gitlab.com")!!.readSecret("oauth_token").shouldBeNull()
    }

    it("does not bring back a removed or replaced token in later sessions") {
      newSession().storageFor("https://gitlab.com")!!.apply {
        removeSecret("personal_access_token")
        putSecret("oauth_token", "rotated")
      }

      repeat(2) {
        val store = newSession()
        store.storageFor("https://gitlab.com")!!.getSecret("personal_access_token").shouldBeNull()
        store.storageFor("https://gitlab.com")!!.readSecret("oauth_token") shouldBe "rotated"
      }
    }
  }

  describe("when the legacy tokens cannot be read (master password cancelled)") {
    beforeEach { root.failDecrypt = true }

    it("treats a token the instance does not hold yet as unreadable, not as absent") {
      val gitlabCom = newSession().storageFor("https://gitlab.com")!!

      shouldThrow<StorageException> { gitlabCom.readSecret("oauth_token") }
      gitlabCom.getSecret("personal_access_token").shouldBeNull()
    }

    it("prompts once, records no decision, and still accepts a new token") {
      val gitlabCom = newSession().storageFor("https://gitlab.com")!!
      root.decryptedReads shouldBe 1
      root.node("instances").get("legacyMigration", null).shouldBeNull()

      root.failDecrypt = false
      gitlabCom.putSecret("personal_access_token", "entered-now")
      gitlabCom.getSecret("personal_access_token") shouldBe "entered-now"
    }

    it("moves the tokens in the next session that can read them") {
      newSession().storageFor("https://gitlab.com")
      root.failDecrypt = false

      newSession().storageFor("https://gitlab.com")!!.readSecret("oauth_token") shouldBe "legacy-oauth"
    }
  }

  describe("logging") {
    it("logs no token and no instance URL") {
      val log = CapturedLog().also { it.install() }
      newSession().storageFor("https://gitlab.com")
      configuredUrl = "https://self.example"
      root.node("instances").remove("legacyMigration")
      newSession().storageFor("https://self.example")

      log.lines.isEmpty() shouldBe false
      listOf("legacy-pat", "legacy-oauth", "self.example").forEach { log.contains(it) shouldBe false }
    }
  }

  describe("without a legacy node") {
    it("does not create one") {
      root = FakeSecurePreferences()

      newSession().storageFor("https://gitlab.com")

      root.nodeExists("hosts") shouldBe false
    }
  }
})
