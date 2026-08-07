package com.gitlab.eclipse.lsp.diagnostics

import java.net.URI

/**
 * LS が返す URI 文字列を、レジストリのキーとして使える正規化済み絶対パスへ落とす。
 *
 * 本プラグインは `IResource.getLocationURI().toASCIIString()`(= `file:/abs/path`。スラッシュ 1 本)
 * を送るが、LS 側は vscode-uri ベースで `file:///abs/path` に正規化しうる(§6.1 P6 / U-1)。
 * **文字列比較をしてはならない**ため、decode 済みパスに落として比較キーとする。
 */
object DiagnosticUri {
  fun normalize(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    val path = uri.path ?: return null
    if (!path.startsWith("/")) return null
    return normalizeWindowsDrive(path)
  }

  private const val DRIVE_PREFIX_LENGTH = 3
  private const val DRIVE_LETTER_INDEX = 1
  private const val DRIVE_COLON_INDEX = 2

  /** `/c:/x` → `/C:/x`。Eclipse も LS もドライブレターの大小を保証しない(U-5)。 */
  private fun normalizeWindowsDrive(path: String): String {
    if (!hasWindowsDrivePrefix(path)) return path
    val drive = path[DRIVE_LETTER_INDEX].uppercaseChar()
    return "/" + drive + path.substring(DRIVE_COLON_INDEX)
  }

  private fun hasWindowsDrivePrefix(path: String): Boolean {
    if (path.length < DRIVE_PREFIX_LENGTH) return false
    if (path[0] != '/') return false
    if (path[DRIVE_COLON_INDEX] != ':') return false
    return path[DRIVE_LETTER_INDEX].isLetter()
  }
}
