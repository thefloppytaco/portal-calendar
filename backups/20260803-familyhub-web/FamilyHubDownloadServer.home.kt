package com.portal.calendar

import android.content.Context
import fi.iki.elonen.NanoHTTPD

/** Serves the FamilyHub APK/download page and browser client on the Portal hostname. */
class FamilyHubDownloadServer(private val ctx: Context) : NanoHTTPD(PORT) {
    override fun serve(session: IHTTPSession): Response {
        val path = if (session.uri == "/") "/index.html" else session.uri
        val asset = when (path) {
            "/index.html" -> "familyhub/index.html"
            "/familyhub.html" -> "familyhub/familyhub.html"
            "/familyhub-companion.apk" -> "familyhub/familyhub-companion.apk"
            "/familyhub-qr.png" -> "familyhub/familyhub-qr.png"
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        val type = when {
            path.endsWith(".apk") -> "application/vnd.android.package-archive"
            path.endsWith(".png") -> "image/png"
            else -> "text/html; charset=utf-8"
        }
        return newFixedLengthResponse(Response.Status.OK, type, bytes.inputStream(), bytes.size.toLong())
    }

    companion object { const val PORT = 8765 }
}
