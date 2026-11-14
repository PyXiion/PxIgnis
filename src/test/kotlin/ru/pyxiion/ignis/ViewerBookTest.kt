package ru.pyxiion.ignis

import ru.pyxiion.ignis.api.manager.ViewerBook
import ru.pyxiion.ignis.api.manager.ViewerBook.Fate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ViewerBookTest {
    private class P(val uuid: UUID)

    private fun book() = ViewerBook<P, String> { it.uuid }

    @Test
    fun `remove reaches the respawned player object`() {
        val id = UUID.randomUUID()
        val old = P(id)
        val book = book()
        book.add(old, "arena")
        val online = HashMap<UUID, P>()
        val respawned = P(id) // Minecraft swaps the object, the connection stays
        online[id] = respawned
        val recipients = book.live { online[it] }
        assertEquals(1, recipients.size)
        assertSame(respawned, recipients[0])
        assertNotSame(old, recipients[0])
    }

    @Test
    fun `disconnected viewers are not resolved`() {
        val book = book()
        book.add(P(UUID.randomUUID()), "arena")
        assertTrue(book.live { null }.isEmpty())
    }

    @Test
    fun `contains is by uuid`() {
        val id = UUID.randomUUID()
        val book = book()
        book.add(P(id), "arena")
        assertTrue(book.contains(P(id)))
        assertTrue(book.contains(id))
    }

    @Test
    fun `fate`() {
        assertEquals(Fate.KEEP, ViewerBook.fate(true, true, true, false))
        assertEquals(Fate.DROP, ViewerBook.fate(false, false, false, true))
        assertEquals(Fate.REMOVE, ViewerBook.fate(true, true, true, true))
        assertEquals(Fate.REMOVE_RECHECK, ViewerBook.fate(true, false, true, true))
        assertEquals(Fate.REMOVE_RECHECK, ViewerBook.fate(true, true, false, false))
    }
}
