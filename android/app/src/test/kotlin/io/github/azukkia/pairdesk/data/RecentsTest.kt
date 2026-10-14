package io.github.azukkia.pairdesk.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecentsTest {
    @Test
    fun `touch moves the partner to the top and keeps at most 30`() {
        var list = emptyList<StoredRecent>()
        for (i in 0 until 35) list = Recents.touch(list, (100000000 + i).toString(), "PC $i", PrsUpdate.Clear, null, i.toLong())
        assertEquals(Recents.MAX, list.size)
        assertEquals("100000034", list.first().id)

        list = Recents.touch(list, "100000010", "", PrsUpdate.Keep, null, 99)
        assertEquals("100000010", list.first().id)
        assertEquals("PC 10", list.first().name, "an empty name keeps the stored one")
        assertEquals(2, list.first().count)
        assertEquals(Recents.MAX, list.size)
    }

    @Test
    fun `prs updates`() {
        var list = Recents.touch(emptyList(), "123456789", "A", PrsUpdate.Set(ByteArray(1)), "sealed", 1)
        assertEquals("sealed", list[0].sealedPrs)
        list = Recents.touch(list, "123456789", "A", PrsUpdate.Keep, null, 2)
        assertEquals("sealed", list[0].sealedPrs)
        list = Recents.touch(list, "123456789", "A", PrsUpdate.Clear, null, 3)
        assertNull(list[0].sealedPrs)
    }

    @Test
    fun `encode and decode round trip`() {
        val list = listOf(
            StoredRecent("123456789", "Bureau \"principal\"", 1_700_000_000_000, 3, "k1:abc"),
            StoredRecent("987654321", "", 5, 1, null),
        )
        assertEquals(list, Recents.decode(Recents.encode(list)))
    }

    @Test
    fun `decode is lenient`() {
        assertTrue(Recents.decode(null).isEmpty())
        assertTrue(Recents.decode("not json").isEmpty())
        assertTrue(Recents.decode("{}").isEmpty())
        val decoded = Recents.decode(
            """[{"id":"012345678"},{"id":"123456789","name":5,"lastAt":"x","count":-2},{"id":"123456789","name":"dup"},42]""",
        )
        assertEquals(1, decoded.size)
        assertEquals(StoredRecent("123456789", "", 0, 0, null), decoded[0])
    }

    @Test
    fun `public view hides secrets`() {
        val public = Recents.toPublic(listOf(StoredRecent("123456789", "A", 1, 1, "k1:x")))
        assertEquals(RecentPartner("123456789", "A", 1, 1, hasPassword = true), public[0])
    }
}
