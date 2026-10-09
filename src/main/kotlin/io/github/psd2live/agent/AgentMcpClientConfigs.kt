package io.github.psd2live.agent

import kotlinx.serialization.json.*

/** How a host adds the endpoint: a command to run or a configuration entry to merge. */
enum class AgentMcpClient { CLAUDE_CODE, CODEX, JSON, STDIO }

object AgentMcpClientConfigs {
    private val pretty = Json { prettyPrint = true }

    fun snippet(client: AgentMcpClient, connection: AgentMcpConnectionInfo, proxyPath: String): String = when (client) {
        AgentMcpClient.CLAUDE_CODE ->
            "claude mcp add --transport http psd2live ${connection.endpoint} --header \"Authorization: Bearer ${connection.token}\""
        AgentMcpClient.CODEX -> """
            [mcp_servers.psd2live]
            url = ${JsonPrimitive(connection.endpoint)}
            http_headers = { Authorization = ${JsonPrimitive("Bearer ${connection.token}")} }
        """.trimIndent()
        AgentMcpClient.JSON -> servers(buildJsonObject {
            put("url", connection.endpoint)
            putJsonObject("headers") { put("Authorization", "Bearer ${connection.token}") }
        })
        // The proxy otherwise reads the saved token and port itself, but only on Windows.
        AgentMcpClient.STDIO -> servers(buildJsonObject {
            put("command", if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3")
            putJsonArray("args") { add(proxyPath) }
            putJsonObject("env") {
                put("PSD2LIVE_MCP_ENDPOINT", connection.endpoint)
                put("PSD2LIVE_MCP_TOKEN", connection.token)
            }
        })
    }

    private fun servers(entry: JsonObject) =
        pretty.encodeToString(JsonObject.serializer(), buildJsonObject { putJsonObject("mcpServers") { put("psd2live", entry) } })
}
