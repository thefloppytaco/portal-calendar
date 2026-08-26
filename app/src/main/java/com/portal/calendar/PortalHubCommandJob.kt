package com.portal.calendar

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs queued Jarvis commands outside the provider's short synchronous timeout. */
class PortalHubCommandJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var retry = false
            while (true) {
                val command = PortalHubCommands.take(this) ?: break
                val completed = runCatching {
                    val taskRoute = Regex("""^Task owner:\s*(.+?)\s*\nCommand:\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
                        .find(command)
                    val calendarRoute = Regex("""^Calendar owner:\s*(.+?)\s*\nCommand:\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
                        .find(command)
                    if (calendarRoute != null) {
                        val owner = calendarRoute.groupValues[1].trim()
                        if (Gemini.isReady(this)) {
                            val proposal = org.json.JSONObject(Gemini.smartImport(this, calendarRoute.groupValues[2].trim(), null, null))
                            proposal.optJSONArray("events")?.let { events ->
                                for (i in 0 until events.length()) events.getJSONObject(i).put("calendarOwner", owner)
                            }
                            Gemini.applyProposals(this, proposal)
                        } else {
                            if (!Writers.setTargetForOwner(this, owner)) throw IllegalArgumentException("no connected calendar for $owner")
                            val directive = MagicWords.parseLoose(this, calendarRoute.groupValues[2].trim())
                            if (directive.kind != "event") throw IllegalArgumentException("that command did not contain a calendar event")
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            val (start, end) = CalDav.eventWindow(date, null, 60, true)
                            Writers.addEvent(this, directive.payload, start, end, true)
                        }
                    } else if (taskRoute != null) {
                        val owner = taskRoute.groupValues[1].trim()
                        val d = MagicWords.parseLoose(this, taskRoute.groupValues[2].trim())
                        if (d.kind == "groceries") MagicWords.execute(this, d, System.currentTimeMillis())
                        else FamilyLists.addOwnedTask(this, owner, d.payload)
                    } else if (Gemini.isReady(this)) {
                        Gemini.applyProposals(this, org.json.JSONObject(
                            Gemini.smartImport(this, command, null, null),
                        ))
                    } else {
                        MagicWords.execute(this, MagicWords.parseLoose(this, command),
                            System.currentTimeMillis())
                    }
                }.isSuccess
                if (!completed) {
                    PortalHubCommands.requeue(this, command)
                    retry = true
                    break
                }
            }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/** Small durable FIFO so accepted commands survive process death and network interruptions. */
object PortalHubCommands {
    private const val PREFS = "jarvis_commands"
    private const val KEY_QUEUE = "queue"

    @Synchronized
    fun enqueue(ctx: Context, command: String) {
        val queue = read(ctx)
        queue.put(command)
        write(ctx, queue)
    }

    @Synchronized
    fun take(ctx: Context): String? {
        val queue = read(ctx)
        if (queue.length() == 0) return null
        val command = queue.optString(0).takeIf { it.isNotBlank() }
        queue.remove(0)
        write(ctx, queue)
        return command
    }

    @Synchronized
    fun requeue(ctx: Context, command: String) {
        val old = read(ctx)
        val queue = JSONArray().put(command)
        for (i in 0 until old.length()) queue.put(old.optString(i))
        write(ctx, queue)
    }

    private fun read(ctx: Context) = runCatching {
        JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUEUE, "[]"))
    }.getOrElse { JSONArray() }

    private fun write(ctx: Context, queue: JSONArray) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUEUE, queue.toString()).apply()
    }
}
