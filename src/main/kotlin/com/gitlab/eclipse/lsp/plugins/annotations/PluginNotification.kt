package com.gitlab.eclipse.lsp.plugins.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PluginNotification(val type: String)
