package com.typedefai.lingua.lingua.agent

import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Tool schemas exposed to Gemma in the practice chat. Execution is delegated to [ToolExecutor]
 * (the manual tool loop calls the executor directly, so validation, dictionary reads, wordbook
 * writes, and translation live in one place).
 */
class LookupWordTool : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = """
    {
        "name": "lookup_word",
        "description": "Look up an English word in the on-device dictionary. Returns the phonetic, meanings, and usage. Use whenever the user asks what a word means or wants a word's details.",
        "parameters": {
            "type": "object",
            "properties": {
                "word": {
                    "type": "string",
                    "description": "The single English word to look up, exactly as the user mentioned it."
                }
            },
            "required": ["word"]
        }
    }
    """.trimIndent()

  override fun execute(paramsJsonString: String): String =
    executeTool("lookup_word", paramsJsonString)
}

class AddToWordbookTool : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = """
    {
        "name": "add_to_wordbook",
        "description": "Add a word to the user's saved word list (wordbook). Use when the user says things like 'add it to my list', 'save this word', 'remember apple', or 'add apple to my vocabulary'. The word must exist in the on-device dictionary.",
        "parameters": {
            "type": "object",
            "properties": {
                "word": {
                    "type": "string",
                    "description": "The word to save, exactly as the user provided."
                }
            },
            "required": ["word"]
        }
    }
    """.trimIndent()

  override fun execute(paramsJsonString: String): String =
    executeTool("add_to_wordbook", paramsJsonString)
}

class RemoveFromWordbookTool : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = """
    {
        "name": "remove_from_wordbook",
        "description": "Remove a word from the user's wordbook. Use when the user asks to delete, remove, or forget a saved word.",
        "parameters": {
            "type": "object",
            "properties": {
                "word": {
                    "type": "string",
                    "description": "The word to remove, exactly as the user provided."
                }
            },
            "required": ["word"]
        }
    }
    """.trimIndent()

  override fun execute(paramsJsonString: String): String =
    executeTool("remove_from_wordbook", paramsJsonString)
}

class ListWordbookTool : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = """
    {
        "name": "list_wordbook",
        "description": "List the words saved in the user's wordbook. Use when the user asks which words they have saved.",
        "parameters": {
            "type": "object",
            "properties": {},
            "required": []
        }
    }
    """.trimIndent()

  override fun execute(paramsJsonString: String): String =
    executeTool("list_wordbook", paramsJsonString)
}

class TranslateTextTool : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = """
    {
        "name": "translate_text",
        "description": "Translate a sentence or phrase between English and Simplified Chinese with the on-device translation model. Use when the user asks to translate something, or asks what something means in the other language. Omit 'to' for automatic direction; set 'to' to 'zh' or 'en' to force a target language.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "The sentence or phrase to translate, exactly as the user provided."
                },
                "to": {
                    "type": "string",
                    "description": "Optional target language: 'zh' for Simplified Chinese or 'en' for English. Omit for automatic."
                }
            },
            "required": ["text"]
        }
    }
    """.trimIndent()

  override fun execute(paramsJsonString: String): String =
    executeTool("translate_text", paramsJsonString)
}

class PronounceWordTool : OpenApiTool {
  override fun getToolDescriptionJsonString(): String = """
    {
        "name": "pronounce_word",
        "description": "Speak an English word or short phrase aloud with the text-to-speech voice. Use when the user asks how to pronounce something or asks you to say a word.",
        "parameters": {
            "type": "object",
            "properties": {
                "word": {
                    "type": "string",
                    "description": "The word or short phrase to pronounce, exactly as the user provided."
                }
            },
            "required": ["word"]
        }
    }
    """.trimIndent()

  override fun execute(paramsJsonString: String): String =
    executeTool("pronounce_word", paramsJsonString)
}

/** Shared entry point for the LiteRT automatic-calling path (the app drives tools manually). */
private fun executeTool(name: String, paramsJsonString: String): String = runBlocking {
  val params = runCatching { JSONObject(paramsJsonString) }.getOrDefault(JSONObject())
  ToolExecutor.toolResponseJson(ToolExecutor.execute(name, params))
}
