package com.portal.calendar

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/** Jarvis external-tool entry point for family changes owned by PortalHub. */
class PortalHubToolProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_INVOKE) return result(false, "Unsupported provider method.")
        if (!isTrustedCaller()) return result(false, "Caller is not authorized to control PortalHub.")
        if (arg != TOOL_FAMILY_COMMAND) return result(false, "Unknown PortalHub command.")

        val request = runCatching { JSONObject(extras?.getString(EXTRA_ARGS).orEmpty()) }
            .getOrElse { return result(false, "PortalHub command arguments were invalid.") }
        val command = request.optString("command").trim()
        if (command.isEmpty()) return result(false, "A PortalHub command is required.")
        val owner = request.optString("calendarOwner").trim()
        val taskOwner = request.optString("taskOwner").trim()
        val lower = command.lowercase()
        val grocery = lower.contains("grocer") || lower.contains("shopping")
        val calendar = lower.contains("calendar") || lower.contains("event") || lower.contains("schedule")
        val task = !grocery && (lower.contains("task") || lower.contains("to-do") || lower.contains("todo"))
        if (calendar && owner.isEmpty())
            return result(false, "Ask whose calendar to use (Matt, Juanita, Kaylee, or Jesse), then retry with calendarOwner.")
        if (task && taskOwner.isEmpty())
            return result(false, "Ask whose task list to use (Matt, Juanita, Kaylee, or Jesse), then retry with taskOwner.")
        if (owner.isEmpty() && request.has("calendar"))
            return result(false, "Calendar names are ambiguous; ask whose calendar to use (Matt, Juanita, Kaylee, or Jesse).")

        val ctx = context ?: return result(false, "PortalHub is unavailable.")
        val routed = buildString {
            if (owner.isNotEmpty()) append("Calendar owner: $owner\n")
            if (taskOwner.isNotEmpty()) append("Task owner: $taskOwner\n")
            append("Command: $command")
        }
        PortalHubCommands.enqueue(ctx, routed)
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        val scheduled = scheduler?.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, PortalHubCommandJob::class.java))
                .setMinimumLatency(0)
                .setOverrideDeadline(1_000)
                .build(),
        ) == JobScheduler.RESULT_SUCCESS
        return if (scheduled) {
            // The JobService runs asynchronously, so a synchronous success
            // would falsely tell Jarvis that the write already happened.
            result(false, "PortalHub queued the command for verification; do not report it as completed yet.")
        } else {
            result(false, "PortalHub could not schedule that command.")
        }
    }

    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        return context?.packageManager?.getPackagesForUid(uid)
            ?.any { it == JARVIS_PACKAGE } == true
    }

    private fun result(ok: Boolean, message: String) = Bundle().apply {
        putString(EXTRA_RESULT, JSONObject().put("success", ok).put("message", message).toString())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?) = 0

    private companion object {
        const val METHOD_INVOKE = "invoke"
        const val EXTRA_ARGS = "com.portal.assistant.tools.extra.ARGS"
        const val EXTRA_RESULT = "com.portal.assistant.tools.extra.RESULT"
        const val JARVIS_PACKAGE = "com.portal.assistant"
        const val TOOL_FAMILY_COMMAND = "com.portal.calendar.family_command"
        const val JOB_ID = 0x5048
    }
}
