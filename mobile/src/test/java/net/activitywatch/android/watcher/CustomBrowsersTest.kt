package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomBrowsersTest {

    @Test
    fun `custom browsers survive an encode-decode round trip`() {
        val browsers = listOf(
            CustomBrowser("org.example.chromium", UrlBarStyle.CHROMIUM),
            CustomBrowser("org.example.fenix", UrlBarStyle.GECKO),
            CustomBrowser("org.example.other", UrlBarStyle.AUTO),
        )
        assertEquals(browsers, decodeCustomBrowsers(encodeCustomBrowsers(browsers)))
    }

    @Test
    fun `missing or corrupt stored value decodes to an empty list`() {
        assertEquals(emptyList<CustomBrowser>(), decodeCustomBrowsers(null))
        assertEquals(emptyList<CustomBrowser>(), decodeCustomBrowsers(""))
        assertEquals(emptyList<CustomBrowser>(), decodeCustomBrowsers("{not json"))
        assertEquals(emptyList<CustomBrowser>(), decodeCustomBrowsers("""{"package":"a.b"}"""))
    }

    @Test
    fun `decode skips bad entries, defaults unknown styles to auto and drops duplicates`() {
        val json = """[
            {"package":"org.example.one","style":"chromium"},
            {"package":"","style":"gecko"},
            "not an object",
            {"package":"org.example.two","style":"from-the-future"},
            {"package":"org.example.one","style":"gecko"}
        ]"""
        assertEquals(
            listOf(
                CustomBrowser("org.example.one", UrlBarStyle.CHROMIUM),
                CustomBrowser("org.example.two", UrlBarStyle.AUTO),
            ),
            decodeCustomBrowsers(json),
        )
    }

    @Test
    fun `style keys are stable`() {
        // Persisted in SharedPreferences: renaming a key silently resets users to AUTO.
        assertEquals(listOf("auto", "chromium", "gecko"), UrlBarStyle.values().map { it.key })
        assertEquals(UrlBarStyle.AUTO, UrlBarStyle.fromKey(null))
    }

    @Test
    fun `package validation`() {
        val builtIn = setOf("com.android.chrome")
        val existing = listOf("org.example.added")
        assertNull(validateCustomBrowserPackage("org.example.browser", builtIn, existing))
        assertNull(validateCustomBrowserPackage("com.example_2.b3", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("nodots", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("org..example", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("org.1example", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("org.example browser", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("com.android.chrome", builtIn, existing))
        assertNotNull(validateCustomBrowserPackage("org.example.added", builtIn, existing))
    }
}
