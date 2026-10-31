package com.gitlab.eclipse

import java.io.File

private const val SOURCE_ROOT = "src/main/kotlin/com/gitlab/eclipse"

/** Every production `.kt` file, relative to [SOURCE_ROOT], with its comments blanked out (line numbers kept). */
internal fun productionSources(): Map<String, String> =
  File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }
    .associate { file ->
      file.toRelativeString(File(SOURCE_ROOT)).replace(File.separatorChar, '/') to stripComments(file.readText())
    }

/**
 * [source] with every KDoc, block comment (nesting included) and `//` comment blanked out and every newline kept, so
 * line numbers survive. String and char literals are walked, not searched: a `//` inside `"https://x"` is not a
 * comment, so a call later on that line is still seen, and a `"` inside `'"'` does not open a string. A `${...}`
 * template inside a string is code again, nesting strings of its own.
 */
internal fun stripComments(source: String): String = CommentStripper(source).run()

internal class CommentStripper(private val source: String) {
  private val out = StringBuilder()
  private val templateDepths = ArrayDeque<Int>()
  private val templateRaw = ArrayDeque<Boolean>()
  private var inString = false
  private var raw = false
  private var i = 0

  fun run(): String {
    while (i < source.length) {
      if (inString) stepInString() else stepInCode()
    }
    return out.toString()
  }

  private fun startsWith(text: String) = source.startsWith(text, i)

  private fun take(count: Int) {
    val end = minOf(i + count, source.length)
    out.append(source, i, end)
    i = end
  }

  private fun stepInString() {
    val c = source[i]
    when {
      raw && startsWith("\"\"\"") -> {
        take(3)
        inString = false
      }
      !raw && c == '\\' -> take(2)
      !raw && (c == '"' || c == '\n') -> {
        take(1)
        inString = false
      }
      startsWith("\${") -> {
        take(2)
        templateDepths.addLast(0)
        templateRaw.addLast(raw)
        inString = false
      }
      else -> take(1)
    }
  }

  @Suppress("CyclomaticComplexMethod")
  private fun stepInCode() {
    val c = source[i]
    when {
      templateDepths.isNotEmpty() && c == '}' && templateDepths.last() == 0 -> {
        take(1)
        templateDepths.removeLast()
        raw = templateRaw.removeLast()
        inString = true
      }
      templateDepths.isNotEmpty() && (c == '{' || c == '}') -> {
        templateDepths[templateDepths.lastIndex] += if (c == '{') 1 else -1
        take(1)
      }
      startsWith("//") -> while (i < source.length && source[i] != '\n') i++
      startsWith("/*") -> skipBlockComment()
      startsWith("\"\"\"") -> {
        take(3)
        inString = true
        raw = true
      }
      c == '"' -> {
        take(1)
        inString = true
        raw = false
      }
      c == '\'' -> takeCharLiteral()
      else -> take(1)
    }
  }

  /** Skips a block comment, nested ones too, keeping its newlines. */
  private fun skipBlockComment() {
    var depth = 0
    do {
      when {
        startsWith("/*") -> {
          depth++
          i += 2
        }
        startsWith("*/") -> {
          depth--
          i += 2
        }
        else -> {
          if (source[i] == '\n') out.append('\n')
          i++
        }
      }
    } while (depth > 0 && i < source.length)
  }

  /** Copies a char literal whole, so `'"'` and `'\\''` do not open a string. A lone `'` is copied as itself. */
  private fun takeCharLiteral() {
    val closing = if (source.getOrNull(i + 1) == '\\') source.indexOf('\'', i + 3) else i + 2
    val closed = closing in (i + 2) until source.length && source[closing] == '\''
    take(if (closed) closing + 1 - i else 1)
  }
}
