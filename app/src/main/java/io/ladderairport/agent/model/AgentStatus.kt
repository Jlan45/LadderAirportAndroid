package io.ladderairport.agent.model

data class AgentStatus(
    val running: Boolean = false,
    val state: String = "stopped",
    val agentVersion: String = "",
    val singboxVersion: String = "",
    val panelUrl: String = "",
    val nodeId: String = "",
    val startedAtUnix: Long = 0,
    val uptimeSecs: Long = 0,
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val connections: Long = 0,
    val configHash: String = "",
    val lastError: String = ""
)
