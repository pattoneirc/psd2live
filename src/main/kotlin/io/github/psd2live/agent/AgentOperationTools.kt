package io.github.psd2live.agent

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*

/** The only translation needed for application operations: the catalog's published schema and native output. */
internal fun installOperationTools(server: Server, catalog: AgentToolCatalog) {
    catalog.tools.values.forEach { tool ->
        val outputSchema = tool.outputSchema?.let { envelope -> ToolSchema(
            properties = envelope.getValue("properties").jsonObject,
            required = envelope.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        ) }
        server.addTool(tool.name, tool.description,
            ToolSchema(properties = tool.inputSchema.getValue("properties").jsonObject,
                required = tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }),
            outputSchema = outputSchema,
            toolAnnotations = tool.annotations,
        ) { call -> tool.handler(call.arguments) }
    }
}
