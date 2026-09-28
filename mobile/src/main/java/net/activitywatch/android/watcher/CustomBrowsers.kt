package net.activitywatch.android.watcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// How to find the URL bar of a browser the user added in Browser Tracking settings.
// `key` is what gets persisted, so never rename one.
internal enum class UrlBarStyle(val key: String, val label: String) {
    AUTO("auto", "Auto-detect (Chromium, then Firefox)"),
    CHROMIUM("chromium", "Chromium (url_bar)"),
    GECKO("gecko", "Gecko / Fenix (address bar)");

    companion object {
        fun fromKey(key: String?): UrlBarStyle = values().firstOrNull { it.key == key } ?: AUTO
    }
}

internal data class CustomBrowser(val packageName: String, val style: UrlBarStyle)

internal fun encodeCustomBrowsers(browsers: List<CustomBrowser>): String =
    JSONArray().apply {
        browsers.forEach { put(JSONObject().put("package", it.packageName).put("style", it.style.key)) }
    }.toString()

// Lenient on purpose: a bad stored value must not take the web watcher down, so broken
// entries are skipped and a style written by a newer version falls back to AUTO.
internal fun decodeCustomBrowsers(json: String?): List<CustomBrowser> {
    if (json.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(json)
    } catch (_: JSONException) {
        return emptyList()
    }
    val result = LinkedHashMap<String, CustomBrowser>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val pkg = obj.optString("package").trim()
        if (pkg.isEmpty() || pkg in result) continue
        result[pkg] = CustomBrowser(pkg, UrlBarStyle.fromKey(obj.optString("style")))
    }
    return result.values.toList()
}

private val PACKAGE_NAME_PATTERN = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

// Error to show the user, or null when `pkg` can be added.
internal fun validateCustomBrowserPackage(
    pkg: String,
    builtIn: Set<String>,
    existing: Collection<String>,
): String? = when {
    pkg.isEmpty() -> "Enter a package name"
    !PACKAGE_NAME_PATTERN.matches(pkg) -> "Not a valid package name, e.g. org.example.browser"
    pkg in builtIn -> "$pkg is already supported built-in"
    pkg in existing -> "$pkg is already in the list"
    else -> null
}
