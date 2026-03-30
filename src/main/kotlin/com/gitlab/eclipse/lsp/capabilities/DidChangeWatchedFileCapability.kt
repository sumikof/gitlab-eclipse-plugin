package com.gitlab.eclipse.lsp.capabilities

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.core.resources.IResourceChangeEvent
import org.eclipse.core.resources.IResourceChangeListener
import org.eclipse.core.resources.IResourceDelta
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*
import kotlin.io.path.toPath

class DidChangeWatchedFileCapability(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope,
  private val sendBatchDelayInMs: Long = 5000L // Default to 5 seconds
) : IResourceChangeListener {
  init {
    ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE)
  }

  private val registrar = HashMap<String, List<FileSystemWatcher>>()
  private val watchers
    get() = registrar.values.distinct().flatten()

  private lateinit var batchSendJob: Job
  private val processBatchedEvents: MutableList<FileEvent> = Collections.synchronizedList(mutableListOf())

  fun register(id: String, options: JsonObject) {
    if (registrar.containsKey(id)) {
      return
    }

    if (registrar.isEmpty()) {
      batchSendJob = coroutineScope.launch {
        while (isActive) {
          if (processBatchedEvents.isNotEmpty()) {
            languageServerWrapper.languageServer?.didChangeWatchedFiles(
              DidChangeWatchedFilesParams(processBatchedEvents.toList())
            )

            processBatchedEvents.clear()
          }

          delay(sendBatchDelayInMs)
        }
      }
    }

    registrar[id] = options.parse().watchers
  }

  fun unregister(id: String) {
    registrar.remove(id)

    if (registrar.isEmpty()) {
      batchSendJob.cancel()
    }
  }

  fun unregisterAll() {
    val watchers = registrar.keys.toList()

    watchers.forEach { id -> unregister(id) }
  }

  override fun resourceChanged(event: IResourceChangeEvent) {
    watchers.forEach { watcher ->
      event.delta.accept { delta ->
        // Let's only send "CHANGED" notifications on the affected resource to prevent unnecessary reloads.
        if (delta.affectedChildren.isNotEmpty() && delta.kind == IResourceDelta.CHANGED) {
          return@accept true // next child
        }

        // Let's ignore resources we can't find.
        val uri = delta.resource.locationURI
          ?: return@accept true // next child

        // Let's not send a "CHANGED" notification that would trigger refreshes of the every project files.
        if (workspaceFolders.any { it.uri == uri.toASCIIString() }) {
          return@accept true // next child
        }

        if (watcher.matches(uri.toPath())) {
          val lspFileEvent = when (delta.kind) {
            IResourceDelta.ADDED -> FileEvent(uri.toASCIIString(), FileChangeType.Created)
            IResourceDelta.REMOVED -> FileEvent(uri.toASCIIString(), FileChangeType.Deleted)
            IResourceDelta.CHANGED -> FileEvent(uri.toASCIIString(), FileChangeType.Changed)
            else -> return@accept true // next child
          }

          if (watcher.isInterestedBy(lspFileEvent)) {
            processBatchedEvents.add(lspFileEvent)
          }
        }

        return@accept false
      }
    }
  }

  fun documentChanged(uri: String) {
    watchers.forEach { watcher ->
      if (watcher.matches(URI(uri).toPath())) {
        val lspFileEvent = FileEvent(uri, FileChangeType.Changed)

        if (watcher.isInterestedBy(lspFileEvent)) {
          processBatchedEvents.add(lspFileEvent)
        }
      }
    }
  }

  private fun FileSystemWatcher.matches(pattern: Path): Boolean {
    return FileSystems.getDefault().getPathMatcher("glob:${globPattern.left}").matches(pattern)
  }

  private fun FileSystemWatcher.isInterestedBy(event: FileEvent): Boolean {
    // Watchers are interested in all WatchKind is none are specified
    val kindValue = kind ?: return true

    return when (event.type) {
      FileChangeType.Created -> (kindValue and WatchKind.Create) == WatchKind.Create
      FileChangeType.Deleted -> (kindValue and WatchKind.Delete) == WatchKind.Delete
      else -> (kindValue and WatchKind.Change) == WatchKind.Change
    }
  }
}

private fun JsonObject.parse(): DidChangeWatchedFilesRegistrationOptions {
  val watchers = this["watchers"]?.asJsonArray?.mapNotNull { element ->
    val watcher = element.asJsonObject

    val globPattern = watcher["globPattern"]?.asJsonPrimitive?.asString ?: return@mapNotNull null
    FileSystemWatcher(Either.forLeft(globPattern), watcher["kind"]?.asInt)
  }.orEmpty()

  return DidChangeWatchedFilesRegistrationOptions(watchers)
}
