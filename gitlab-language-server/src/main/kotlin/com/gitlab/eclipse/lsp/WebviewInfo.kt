package com.gitlab.eclipse.lsp

class WebviewInfo(var id: String, var title: String, var uris: List<String>) {
    override fun toString(): String {
        return "WebviewInfo [id=$id, title=$title, uris=$uris]"
    }
}