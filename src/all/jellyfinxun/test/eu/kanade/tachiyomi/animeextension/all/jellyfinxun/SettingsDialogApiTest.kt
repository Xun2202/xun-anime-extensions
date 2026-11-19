package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDialogApiTest {
    @Test
    fun settingsUseAndroidDialogsAcrossTheHostBoundary() {
        // The host's release optimizer can remove AppCompat methods used only by extensions.
        val settingsBytecode = checkNotNull(javaClass.getResourceAsStream("JellyfinXun\$Settings.class"))
            .use { it.readBytes().toString(Charsets.ISO_8859_1) }

        assertTrue(settingsBytecode.contains("android/app/AlertDialog"))
        assertFalse(settingsBytecode.contains("androidx/appcompat/"))
    }
}
