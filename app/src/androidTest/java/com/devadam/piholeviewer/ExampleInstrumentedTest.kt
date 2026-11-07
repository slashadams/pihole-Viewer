package com.devadam.piholeviewer

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.devadam.piholeviewer", appContext.packageName)
    }

    @Test
    fun cryptoHelper_encryptAndDecryptRoundTrip() {
        val originalSecret = "my-super-secret-pihole-password!@#"
        val encrypted = CryptoHelper.encrypt(originalSecret)
        assertTrue("Encrypted value should start with enc_v1:", encrypted.startsWith("enc_v1:"))
        assertNotEquals(originalSecret, encrypted)

        val decrypted = CryptoHelper.decrypt(encrypted)
        assertEquals(originalSecret, decrypted)
    }

    @Test
    fun cryptoHelper_legacyPlaintextAndEmptyHandling() {
        assertEquals("", CryptoHelper.encrypt(""))
        assertEquals("", CryptoHelper.decrypt(""))
        assertEquals("legacy_plain_pass", CryptoHelper.decrypt("legacy_plain_pass"))
    }

    @Test
    fun preferencesManager_saveAndLoadInstancesWithEncryptedPassword() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = appContext.getSharedPreferences("pihole_prefs", Context.MODE_PRIVATE)
        val originalSavedJson = prefs.getString("instances", null)

        try {
            prefs.edit().clear().commit()

            val prefsManager = PreferencesManager(appContext)
            val testInstance = PiHoleInstance(
                id = "test-id-1",
                name = "Primary Pi-hole",
                url = "https://192.168.1.50/admin",
                password = "correct-horse-battery-staple",
                pinnedCertSha256 = "AA:BB:CC:DD"
            )
            prefsManager.saveInstances(listOf(testInstance))

            val loaded = prefsManager.loadInstances()
            assertEquals(1, loaded.size)
            val item = loaded[0]
            assertEquals("test-id-1", item.id)
            assertEquals("Primary Pi-hole", item.name)
            assertEquals("https://192.168.1.50", item.url)
            assertEquals("correct-horse-battery-staple", item.password)
            assertEquals("AA:BB:CC:DD", item.pinnedCertSha256)
        } finally {
            if (originalSavedJson != null) {
                prefs.edit().putString("instances", originalSavedJson).commit()
            } else {
                prefs.edit().clear().commit()
            }
        }
    }

    @Test
    fun jsonParsing_statsAndListsRoundTrip() {
        val summaryJson = """
            {
              "queries": {
                "total": 158856,
                "blocked": 84033,
                "percent_blocked": 52.898
              },
              "gravity": {
                "domains_being_blocked": 2226785
              }
            }
        """.trimIndent()
        val root = JSONObject(summaryJson)
        val queriesObj = root.optJSONObject("queries") ?: root
        val gravityObj = root.optJSONObject("gravity")
        val stats = PiHoleStats(
            domainsBlocked = gravityObj?.optInt("domains_being_blocked", 0) ?: 0,
            dnsQueries = queriesObj.optInt("total", 0),
            queriesBlocked = queriesObj.optInt("blocked", 0),
            percentBlocked = queriesObj.optDouble("percent_blocked", 0.0).toFloat(),
            status = "enabled"
        )
        assertEquals(2226785, stats.domainsBlocked)
        assertEquals(158856, stats.dnsQueries)
        assertEquals(84033, stats.queriesBlocked)
        assertEquals(52.898f, stats.percentBlocked, 0.01f)

        val listsJson = """
            {
              "lists": [
                {
                  "id": 1,
                  "address": "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                  "type": "block",
                  "comment": "StevenBlack hosts",
                  "enabled": true,
                  "groups": [0],
                  "number": 76737
                }
              ]
            }
        """.trimIndent()
        val arr = JSONObject(listsJson).optJSONArray("lists") ?: JSONArray()
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        val entry = PiHoleListEntry(
            id = obj.optInt("id", 0),
            address = obj.optString("address", ""),
            type = obj.optString("type", "block"),
            comment = if (obj.isNull("comment")) "" else obj.optString("comment", ""),
            enabled = obj.optBoolean("enabled", true),
            groups = listOf(0),
            numberDomains = obj.optInt("number", 0)
        )
        assertEquals(1, entry.id)
        assertEquals("block", entry.type)
        assertEquals(76737, entry.numberDomains)
    }
}
