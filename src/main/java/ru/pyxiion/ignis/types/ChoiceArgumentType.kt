package ru.pyxiion.ignis.types

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import java.util.concurrent.CompletableFuture

class ChoiceArgumentType(private val choices: List<String>) : LuaArgumentType {
    init {
        require(choices.isNotEmpty()) { "choice type requires at least one option" }
        require(choices.all { it.isNotBlank() }) { "choice options must not be blank" }
    }

    override fun getArg(ctx: CommandContext<ServerCommandSource>, name: String): Any {
        val value = StringArgumentType.getString(ctx, name)
        if (value !in choices) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create()
        }
        return value
    }

    /**
     * Looks for the best completion
     */
    override fun getBrigadierArgument(name: String): ArgumentCommandNode<ServerCommandSource, *> {
        return CommandManager.argument(name, StringArgumentType.word())
            .suggests({ ctx, builder ->
                val remaining = builder.remainingLowerCase

                if (remaining.isEmpty()) {
                    choices.forEach { builder.suggest(it) }
                    return@suggests builder.buildFuture()
                }

                CompletableFuture.supplyAsync {
                    choices
                        .map { choice ->
                            val cleanChoice = choice.lowercase()
                            val score = calcScore(remaining, cleanChoice)
                            Triple(choice, score, cleanChoice)
                        }
                        .filter { it.second > 0 }
                        .sortedWith(
                            // First by score. If scores are equal, by abc
                            compareByDescending<Triple<String, Int, String>> { it.second }
                                .thenBy { it.third }
                        )
                        .forEach { builder.suggest(it.first) }
                }.thenCompose { builder.buildFuture() }
            })
            .build()
    }

    companion object {

        private fun calcScore(query: String, target: String): Int {
            if (query == target) return 100
            if (target.startsWith(query)) return 80
            if (target.contains(query)) return 60
            if (isSubsequence(query, target)) return 40

            val distance = levenshtein(query, target)

            // The longer the word, the more errors are allowed
            val maxAllowedErrors = when {
                query.length >= 6 -> 3
                query.length >= 4 -> 2
                query.length >= 3 -> 1
                else -> 0
            }

            if (distance <= maxAllowedErrors) {
                return (21 - distance).coerceIn(1, 20)
            }

            return 0 // drop
        }

        private fun isSubsequence(query: String, target: String): Boolean {
            var queryIndex = 0
            var targetIndex = 0
            while (queryIndex < query.length && targetIndex < target.length) {
                if (query[queryIndex] == target[targetIndex]) queryIndex++
                targetIndex++
            }
            return queryIndex == query.length
        }

        private fun levenshtein(s: String, t: String): Int {
            if (s == t) return 0
            if (s.isEmpty()) return t.length
            if (t.isEmpty()) return s.length

            val v0 = IntArray(t.length + 1) { it }
            val v1 = IntArray(t.length + 1)

            for (i in s.indices) {
                v1[0] = i + 1
                for (j in t.indices) {
                    val cost = if (s[i] == t[j]) 0 else 1
                    v1[j + 1] = (v1[j] + 1).coerceAtMost(v0[j + 1] + 1).coerceAtMost(v0[j] + cost)
                }
                System.arraycopy(v1, 0, v0, 0, v0.size)
            }
            return v0[t.length]
        }
    }
}
