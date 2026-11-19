package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdministrationPageTest {
    @Test
    fun buildsTheWebPageDirectlyAndKeepsThePortAndPrefix() {
        assertEquals("https://media.example.org:8443/web/", NasConnection.administrationPage("https://media.example.org:8443").toString())
        assertEquals("https://[2001:db8::1]/jellyfin/web/", NasConnection.administrationPage("https://[2001:db8::1]/jellyfin/").toString())
    }

    @Test
    fun acceptsAnExistingWebPathWithoutAppendingItAgain() {
        val page = "https://media.example.org/jellyfin/web/"
        assertEquals(page, NasConnection.administrationPage(page.trimEnd('/')).toString())
        assertEquals(page, NasConnection.administrationPage(page + "index.html").toString())
    }

    @Test
    fun rejectsCleartextAndCredentialsInTheLink() {
        for (invalid in listOf("http://media.example.org", "https://user:password@media.example.org", "https://media.example.org?token=example")) {
            assertThrows(NasConfigurationException::class.java) { NasConnection.administrationPage(invalid) }
        }
    }
}
