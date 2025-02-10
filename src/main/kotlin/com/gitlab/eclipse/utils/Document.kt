package com.gitlab.eclipse.utils

import org.eclipse.core.filebuffers.FileBuffers
import org.eclipse.jface.text.IDocument

val IDocument.uri: String
  get() =
    FileBuffers
      .getTextFileBufferManager()
      .getTextFileBuffer(this)
      .fileStore
      .toURI()
      .toASCIIString()
