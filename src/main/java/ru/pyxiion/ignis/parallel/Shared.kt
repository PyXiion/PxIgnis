package ru.pyxiion.ignis.parallel

import ru.pyxiion.luanova.vm2.LuaError
import ru.pyxiion.luanova.vm2.LuaFunction
import ru.pyxiion.luanova.vm2.LuaString
import ru.pyxiion.luanova.vm2.LuaTable
import ru.pyxiion.luanova.vm2.LuaValue
import ru.pyxiion.luanova.vm2.Varargs
import ru.pyxiion.ignis.asObject
import ru.pyxiion.ignis.luaVarFunction
import ru.pyxiion.ignis.toLuaValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The only mutable state that main and worker threads share. Everything inside is thread-safe; values are limited
 * to booleans, numbers, strings, frozen tables and other shared objects (see [SharedLib.encode]).
 */
sealed interface SharedObject

class SharedMap : SharedObject {
    internal val map = ConcurrentHashMap<Any, Any>()
}

class SharedCounter(initial: Long) : SharedObject {
    internal val value = AtomicLong(initial)
}

/** Bounded FIFO. Server threads must not block: they use [trySend]/[tryReceive] with [onSpace]/[onItem]. */
class SharedChannel(val capacity: Int) : SharedObject {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    private val buffer = ArrayDeque<Any>()
    private var closed = false
    private val itemListeners = ArrayDeque<() -> Unit>()
    private val spaceListeners = ArrayDeque<() -> Unit>()

    /** Called with the lock held: appends [v] and returns the listener to run once the lock is released. */
    private fun push(v: Any): (() -> Unit)? {
        buffer.addLast(v)
        notEmpty.signal()
        return itemListeners.removeFirstOrNull()
    }

    /** Called with the lock held and a non-empty buffer. */
    private fun pop(): Pair<Any, (() -> Unit)?> {
        val v = buffer.removeFirst()
        notFull.signal()
        return v to spaceListeners.removeFirstOrNull()
    }

    /** False when the channel is full. */
    fun trySend(v: Any): Boolean {
        val listener = lock.withLock {
            if (closed) throw LuaError("channel is closed")
            if (buffer.size >= capacity) return false
            push(v)
        }
        listener?.invoke()
        return true
    }

    /** The value, [CLOSED] when closed and drained, null when empty and open. */
    fun tryReceive(): Any? {
        val popped = lock.withLock {
            if (buffer.isNotEmpty()) pop() else return if (closed) CLOSED else null
        }
        popped.second?.invoke()
        return popped.first
    }

    fun sendBlocking(v: Any) {
        val listener = lock.withLock {
            try {
                while (!closed && buffer.size >= capacity) notFull.await()
            } catch (e: InterruptedException) {
                throw LuaError("parallel call cancelled")
            }
            if (closed) throw LuaError("channel is closed")
            push(v)
        }
        listener?.invoke()
    }

    /** Null means closed and drained. */
    fun receiveBlocking(): Any? {
        val popped = lock.withLock {
            try {
                while (!closed && buffer.isEmpty()) notEmpty.await()
            } catch (e: InterruptedException) {
                throw LuaError("parallel call cancelled")
            }
            if (buffer.isEmpty()) return null
            pop()
        }
        popped.second?.invoke()
        return popped.first
    }

    /** Runs [callback] once there is an item to take or the channel is closed. */
    fun onItem(callback: () -> Unit) {
        val now = lock.withLock {
            if (buffer.isNotEmpty() || closed) true else {
                itemListeners.addLast(callback)
                false
            }
        }
        if (now) callback()
    }

    /** Runs [callback] once there is room to send or the channel is closed. */
    fun onSpace(callback: () -> Unit) {
        val now = lock.withLock {
            if (buffer.size < capacity || closed) true else {
                spaceListeners.addLast(callback)
                false
            }
        }
        if (now) callback()
    }

    fun close() {
        val pending = lock.withLock {
            if (closed) return
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
            (itemListeners + spaceListeners).also {
                itemListeners.clear()
                spaceListeners.clear()
            }
        }
        for (listener in pending) listener()
    }

    companion object {
        /** What [tryReceive] returns once the channel is closed and empty. */
        val CLOSED = Any()
    }
}

/**
 * The Lua face of the shared objects for one `LuaState`: the metatables and the `async.map/counter/channel`
 * constructors. Channel `send` and `receive` differ between the server thread (they yield the coroutine) and a
 * worker (they block the thread), so the functions are supplied by the owner of the state.
 */
