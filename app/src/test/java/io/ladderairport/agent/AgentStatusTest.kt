package io.ladderairport.agent

import com.google.gson.JsonParser
import io.ladderairport.agent.model.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {

    @Test
    fun testAgentStatusDefaults() {
        val status = AgentStatus()
        assertFalse(status.running)
        assertEquals("stopped", status.state)
        assertEquals(0L, status.uplinkBytes)
        assertEquals(0L, status.downlinkBytes)
    }

    @Test
    fun testAgentStatusParsing() {
        val json = """
            {
                "running": true,
                "state": "running",
                "agent_version": "v0.15.5",
                "singbox_version": "1.12.22",
                "panel_url": "https://panel.example.com",
                "node_id": "android-node-01",
                "started_at_unix": 1727140000,
                "uptime_secs": 120,
                "uplink_bytes": 1048576,
                "downlink_bytes": 2097152,
                "connections": 5,
                "config_hash": "abc123hash",
                "last_error": ""
            }
        """.trimIndent()

        val obj = JsonParser.parseString(json).asJsonObject
        val status = AgentStatus(
            running = obj.get("running").asBoolean,
            state = obj.get("state").asString,
            agentVersion = obj.get("agent_version").asString,
            singboxVersion = obj.get("singbox_version").asString,
            panelUrl = obj.get("panel_url").asString,
            nodeId = obj.get("node_id").asString,
            startedAtUnix = obj.get("started_at_unix").asLong,
            uptimeSecs = obj.get("uptime_secs").asLong,
            uplinkBytes = obj.get("uplink_bytes").asLong,
            downlinkBytes = obj.get("downlink_bytes").asLong,
            connections = obj.get("connections").asLong,
            configHash = obj.get("config_hash").asString,
            lastError = obj.get("last_error").asString
        )

        assertTrue(status.running)
        assertEquals("running", status.state)
        assertEquals("v0.15.5", status.agentVersion)
        assertEquals(1048576L, status.uplinkBytes)
        assertEquals(2097152L, status.downlinkBytes)
        assertEquals(5L, status.connections)
        assertEquals("abc123hash", status.configHash)
    }
}
