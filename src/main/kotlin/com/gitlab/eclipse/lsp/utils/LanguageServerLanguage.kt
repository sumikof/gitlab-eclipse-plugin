package com.gitlab.eclipse.lsp.utils

import org.eclipse.core.resources.IFile

object LanguageServerLanguage {
  // Imported from https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/blob/main/code_suggestions_config.json
  enum class Language(val id: String, val humanReadableName: String, val extensions: List<String>) {
    C("c", "C", listOf("c", "h")),
    CPP("cpp", "C++", listOf("cpp", "cc", "cxx", "c++", "h", "hpp", "hxx", "h++")),
    CSharp("csharp", "C#", listOf("cs")),
    Go("go", "Go", listOf("go")),
    Haml("haml", "HAML", listOf("haml")),
    Handlebars("handlebars", "Handlebars", listOf("hbs", "handlebars")),
    Java("java", "Java", listOf("java")),
    JavaScript("javascript", "JavaScript", listOf("js")),
    JavascriptReact("javascriptreact", "JavaScript React", listOf("jsx")),
    Kotlin("kotlin", "Kotlin", listOf("kt", "kts")),
    Python("python", "Python", listOf("py")),
    PHP("php", "PHP", listOf("php")),
    Ruby("ruby", "Ruby", listOf("rb")),
    Rust("rust", "Rust", listOf("rs")),
    Scala("scala", "Scala", listOf("scala")),
    Shellscript("shellscript", "Shell", listOf("sh")),
    SQL("sql", "SQL", listOf("sql")),
    Swift("swift", "Swift", listOf("swift")),
    TypeScript("typescript", "TypeScript", listOf("ts")),
    TypeScriptReact("typescriptreact", "TypeScript React", listOf("tsx")),
    Svelte("svelte", "Svelte", listOf("svelte")),
    Terraform("terraform", "Terraform", listOf("tf", "tfvars")),
    Terragrunt("terragrunt", "Terragrunt", listOf("hcl")),
    Vue("vue", "Vue", listOf("vue"))
  }

  val IFile.language: Language?
    get() = normalizedExtension?.let { extension ->
      Language.entries.firstOrNull { it.extensions.contains(extension) }
    }

  // The language id sent to the language server in didOpen. Unknown languages fall back to the
  // lowercased extension (or file name), which is how the server normalizes additional languages.
  val IFile.languageId: String
    get() = language?.id ?: normalizedExtension ?: name.lowercase()

  /** Lowercase, trimmed and without a leading dot, as both language ids and extensions are compared. */
  fun normalizeIdentifier(identifier: String): String = identifier.trim().lowercase().removePrefix(".")

  private val IFile.normalizedExtension: String?
    get() = fileExtension?.let(::normalizeIdentifier)?.ifEmpty { null }
}
