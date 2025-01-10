package com.gitlab.eclipse.lsp.plugins.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PluginRequest(val type: String)
