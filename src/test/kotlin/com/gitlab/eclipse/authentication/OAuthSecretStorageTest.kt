package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.google.gson.GsonBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.equinox.security.storage.StorageException
import java.time.Instant

class OAuthSecretStorageTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("OAuthSecretStorage") {
    val mockSecretStorage = mockk<SecretStorage>()
    lateinit var oauthSecretStorage: OAuthSecretStorage

    beforeTest {
      oauthSecretStorage = OAuthSecretStorage(mockSecretStorage)
    }

    afterTest {
      clearAllMocks()
    }

    describe("getOAuthToken") {
      it("returns null when secret is empty") {
        every { mockSecretStorage.readSecret(any()) } returns ""

        val result = oauthSecretStorage.getOAuthToken()

        result.shouldBeNull()
        verify { mockSecretStorage.readSecret(OAuthSecretStorage.Companion.SECRET_NAME) }
      }

      it("returns token when secret exists") {
        val createdAt = Instant.now().epochSecond
        val expirationTime = Instant.ofEpochSecond(createdAt.plus(3600).minus(120))
        val gson = GsonBuilder()
          .registerTypeAdapter(GitLabAuthorizationToken::class.java, GitLabAuthorizationTokenDeserializer())
          .create()

        val token = GitLabAuthorizationToken("test-token", "test-refresh", 3600, createdAt)
        val jsonOAuthToken = gson.toJson(token)

        every { mockSecretStorage.readSecret(any()) } returns jsonOAuthToken

        val result = oauthSecretStorage.getOAuthToken()

        result?.accessToken shouldBe "test-token"
        result?.refreshToken shouldBe "test-refresh"
        result?.tokenExpirationTimestamp shouldBe expirationTime
        verify { mockSecretStorage.readSecret(OAuthSecretStorage.Companion.SECRET_NAME) }
      }

      it("propagates a secure storage failure instead of reporting an empty storage") {
        every { mockSecretStorage.readSecret(any()) } throws
          StorageException(StorageException.NO_PASSWORD, "cancelled")

        shouldThrow<StorageException> { oauthSecretStorage.getOAuthToken() }
      }
    }

    describe("setOAuthToken") {
      it("stores token in secret storage") {
        val token = GitLabAuthorizationToken("valid_token", "refresh_token", 3600, Instant.now().epochSecond)
        every { mockSecretStorage.putSecret(any(), any()) } just runs

        oauthSecretStorage.setOAuthToken(token)

        verify { mockSecretStorage.putSecret(OAuthSecretStorage.Companion.SECRET_NAME, any()) }
      }
    }
  }
})
