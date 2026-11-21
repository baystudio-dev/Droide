package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Line protocol for the isolated Chromium regression harness; no Android or production runtime changes. */
object BrowserActionProbe {
    @JvmStatic fun main(args: Array<String>) {
        generateSequence(::readlnOrNull).forEach { line ->
            val input = Json.parseToJsonElement(line).jsonObject
            val op = input.getValue("operation").jsonPrimitive.content
            val script = if (op == "snapshot") AgentBrowserSnapshot.script(count = 200, elementCount = 40)
                else if (op == "point") AgentBrowserInteraction.verifyPoint(input.getValue("target").jsonPrimitive.content,
                    input.getValue("x").jsonPrimitive.float, input.getValue("y").jsonPrimitive.float)
                else if (op == "double_watch") AgentBrowserInteraction.doubleTapWatch("test", input.getValue("target").jsonPrimitive.content,
                    input.getValue("start").jsonPrimitive.boolean)
                else if (op == "drag_phase") AgentBrowserDrag.script("test", input.getValue("phase").jsonPrimitive.content,
                    input.getValue("target").jsonPrimitive.content, input["destination"]?.jsonPrimitive?.contentOrNull,
                    input.getValue("html").jsonPrimitive.boolean, input.getValue("x").jsonPrimitive.float, input.getValue("y").jsonPrimitive.float)
                else if (op == "blob_stream") BrowserBlobStream.script(input.getValue("target").jsonPrimitive.content, input.getValue("text").jsonPrimitive.content)
                else AgentBrowserInteraction.script(op, input["target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input["text"]?.jsonPrimitive?.contentOrNull, input["submit"]?.jsonPrimitive?.booleanOrNull ?: false,
                    input["checked"]?.jsonPrimitive?.booleanOrNull, input["option_index"]?.jsonPrimitive?.intOrNull)
            println(JsonPrimitive(script))
        }
    }
}
