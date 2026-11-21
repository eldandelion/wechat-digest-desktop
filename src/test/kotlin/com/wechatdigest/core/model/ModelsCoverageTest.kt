package com.wechatdigest.core.model

import com.wechatdigest.app.Screen
import com.wechatdigest.core.llm.OllamaRequest
import com.wechatdigest.core.llm.OllamaResponse
import com.wechatdigest.core.llm.OpenAiChoice
import com.wechatdigest.core.llm.OpenAiMessage
import com.wechatdigest.core.llm.OpenAiRequest
import com.wechatdigest.core.llm.OpenAiResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelsCoverageTest {

    @Test
    fun `test LLM DTOs defaults and properties`() {
        val extracted = LlmExtractedEvent()
        assertEquals("", extracted.title)
        assertEquals("", extracted.summary)
        assertEquals("", extracted.translatedSummary)
        assertEquals("NOTICE", extracted.category)
        assertEquals("NORMAL", extracted.priority)
        assertEquals(null, extracted.eventDate)
        assertTrue(extracted.sourceMessageIds.isEmpty())

        val copyExtracted = extracted.copy(title = "New Title", eventDate = "2026-11-20")
        assertEquals("New Title", copyExtracted.title)
        assertEquals("2026-11-20", copyExtracted.eventDate)

        val response = LlmExtractionResponse()
        assertFalse(response.isRelevant)
        assertTrue(response.events.isEmpty())

        val copyResp = response.copy(isRelevant = true, events = listOf(copyExtracted))
        assertTrue(copyResp.isRelevant)
        assertEquals(1, copyResp.events.size)
    }

    @Test
    fun `test OpenAI and Ollama DTOs`() {
        val msg = OpenAiMessage(role = "user", content = "hello")
        assertEquals("user", msg.role)
        assertEquals("hello", msg.content)
        val copyMsg = msg.copy(role = "assistant")
        assertEquals("assistant", copyMsg.role)

        val choice = OpenAiChoice(message = msg)
        assertEquals(msg, choice.message)
        val copyChoice = choice.copy(message = copyMsg)
        assertEquals(copyMsg, copyChoice.message)

        val openAiReq = OpenAiRequest(
            model = "gpt-4o",
            messages = listOf(msg),
            temperature = 0.5
        )
        assertEquals("gpt-4o", openAiReq.model)
        assertEquals(0.5, openAiReq.temperature)

        val openAiResp = OpenAiResponse(choices = listOf(choice))
        assertEquals(1, openAiResp.choices.size)

        val ollamaReq = OllamaRequest(
            model = "llama3",
            messages = listOf(msg),
            stream = false
        )
        assertEquals("llama3", ollamaReq.model)
        assertFalse(ollamaReq.stream)

        val ollamaResp = OllamaResponse(message = msg)
        assertEquals(msg, ollamaResp.message)
    }

    @Test
    fun `test Screen enum entries and valueOf`() {
        assertEquals(Screen.EVENTS, Screen.valueOf("EVENTS"))
        assertEquals(Screen.GROUPS, Screen.valueOf("GROUPS"))
        assertEquals(Screen.SETTINGS, Screen.valueOf("SETTINGS"))
        assertEquals(3, Screen.entries.size)
    }

    @Test
    fun `test GroupConfig properties and copy`() {
        val config = GroupConfig(roomId = "room@chatroom")
        assertEquals("room@chatroom", config.roomId)
        assertEquals("", config.customPrompt)
        assertEquals("", config.customPersona)
        assertTrue(config.updatedAt > 0)

        val modified = config.copy(customPrompt = "prompt", customPersona = "persona")
        assertEquals("prompt", modified.customPrompt)
        assertEquals("persona", modified.customPersona)
    }

    @Test
    fun `test EventCard copy and properties`() {
        val card = EventCard(
            id = "c1",
            title = "T",
            summary = "S",
            translatedSummary = "TS",
            category = EventCategory.EXAM,
            priority = EventPriority.CRITICAL,
            originalSender = "Sender",
            sourceRoomId = "room",
            timestampSeconds = 100L
        )
        assertFalse(card.isRead)
        assertFalse(card.isDismissed)

        val readCard = card.copy(isRead = true, isDismissed = true)
        assertTrue(readCard.isRead)
        assertTrue(readCard.isDismissed)
    }
}
