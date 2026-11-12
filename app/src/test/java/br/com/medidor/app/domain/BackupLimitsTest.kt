package br.com.medidor.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLimitsTest {
    @Test fun allowsManifestAndPrivateImages() {
        assertTrue(BackupLimits.validEntry("manifest.json"))
        assertTrue(BackupLimits.validEntry("images/planta_123_456.jpg"))
        assertTrue(BackupLimits.validEntry("images/planta-0123.JPG"))
    }

    @Test fun rejectsTraversalAndUnexpectedArchiveContent() {
        for (entry in listOf(
            "../prefs.xml", "images/../private.jpg", "images/a/evil.jpg",
            "images/evil.jpg/../../x", "private/records.json", "images\\bad.jpg",
            "images/../../manifest.json", "images/.jpg..", "images/test.png"
        )) assertFalse(BackupLimits.validEntry(entry))
    }
}
