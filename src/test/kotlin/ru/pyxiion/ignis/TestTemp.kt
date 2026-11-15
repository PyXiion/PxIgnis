package ru.pyxiion.ignis

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Temporary directories of the tests, deleted recursively when the test JVM exits. */
object TestTemp {
    private val dirs: MutableList<Path> = Collections.synchronizedList(mutableListOf())

    init {
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(dirs) { dirs.forEach { it.toFile().deleteRecursively() } } })
    }

    fun dir(prefix: String): Path = Files.createTempDirectory(prefix).also { dirs.add(it) }
}
