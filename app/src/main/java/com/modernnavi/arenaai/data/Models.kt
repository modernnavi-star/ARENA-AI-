package com.modernnavi.arenaai.data

import com.google.firebase.auth.FirebaseUser

/** Chat routing mode exposed to users. */
enum class ArenaMode(val wireName: String, val label: String, val description: String) {
    RANDOM("random", "Random AI", "One available model is chosen automatically."),
    DUEL("duel", "Arena Duel", "Two models answer side-by-side so you can compare.")
}

enum class AiModelChoice(val wireName: String, val label: String, val description: String) {
    RANDOM("random", "Random", "Let Arena choose"),
    GEMINI("gemini", "Gemini", "Google model"),
    OPENAI("openai", "OpenAI", "GPT model"),
    CLAUDE("anthropic", "Claude", "Anthropic model"),
    MISTRAL("mistral", "Mistral", "Fast open model")
}

data class ChatSummary(
    val id: String,
    val title: String,
    val lastMessage: String,
    val modelUsed: String,
    val updatedAtMillis: Long
)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val modelUsed: String?,
    val createdAtMillis: Long
)

data class WorkspaceArtifact(
    val id: String,
    val fileName: String,
    val fileType: String,
    val content: String,
    val chatId: String?,
    val createdAtMillis: Long
)

data class ArenaUiState(
    val currentUser: FirebaseUser? = null,
    val chats: List<ChatSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val artifacts: List<WorkspaceArtifact> = emptyList(),
    val selectedChatId: String? = null,
    val inputText: String = "",
    val mode: ArenaMode = ArenaMode.RANDOM,
    val selectedModel: AiModelChoice = AiModelChoice.RANDOM,
    val isSending: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val error: String? = null
)
