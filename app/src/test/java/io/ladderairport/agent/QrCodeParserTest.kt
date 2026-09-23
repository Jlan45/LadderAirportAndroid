package io.ladderairport.agent

import io.ladderairport.agent.util.QrCodeParser
import org.junit.Assert.*
import org.junit.Test

class QrCodeParserTest {

    @Test
    fun testParseJson() {
        val json = """
            {"panel_url":"https://panel.example.com","node_id":"node-01","token":"test-enroll-tok"}
        """.trimIndent()
        val info = QrCodeParser.parse(json)
        assertNotNull(info)
        assertEquals("https://panel.example.com", info?.panelUrl)
        assertEquals("node-01", info?.nodeId)
        assertEquals("test-enroll-tok", info?.token)
    }

    @Test
    fun testParseJsonWithCamelCase() {
        val json = """
            {"panelUrl":"http://192.168.1.100:8080","nodeId":"android-box","enrollToken":"secret-123"}
        """.trimIndent()
        val info = QrCodeParser.parse(json)
        assertNotNull(info)
        assertEquals("http://192.168.1.100:8080", info?.panelUrl)
        assertEquals("android-box", info?.nodeId)
        assertEquals("secret-123", info?.token)
    }

    @Test
    fun testParseUri() {
        val uri = "ladder://enroll?panel_url=https%3A%2F%2Fpanel.example.com&node_id=my-node&token=my-tok"
        val info = QrCodeParser.parse(uri)
        assertNotNull(info)
        assertEquals("https://panel.example.com", info?.panelUrl)
        assertEquals("my-node", info?.nodeId)
        assertEquals("my-tok", info?.token)
    }

    @Test
    fun testParseInvalid() {
        assertNull(QrCodeParser.parse(""))
        assertNull(QrCodeParser.parse("hello world"))
        assertNull(QrCodeParser.parse("{\"foo\":\"bar\"}"))
    }
}