class SharedLib(
    channelSend: (SharedLib) -> LuaFunction,
    channelReceive: (SharedLib) -> LuaFunction,
) {
    private val mapMeta = meta(mapMethods())
    private val counterMeta = meta(counterMethods())
    private val channelMeta = meta(
        LuaTable().also {
            it.rawset("send", channelSend(this))
            it.rawset("receive", channelReceive(this))
            it.rawset("close", luaVarFunction { args ->
                channelOf(args, "close").close()
                LuaValue.NONE
            })
        }
    )

    fun wrap(obj: SharedObject): LuaValue = LuaValue.userdataOf(
        obj,
        when (obj) {
            is SharedMap -> mapMeta
            is SharedCounter -> counterMeta
            is SharedChannel -> channelMeta
        }
    )

    /** Inverse of [encode]. */
    fun decode(o: Any?): LuaValue = when (o) {
        null -> LuaValue.NIL
        is LuaValue -> o
        is SharedObject -> wrap(o)
        else -> throw IllegalStateException("unexpected shared value ${o.javaClass}")
    }

    private fun decodeKey(k: Any): LuaValue = when (k) {
        is LuaString -> k
        is Double -> LuaValue.valueOf(k)
        is Boolean -> LuaValue.valueOf(k)
        else -> throw IllegalStateException("unexpected shared key ${k.javaClass}")
    }

    fun install(module: LuaTable) {
        module.rawset("map", luaVarFunction { wrap(SharedMap()) })
        module.rawset("counter", luaVarFunction { args -> wrap(SharedCounter(args.optlong(1, 0))) })
        module.rawset("channel", luaVarFunction { args ->
            val capacity = args.optint(1, 16)
            if (capacity < 1) throw LuaError("async.channel: capacity must be at least 1")
            wrap(SharedChannel(capacity))
        })
    }

    private fun meta(methods: LuaTable): LuaTable = LuaTable().also {
        it.rawset("__index", methods)
        it.rawset("__metatable", LuaValue.FALSE)
    }

    // In methods arg(1) is self; the real parameters start at arg(2).

    private fun mapMethods(): LuaTable = LuaTable().also { m ->
        m.rawset("get", luaVarFunction { args ->
            decode(mapOf(args, "get").map[encodeKey(args.arg(2), "get")])
        })
        m.rawset("set", luaVarFunction { args ->
            val map = mapOf(args, "set").map
            val key = encodeKey(args.arg(2), "set")
            val v = args.arg(3)
            if (v.isnil()) map.remove(key) else map[key] = encode(v, "set")
            LuaValue.NONE
        })
        m.rawset("remove", luaVarFunction { args ->
            decode(mapOf(args, "remove").map.remove(encodeKey(args.arg(2), "remove")))
        })
        m.rawset("update", luaVarFunction { args ->
            val map = mapOf(args, "update").map
            val key = encodeKey(args.arg(2), "update")
            val fn = args.arg(3).checkfunction()
            var stored: LuaValue? = null
            while (stored == null) {
                val old = map[key]
                val result = fn.call(decode(old))
                val new = if (result.isnil()) null else encode(result, "update")
                val swapped = when {
                    old == null && new == null -> true
                    old == null -> map.putIfAbsent(key, new!!) == null
                    new == null -> map.remove(key, old)
                    else -> map.replace(key, old, new)
                }
                if (swapped) stored = decode(new)
            }
            stored ?: LuaValue.NIL
        })
        m.rawset("size", luaVarFunction { args -> LuaValue.valueOf(mapOf(args, "size").map.size) })
        m.rawset("keys", luaVarFunction { args ->
            val t = LuaTable()
            var i = 1
            for (k in mapOf(args, "keys").map.keys) t.rawset(i++, decodeKey(k))
            t
        })
    }

    private fun counterMethods(): LuaTable = LuaTable().also { m ->
        m.rawset("get", luaVarFunction { args -> toLuaValue(counterOf(args, "get").value.get()) })
        m.rawset("set", luaVarFunction { args ->
            counterOf(args, "set").value.set(args.checklong(2))
            LuaValue.NONE
        })
        m.rawset("add", luaVarFunction { args ->
            toLuaValue(counterOf(args, "add").value.addAndGet(args.optlong(2, 1)))
        })
        m.rawset("compareAndSet", luaVarFunction { args ->
            val c = counterOf(args, "compareAndSet")
            LuaValue.valueOf(c.value.compareAndSet(args.checklong(2), args.checklong(3)))
        })
    }

    companion object {
        private const val STORABLE = "only booleans, numbers, strings, frozen tables and shared objects can be stored"

        /** Value to its stored form. The result is immutable or itself thread-safe. */
        fun encode(v: LuaValue, op: String): Any = when (v.type()) {
            LuaValue.TBOOLEAN, LuaValue.TSTRING -> v
            LuaValue.TNUMBER -> if (v.todouble().isNaN()) throw LuaError("$op: $STORABLE") else v
            LuaValue.TTABLE -> if ((v as LuaTable).isFrozen()) v else throw LuaError("$op: $STORABLE")
            LuaValue.TUSERDATA -> v.asObject<SharedObject>() ?: throw LuaError("$op: $STORABLE")
            else -> throw LuaError("$op: $STORABLE")
        }

        fun encodeKey(k: LuaValue, op: String): Any = when (k.type()) {
            LuaValue.TSTRING -> k
            LuaValue.TNUMBER -> k.todouble().takeUnless { it.isNaN() }
                ?: throw LuaError("$op: key must be a string, number or boolean")

            LuaValue.TBOOLEAN -> k.toboolean()
            else -> throw LuaError("$op: key must be a string, number or boolean")
        }

        private fun mapOf(args: Varargs, op: String): SharedMap =
            args.arg(1).asObject<SharedMap>() ?: throw LuaError("$op: expected an async.map")

        private fun counterOf(args: Varargs, op: String): SharedCounter =
            args.arg(1).asObject<SharedCounter>() ?: throw LuaError("$op: expected an async.counter")

        fun channelOf(args: Varargs, op: String): SharedChannel =
            args.arg(1).asObject<SharedChannel>() ?: throw LuaError("$op: expected an async.channel")

        /** The channel and the encoded value of a `send` call. */
        fun sendArgs(args: Varargs): Pair<SharedChannel, Any> {
            val channel = channelOf(args, "send")
            val v = args.arg(2)
            if (v.isnil()) throw LuaError("send: nil cannot be sent")
            return channel to encode(v, "send")
        }

        /** Worker side: `send` and `receive` block the pool thread. */
        val blockingSend: (SharedLib) -> LuaFunction = {
            luaVarFunction { args ->
                val (channel, v) = sendArgs(args)
                channel.sendBlocking(v)
                LuaValue.NONE
            }
        }

        val blockingReceive: (SharedLib) -> LuaFunction = { lib ->
            luaVarFunction { args -> lib.decode(channelOf(args, "receive").receiveBlocking()) }
        }
    }
}
