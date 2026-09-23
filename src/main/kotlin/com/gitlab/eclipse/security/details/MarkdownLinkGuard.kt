package com.gitlab.eclipse.security.details

/**
 * Allowlists the destination of every markdown link and image in an already HTML-escaped finding
 * description (PR #89 review, `VulnerabilityProjection`).
 *
 * The bundled webview renderer builds `<a href="…">` / `<img src="…">` by plain concatenation and
 * never checks the scheme, so the destination is the whole attack surface once raw HTML is escaped.
 * A destination is **allowed** when it is `http:`, `https:` or `mailto:`, or a relative reference
 * (path, query or fragment). Anything else — `javascript:`, `vbscript:`, `data:`, `file:`, any other
 * scheme, and network-path references (`//host`, `\\host`) that leave for another host — gets a `#`
 * put in front of it, which turns it into a same-document fragment: the link text stays readable and
 * the target can no longer run or navigate anywhere.
 *
 * ## Why a scan and not a markdown parser
 *
 * Deciding exactly what the bundle's lexer will treat as a link would mean re-implementing it, and
 * then trusting that copy to stay in step with every language server update. Instead this
 * over-approximates: *every* `](` and `]:` is treated as the start of a destination, whether or not
 * the lexer would agree. The only cost of a false positive is a visible `#` in prose shaped like
 * `a[0]:foo:bar`, and only when what follows parses as a disallowed scheme.
 *
 * The scheme is read the way a browser would read it after the lexer is done, erring towards
 * rejection: leading whitespace is skipped — every character JavaScript's `\s` matches, U+FEFF
 * included, since the lexer swallows those before the destination — and C0 controls, DEL, U+2424
 * (which the lexer turns into a newline, which browsers remove from URLs) and backslashes are ignored
 * anywhere in the scheme or a leading `//`, so `java\u2424script:`, `/\u2424/host` and `javascript\:`
 * are all caught.
 *
 * Entity tricks such as `&#106;avascript:` need no handling here: the text is escaped first, so the
 * `&` arrives as `&amp;` and the browser never decodes a scheme out of it.
 */
internal object MarkdownLinkGuard {

  /** [escapedMarkdown] with every disallowed link or image destination made a fragment. */
  fun neutralize(escapedMarkdown: String): String {
    val out = StringBuilder(escapedMarkdown.length)
    var i = 0
    while (i < escapedMarkdown.length) {
      if (!opensDestination(escapedMarkdown, i)) {
        out.append(escapedMarkdown[i])
        i++
        continue
      }
      var start = i + OPENER_LENGTH
      while (start < escapedMarkdown.length && isSkippedBeforeDestination(escapedMarkdown[start])) start++
      out.append(escapedMarkdown, i, start)
      if (!isAllowed(escapedMarkdown, start)) out.append(FRAGMENT)
      i = start
    }
    return out.toString()
  }

  /** `](` opens an inline link or image destination; `]:` opens a reference definition's. */
  private fun opensDestination(text: String, at: Int): Boolean =
    at + 1 < text.length && text[at] == ']' && (text[at + 1] == '(' || text[at + 1] == ':')

  /** Everything JavaScript's `\s` matches (the lexer's `\(\s*`) plus what [isIgnorable] drops. */
  private fun isSkippedBeforeDestination(c: Char): Boolean =
    c.isWhitespace() || c == BYTE_ORDER_MARK || isIgnorable(c)

  /**
   * C0 controls and DEL, which browsers drop from URLs in part, and U+2424, which the lexer turns into
   * a newline that browsers then drop — so none of them may hide a scheme or split a `//`.
   */
  private fun isIgnorable(c: Char): Boolean = c < ' ' || c == DEL || c == LEXER_NEWLINE

  private fun isAllowed(text: String, start: Int): Boolean {
    if (isNetworkPath(text, start)) return false
    val scheme = schemeAt(text, start) ?: return true
    return scheme.lowercase() in ALLOWED_SCHEMES
  }

  /** Two leading slashes of either kind: browsers read `\` as `/` in web URLs. */
  private fun isNetworkPath(text: String, start: Int): Boolean {
    var slashes = 0
    var i = start
    while (i < text.length && slashes < 2) {
      val c = text[i++]
      when {
        isIgnorable(c) -> Unit
        c == '/' || c == '\\' -> slashes++
        else -> return false
      }
    }
    return slashes == 2
  }

  /** The scheme the destination at [start] names, or `null` when it is a relative reference. */
  private fun schemeAt(text: String, start: Int): String? {
    val scheme = StringBuilder()
    for (i in start until text.length) {
      val c = text[i]
      when {
        isIgnorable(c) || c == '\\' -> Unit
        isSchemeChar(c) -> scheme.append(c)
        c == ':' -> return scheme.takeIf { it.isNotEmpty() && isAsciiLetter(it[0]) }?.toString()
        else -> return null
      }
    }
    return null
  }

  /** RFC 3986 §3.1: `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`. */
  private fun isSchemeChar(c: Char): Boolean = isAsciiLetter(c) || c in '0'..'9' || c == '+' || c == '-' || c == '.'

  private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

  private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")
  private const val OPENER_LENGTH = 2
  private const val FRAGMENT = '#'
  private const val DEL = '\u007f'
  private const val LEXER_NEWLINE = '\u2424'
  private const val BYTE_ORDER_MARK = '\uFEFF'
}
