package com.embeddablechatbot.android.core.data.repository

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DeviceDocumentTextTest {
    private fun zip(vararg parts: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> parts.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun `reads shared, inline and numeric cells of every sheet`() {
        val bytes = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Budget &amp; plan" sheetId="1"/></sheets></workbook>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Item</t></si><si><r><t>Co</t></r><r><t>st</t></r></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>Rent</t></is></c><c r="B2"><v>1200</v></c></row></sheetData></worksheet>""",
        )
        assertEquals("## Budget & plan\nItem\tCost\nRent\t1200", DeviceDocumentText.text(bytes))
    }

    @Test fun `reads slide text in slide order`() {
        val bytes = zip(
            "ppt/slides/slide10.xml" to """<p:sld><a:p><a:r><a:t>Last</a:t></a:r></a:p></p:sld>""",
            "ppt/slides/slide2.xml" to """<p:sld><a:p><a:r><a:t>Q4 &lt;plan&gt;</a:t></a:r></a:p><a:p><a:r><a:t>Hire</a:t></a:r><a:r><a:t> two</a:t></a:r></a:p></p:sld>""",
        )
        assertEquals("## Slide 1\nQ4 <plan>\nHire two\n\n## Slide 2\nLast", DeviceDocumentText.text(bytes))
    }

    @Test fun `finds the text of a legacy Word file`() {
        val header = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val body = "The contract renewal is due on the first of March, please confirm.".toByteArray(Charsets.UTF_16LE)
        val bytes = header + ByteArray(600) + body + ByteArray(100)
        assertTrue(DeviceDocumentText.text(bytes)!!.contains("contract renewal is due"))
    }

    @Test fun `unknown or empty files give null`() {
        assertNull(DeviceDocumentText.text("plain".toByteArray()))
        assertNull(DeviceDocumentText.text(zip("word/document.xml" to "<w:document/>")))
    }
}
