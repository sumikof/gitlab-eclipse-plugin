package com.gitlab.eclipse.utils

fun String.linesWithSeparators(): List<String> {
  if (isEmpty()) return emptyList()

  val result = mutableListOf<String>()
  var startIndex = 0
  var i = 0

  while (i < length) {
    if (this[i] == '\n' || this[i] == '\r') {
      // Handle CRLF as a single line ending
      val endIndex = if (this[i] == '\r' && i + 1 < length && this[i + 1] == '\n') {
        i + 2 // Include both \r and \n
      } else {
        i + 1 // Include just \n or \r
      }

      val line = substring(startIndex, endIndex)
      result.add(line)

      startIndex = endIndex
      i = endIndex
    } else {
      i++
    }
  }

  // Add remaining text if any
  if (startIndex < length) {
    result.add(substring(startIndex))
  }

  return result
}
