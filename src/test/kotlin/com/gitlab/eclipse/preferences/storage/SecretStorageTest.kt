package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eclipse.equinox.security.storage.StorageException

class SecretStorageTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var node: FakeSecurePreferences
  lateinit var log: CapturedLog

  beforeEach {
    node = FakeSecurePreferences()
    log = CapturedLog().also { it.install() }
  }

  describe("a storage opened on a node") {
    it("encrypts what it writes and reads it back") {
      val storage = SecretStorage(node)

      storage.putSecret("personal_access_token", "value-1")

      node.encryptedOf("personal_access_token") shouldBe true
      storage.getSecret("personal_access_token") shouldBe "value-1"
      storage.readSecret("personal_access_token") shouldBe "value-1"
    }

    it("removes a secret") {
      val storage = SecretStorage(node)
      storage.putSecret("personal_access_token", "value-1")

      storage.removeSecret("personal_access_token")

      node.keys().toList() shouldBe emptyList()
      storage.getSecret("personal_access_token").shouldBeNull()
    }
  }

  describe("the read guard") {
    it("turns a read of an absent key into a storage failure when the guard refuses") {
      val storage = SecretStorage(node) { throw StorageException(StorageException.NO_PASSWORD, "guarded") }

      shouldThrow<StorageException> { storage.readSecret("oauth_token") }
      storage.getSecret("oauth_token").shouldBeNull()
    }

    it("is not consulted for a key the node holds") {
      val storage = SecretStorage(node) { throw StorageException(StorageException.NO_PASSWORD, "guarded") }
      storage.putSecret("oauth_token", "value-1")

      storage.readSecret("oauth_token") shouldBe "value-1"
    }
  }

  describe("logging") {
    it("logs a failed read by type name only, without the exception message") {
      node.put("personal_access_token", "value-1", true)
      node.failDecrypt = true
      val storage = SecretStorage(node)

      storage.getSecret("personal_access_token").shouldBeNull()
      shouldThrow<StorageException> { storage.readSecret("personal_access_token") }

      log.contains(StorageException::class.java.name) shouldBe true
      log.contains("cancelled") shouldBe false
      log.contains("value-1") shouldBe false
    }
  }
})
