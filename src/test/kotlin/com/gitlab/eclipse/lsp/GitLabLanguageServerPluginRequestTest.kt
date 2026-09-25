package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.lang.reflect.ParameterizedType
import java.util.concurrent.CompletableFuture

/**
 * Plan (1/5) §3 / A20: the extension → plugin request shares its JSON-RPC name with the client-side
 * receiver, so lsp4j parses its response with the receiver's `Object` return type. The declaration
 * must therefore promise nothing more specific than `Any?`.
 */
class GitLabLanguageServerPluginRequestTest : DescribeSpec({

  val method = GitLabLanguageServer::class.java.getMethod("pluginRequest", ExtensionToPluginRequest::class.java)

  it("\$/gitlab/plugin/request として宣言される") {
    method.getAnnotation(JsonRequest::class.java).value shouldBe "\$/gitlab/plugin/request"
    ServiceEndpoints.getSupportedMethods(GitLabLanguageServer::class.java).keys shouldContain
      "\$/gitlab/plugin/request"
  }

  it("戻り値型は CompletableFuture<Any?> に固定される") {
    val returnType = method.genericReturnType as ParameterizedType

    returnType.rawType shouldBe CompletableFuture::class.java
    returnType.actualTypeArguments.single() shouldBe Any::class.java
  }
})
