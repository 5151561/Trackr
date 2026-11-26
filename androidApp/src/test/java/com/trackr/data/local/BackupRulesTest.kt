package com.trackr.data.local

import android.app.Application
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.trackr.R
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.datastore.TrackrDataStoreFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * 备份规则跟着存储文件名走（重构计划 §2.4、风险 R22）：
 * 高德 Key 和追踪会话不能随备份去别的设备；收藏数据库和设置必须能恢复。
 * 改了 DataStore / Room 的文件名而没改备份规则时，这里先失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupRulesTest {

    private data class Rule(val tag: String, val domain: String, val path: String)

    private val context: Application = ApplicationProvider.getApplicationContext()

    /** 读出一份规则 XML：section（cloud-backup / device-transfer / full-backup-content）-> 规则 */
    private fun readRules(resId: Int): Map<String, List<Rule>> {
        val parser: XmlResourceParser = context.resources.getXml(resId)
        val result = linkedMapOf<String, MutableList<Rule>>()
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "full-backup-content", "cloud-backup", "device-transfer" -> {
                    section = parser.name
                    result.getOrPut(section) { mutableListOf() }
                }
                "include", "exclude" -> result.getValue(section) += Rule(
                    tag = parser.name,
                    domain = parser.getAttributeValue(null, "domain"),
                    path = parser.getAttributeValue(null, "path")
                )
            }
        }
        return result
    }

    private val mustExclude = listOf(
        Rule("exclude", "file", "${TrackrDataStoreFiles.DIRECTORY}/${TrackrDataStoreFiles.AMAP_KEYS}"),
        Rule("exclude", "file", "${TrackrDataStoreFiles.DIRECTORY}/${TrackrDataStoreFiles.TRACKING_SESSION}"),
        // 旧 SharedPreferences 的 Key 文件保留到旧文件删掉
        Rule("exclude", "sharedpref", "trackr_amap_keys.xml")
    )

    /** 不能被排除的（收藏、路线、提醒、设置） */
    private val mustKeep = listOf(
        "database" to TrackrDatabase.FILE_NAME,
        "file" to "${TrackrDataStoreFiles.DIRECTORY}/${TrackrDataStoreFiles.SETTINGS}",
        "sharedpref" to "trackr_prefs.xml",
        "sharedpref" to "trackr_route_groups.xml",
        "sharedpref" to "trackr_scheduled_reminders.xml"
    )

    private fun assertSection(name: String, rules: List<Rule>) {
        mustExclude.forEach { assertTrue("$name 没有排除 ${it.domain}/${it.path}", it in rules) }
        // 没有 include：一旦写了 include，没列出的文件（包括 trackr.db）都不会备份
        assertTrue("$name 不应有 include", rules.none { it.tag == "include" })
        mustKeep.forEach { (domain, path) ->
            assertFalse(
                "$name 把 $domain/$path 排除了",
                rules.any { it.domain == domain && (it.path == path || path.startsWith(it.path.trimEnd('/') + "/") || it.path == ".") }
            )
        }
    }

    @Test
    fun legacyBackupRules_android11AndBelow() {
        val rules = readRules(R.xml.backup_rules)
        assertEquals(listOf("full-backup-content"), rules.keys.toList())
        assertSection("backup_rules", rules.getValue("full-backup-content"))
    }

    @Test
    fun dataExtractionRules_cloudAndDeviceTransfer() {
        val rules = readRules(R.xml.data_extraction_rules)
        assertEquals(listOf("cloud-backup", "device-transfer"), rules.keys.toList())
        assertSection("cloud-backup", rules.getValue("cloud-backup"))
        assertSection("device-transfer", rules.getValue("device-transfer"))
    }

    @Test
    fun allThreeSections_haveSameRules() {
        val legacy = readRules(R.xml.backup_rules).getValue("full-backup-content").toSet()
        val extraction = readRules(R.xml.data_extraction_rules)
        assertEquals(legacy, extraction.getValue("cloud-backup").toSet())
        assertEquals(legacy, extraction.getValue("device-transfer").toSet())
    }

    @Test
    fun manifest_pointsAtBothRuleFiles() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
