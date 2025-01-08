package com.gitlab.eclipse.lsp

data class GitLabLanguageServerConfigurationParams(
  val baseUrl: String?,
  val logLevel: String?,
  val telemetry: Telemetry?,
  val token: String?,
  val codeCompletion: CodeCompletion?,
  val featureFlags: FeatureFlags?,
  val ignoreCertificateErrors: Boolean // String projectPath,
) {
  class Builder {
    private var baseUrl: String? = null
    private var logLevel: String? = null
    private var codeCompletion: CodeCompletion? = null
    private var featureFlags: FeatureFlags? = null
    private var telemetry: Telemetry? = null
    private var token: String? = null
    private var ignoreCertificateErrors: Boolean = false

    fun baseUrl(baseUrl: String?): Builder {
      this.baseUrl = baseUrl
      return this
    }

    fun codeCompletion(codeCompletion: CodeCompletion?): Builder {
      this.codeCompletion = codeCompletion
      return this
    }

    fun featureFlags(featureFlags: FeatureFlags?): Builder {
      this.featureFlags = featureFlags
      return this
    }

    fun ignoreCertificateErrors(ignoreCertificateErrors: Boolean): Builder {
      this.ignoreCertificateErrors = ignoreCertificateErrors
      return this
    }

    fun telemetry(featureFlags: FeatureFlags?): Builder {
      this.featureFlags = featureFlags
      return this
    }

    fun logLevel(logLevel: String?): Builder {
      this.logLevel = logLevel
      return this
    }

    fun telemetry(telemetry: Telemetry?): Builder {
      this.telemetry = telemetry
      return this
    }

    fun token(token: String?): Builder {
      this.token = token
      return this
    }

    fun build(): GitLabLanguageServerConfigurationParams {
      return GitLabLanguageServerConfigurationParams(
        baseUrl,
        logLevel,
        telemetry,
        token,
        codeCompletion,
        featureFlags,
        ignoreCertificateErrors
      )
    }
  }

  data class CodeCompletion(
    val enableSecretRedaction: Boolean,
    val disabledSupportedLanguages: List<String>,
    val additionalLanguages: List<String>
  )

  data class FeatureFlags(val remoteSecurityScans: Boolean, val streamCodeGenerations: Boolean) {
    class Builder {
      var remoteSecurityScans: Boolean = false
      var streamCodeGenerations: Boolean = false

      fun remoteSecurityScans(remoteSecurityScans: Boolean): Builder {
        this.remoteSecurityScans = remoteSecurityScans
        return this
      }

      fun streamCodeGenerations(streamCodeGenerations: Boolean): Builder {
        this.streamCodeGenerations = streamCodeGenerations
        return this
      }

      fun build(): FeatureFlags {
        return FeatureFlags(remoteSecurityScans, streamCodeGenerations)
      }
    }

    companion object {
      fun builder(): Builder {
        return Builder()
      }
    }
  }

  data class HttpAgentOptions(val ca: String, val cert: String, val certKey: String)

  data class Telemetry(val enabled: Boolean, val trackingUrl: String)

  companion object {
    fun builder(): Builder {
      return Builder()
    }
  }
}
