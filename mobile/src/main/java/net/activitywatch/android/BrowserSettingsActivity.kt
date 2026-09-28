package net.activitywatch.android

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.activitywatch.android.watcher.CustomBrowser
import net.activitywatch.android.watcher.UrlBarStyle
import net.activitywatch.android.watcher.WebWatcher
import net.activitywatch.android.watcher.validateCustomBrowserPackage

private const val TAG = "BrowserSettingsActivity"

/**
 * BrowserSettingsActivity lists the browsers the web watcher tracks and lets the user add
 * their own (ActivityWatch/aw-android#306).
 *
 * WebWatcher listens for changes to the stored list, so edits apply without a restart.
 */
class BrowserSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AWPreferences

    private lateinit var containerCustomBrowsers: LinearLayout
    private lateinit var tvNoCustomBrowsers: TextView
    private lateinit var etPackage: EditText
    private lateinit var rgStyle: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser_settings)
        applySafeWindowInsets()

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.browser_settings)
        }

        prefs = AWPreferences(this)

        containerCustomBrowsers = findViewById(R.id.container_custom_browsers)
        tvNoCustomBrowsers = findViewById(R.id.tv_no_custom_browsers)
        etPackage = findViewById(R.id.et_browser_package)
        rgStyle = findViewById(R.id.rg_url_bar_style)

        findViewById<TextView>(R.id.tv_builtin_browsers).text =
            WebWatcher.KNOWN_BROWSER_PACKAGES.joinToString("\n")
        findViewById<Button>(R.id.btn_add_browser).setOnClickListener { addBrowser() }

        renderCustomBrowsers()
    }

    private fun addBrowser() {
        val pkg = etPackage.text.toString().trim()
        val current = prefs.getCustomBrowsers()
        validateCustomBrowserPackage(pkg, WebWatcher.KNOWN_BROWSER_PACKAGES, current.map { it.packageName })?.let { error ->
            Log.d(TAG, "Rejected custom browser '$pkg': $error")
            etPackage.error = error
            return
        }
        val style = when (rgStyle.checkedRadioButtonId) {
            R.id.rb_style_chromium -> UrlBarStyle.CHROMIUM
            R.id.rb_style_gecko -> UrlBarStyle.GECKO
            else -> UrlBarStyle.AUTO
        }
        prefs.setCustomBrowsers(current + CustomBrowser(pkg, style))
        Log.i(TAG, "Added custom browser $pkg (${style.key})")

        etPackage.text.clear()
        rgStyle.check(R.id.rb_style_auto)
        renderCustomBrowsers()
        Toast.makeText(this, "Added $pkg", Toast.LENGTH_SHORT).show()
    }

    private fun removeBrowser(pkg: String) {
        prefs.setCustomBrowsers(prefs.getCustomBrowsers().filterNot { it.packageName == pkg })
        Log.i(TAG, "Removed custom browser $pkg")
        renderCustomBrowsers()
    }

    private fun renderCustomBrowsers() {
        val browsers = prefs.getCustomBrowsers()
        containerCustomBrowsers.removeAllViews()
        tvNoCustomBrowsers.visibility = if (browsers.isEmpty()) View.VISIBLE else View.GONE
        for (browser in browsers) {
            val row = layoutInflater.inflate(R.layout.item_custom_browser, containerCustomBrowsers, false)
            row.findViewById<TextView>(R.id.tv_custom_browser_package).text = browser.packageName
            row.findViewById<TextView>(R.id.tv_custom_browser_style).text = browser.style.label
            row.findViewById<Button>(R.id.btn_remove_custom_browser).setOnClickListener {
                removeBrowser(browser.packageName)
            }
            containerCustomBrowsers.addView(row)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
