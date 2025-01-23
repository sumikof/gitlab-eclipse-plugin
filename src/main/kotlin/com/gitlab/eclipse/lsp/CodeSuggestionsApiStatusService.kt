package com.gitlab.eclipse.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CodeSuggestionsApiStatusService(private val scope: CoroutineScope) {

  private val _apiStatus = MutableStateFlow<ApiStatus?>(null)
  val apiStatus: StateFlow<ApiStatus?> = _apiStatus

  fun reportError() {
    scope.launch {
      _apiStatus.emit(ApiStatus.Error)
    }
  }

  fun reportRecovery() {
    scope.launch {
      _apiStatus.emit(ApiStatus.Recovery)
    }
  }

  sealed class ApiStatus {
    data object Error : ApiStatus()
    data object Recovery : ApiStatus()
  }
}
