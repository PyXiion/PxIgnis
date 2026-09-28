package ru.pyxiion.ignis.runtime

import org.luaj.vm2.LuaError
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * One place where script errors and warnings go: the server log, plus in-game chat for players who opted in
 * (see [notifier], set up by `IgnisRuntime` with the `px.ignis.prompt_errors` permission).
 *
 * The same message is sent to chat at most once per [repeatWindowMillis], so a handler that fails every tick
 * does not flood it. The log still gets every occurrence.
 */
object ScriptErrors {
    private val logger: Logger = LoggerFactory.getLogger("pxignis")

    /** Receives the chat line for an error or warning. Called on the thread that reported it. */
    @Volatile
    var notifier: ((Message) -> Unit)? = null

    var repeatWindowMillis: Long = 10_000
    var clock: () -> Long = System::currentTimeMillis

    private val lastSent = LinkedHashMap<String, Long>()

    data class Message(val severity: Severity, val context: String, val text: String)

    enum class Severity { ERROR, WARNING }

    /** Reports an error thrown by script code. [context] says what was running, e.g. "event 'player.join'". */
    fun error(context: String, error: Throwable) {
        val text = describe(error)
        if (error is LuaError) logger.error("{}: {}", context, text)
        else logger.error("$context: $text", error)
        notify(Message(Severity.ERROR, context, text))
    }

    fun error(context: String, text: String) {
        logger.error("{}: {}", context, text)
        notify(Message(Severity.ERROR, context, text))
    }

    fun warn(context: String, text: String) {
        logger.warn("{}: {}", context, text)
        notify(Message(Severity.WARNING, context, text))
    }

    private fun describe(error: Throwable): String = when (error) {
        is LuaError -> error.message ?: error.toString()
        else -> "${error.javaClass.simpleName}: ${error.message}"
    }

    private fun notify(message: Message) {
        val target = notifier ?: return
        val key = "${message.context}\u0000${message.text}"
        val now = clock()
        synchronized(lastSent) {
            val prev = lastSent[key]
            if (prev != null && now - prev < repeatWindowMillis) return
            lastSent[key] = now
            if (lastSent.size > 256) lastSent.entries.iterator().let { it.next(); it.remove() }
        }
        try {
            target(message)
        } catch (t: Throwable) {
            logger.warn("Could not deliver a script error to chat", t)
        }
    }

    /** Forgets the rate-limit history, so errors from freshly reloaded scripts are shown again. */
    fun resetRateLimit() {
        synchronized(lastSent) { lastSent.clear() }
    }
}
