package com.modernnavi.arenaai.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await

class ArenaViewModel : ViewModel() {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = Firebase.firestore
    private val functions = Firebase.functions("us-central1")

    private var chatsListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var artifactsListener: ListenerRegistration? = null

    var uiState by mutableStateOf(ArenaUiState(currentUser = auth.currentUser))
        private set

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        uiState = ArenaUiState(currentUser = user)
        chatsListener?.remove()
        messagesListener?.remove()
        artifactsListener?.remove()
        chatsListener = null
        messagesListener = null
        artifactsListener = null
        if (user != null) {
            ensureUserProfile()
            listenToChats(user.uid)
            listenToArtifacts(user.uid)
        }
    }

    init {
        auth.addAuthStateListener(authListener)
        auth.currentUser?.let {
            ensureUserProfile()
            listenToChats(it.uid)
            listenToArtifacts(it.uid)
        }
    }

    fun setInput(value: String) {
        uiState = uiState.copy(inputText = value, error = null)
    }

    fun setMode(mode: ArenaMode) {
        uiState = uiState.copy(mode = mode)
    }

    fun setModelChoice(model: AiModelChoice) {
        uiState = uiState.copy(selectedModel = model)
    }

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    fun selectChat(chatId: String?) {
        uiState = uiState.copy(selectedChatId = chatId, messages = emptyList(), error = null)
        messagesListener?.remove()
        val uid = uiState.currentUser?.uid ?: return
        if (chatId == null) return
        messagesListener = db.collection("users")
            .document(uid)
            .collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    uiState = uiState.copy(error = error.localizedMessage)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents.orEmpty().map { doc ->
                    ChatMessage(
                        id = doc.id,
                        role = doc.getString("role") ?: "assistant",
                        content = doc.getString("content") ?: "",
                        modelUsed = doc.getString("modelUsed"),
                        createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    )
                }
                uiState = uiState.copy(messages = messages)
            }
    }

    fun newChat() {
        selectChat(null)
    }

    fun sendPrompt() {
        val user = uiState.currentUser ?: return
        val prompt = uiState.inputText.trim()
        if (prompt.isEmpty() || uiState.isSending) return

        val selectedChatId = uiState.selectedChatId
        val mode = uiState.mode
        val selectedModel = uiState.selectedModel
        uiState = uiState.copy(inputText = "", isSending = true, error = null)

        viewModelScope.launch {
            try {
                ensureUserProfile()
                val result = withTimeout(4000) {
                    functions
                        .getHttpsCallable("sendArenaPrompt")
                        .call(
                            mapOf(
                                "prompt" to prompt,
                                "chatId" to selectedChatId,
                                "mode" to mode.wireName,
                                "model" to selectedModel.wireName
                            )
                        )
                        .await()
                }

                @Suppress("UNCHECKED_CAST")
                val data = result.getData() as? Map<String, Any?>
                val returnedChatId = data?.get("chatId") as? String
                if (returnedChatId != null && returnedChatId != uiState.selectedChatId) {
                    selectChat(returnedChatId)
                }
            } catch (throwable: Throwable) {
                if (shouldUseLocalArenaFallback(throwable)) {
                    showLocalArenaResponse(
                        uid = user.uid,
                        existingChatId = selectedChatId,
                        prompt = prompt,
                        mode = mode,
                        selectedModel = selectedModel
                    )
                } else {
                    uiState = uiState.copy(
                        inputText = prompt,
                        error = throwable.localizedMessage ?: "Unable to get an AI response."
                    )
                }
            } finally {
                if (uiState.currentUser?.uid == user.uid) {
                    uiState = uiState.copy(isSending = false)
                }
            }
        }
    }

    fun deleteChat(chatId: String) {
        val uid = uiState.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val chatRef = db.collection("users").document(uid).collection("chats").document(chatId)
                val messages = chatRef.collection("messages").get().await()
                val batch = db.batch()
                messages.documents.forEach { batch.delete(it.reference) }
                batch.delete(chatRef)
                batch.commit().await()
                if (uiState.selectedChatId == chatId) selectChat(null)
            } catch (throwable: Throwable) {
                uiState = uiState.copy(error = throwable.localizedMessage)
            }
        }
    }

    fun signOut() {
        auth.signOut()
    }

    private suspend fun showLocalArenaResponse(
        uid: String,
        existingChatId: String?,
        prompt: String,
        mode: ArenaMode,
        selectedModel: AiModelChoice
    ) {
        val answer = buildLocalArenaResponse(prompt, mode, selectedModel)
        val modelUsed = localModelName(mode, selectedModel)
        val now = System.currentTimeMillis()
        uiState = uiState.copy(
            messages = uiState.messages + listOf(
                ChatMessage("local-user-$now", "user", prompt, null, now),
                ChatMessage("local-ai-$now", "assistant", answer, modelUsed, now + 1)
            ),
            error = null
        )
        try {
            val chatId = withTimeout(6000) {
                saveLocalArenaResponse(uid, existingChatId, prompt, answer, mode, modelUsed)
            }
            if (chatId != uiState.selectedChatId) {
                selectChat(chatId)
            }
        } catch (saveError: Throwable) {
            uiState = uiState.copy(
                error = "Response shown. Cloud save is pending or unavailable: ${saveError.localizedMessage ?: "unknown error"}"
            )
        }
    }

    private suspend fun saveLocalArenaResponse(
        uid: String,
        existingChatId: String?,
        prompt: String,
        answer: String,
        mode: ArenaMode,
        modelUsed: String
    ): String {
        val chatsRef = db.collection("users").document(uid).collection("chats")
        val chatRef = existingChatId?.let { chatsRef.document(it) } ?: chatsRef.document()
        if (existingChatId == null) {
            chatRef.set(
                mapOf(
                    "title" to makeTitle(prompt),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "lastMessage" to prompt.take(240),
                    "modelUsed" to modelUsed,
                    "mode" to mode.wireName
                )
            ).await()
        }

        chatRef.collection("messages").add(
            mapOf(
                "role" to "user",
                "content" to prompt,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()

        chatRef.collection("messages").add(
            mapOf(
                "role" to "assistant",
                "content" to answer,
                "modelUsed" to modelUsed,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()

        chatRef.set(
            mapOf(
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastMessage" to answer.take(240),
                "modelUsed" to modelUsed,
                "mode" to mode.wireName
            ),
            SetOptions.merge()
        ).await()

        saveWorkspaceArtifacts(uid, chatRef.id, prompt, answer, mode, modelUsed)
        return chatRef.id
    }

    private fun shouldUseLocalArenaFallback(throwable: Throwable): Boolean {
        val functionsError = throwable as? FirebaseFunctionsException
        return throwable is TimeoutCancellationException ||
            functionsError?.code == FirebaseFunctionsException.Code.NOT_FOUND ||
            functionsError?.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
            functionsError?.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
            throwable.localizedMessage?.contains("NOT_FOUND", ignoreCase = true) == true ||
            throwable.localizedMessage?.contains("timeout", ignoreCase = true) == true
    }

    private fun buildLocalArenaResponse(prompt: String, mode: ArenaMode, selectedModel: AiModelChoice): String {
        val primary = buildStructuredArenaAnswer(prompt, selectedModel.label)
        if (mode != ArenaMode.DUEL) return primary

        val challenger = buildAlternativeArenaAnswer(prompt)
        return listOf(
            "🏁 Arena Duel Result",
            "",
            "A — ${selectedModel.label} style",
            primary,
            "",
            "B — Reasoning style",
            challenger,
            "",
            "Verdict: Use answer A for a polished final response and answer B as a checklist to improve it."
        ).joinToString("\n")
    }

    private fun buildStructuredArenaAnswer(prompt: String, modelLabel: String): String {
        val lower = prompt.lowercase()
        return when {
            listOf("article", "blog", "essay", "write").any { lower.contains(it) } -> buildArticleAnswer(prompt, modelLabel)
            listOf("code", "app", "android", "kotlin", "firebase").any { lower.contains(it) } -> buildTechnicalPlan(prompt, modelLabel)
            listOf("compare", "vs", "difference").any { lower.contains(it) } -> buildComparisonAnswer(prompt, modelLabel)
            else -> buildGeneralTaskAnswer(prompt, modelLabel)
        }
    }

    private fun buildArticleAnswer(prompt: String, modelLabel: String): String = """
        $modelLabel response

        Title: ${makeTitle(prompt).replaceFirstChar { it.uppercase() }}

        Introduction
        Artificial intelligence is becoming one of the most important technologies in modern life. It helps people create content, solve problems, automate work, learn faster, and make better decisions.

        Main points
        1. AI improves productivity by handling repetitive tasks and giving quick suggestions.
        2. AI supports creativity by helping with writing, design, coding, planning, and research.
        3. AI can personalize learning and make complex topics easier to understand.
        4. Responsible use is important because AI can make mistakes, reflect bias, or miss context.

        Conclusion
        AI is powerful when used as an assistant, not as a replacement for human judgment. The best results come from clear instructions, careful review, and ethical use.
    """.trimIndent()

    private fun buildTechnicalPlan(prompt: String, modelLabel: String): String = """
        $modelLabel response

        Goal
        ${prompt.trim()}

        Recommended plan
        1. Define the exact feature and user flow.
        2. Create a simple UI first, then connect data and backend logic.
        3. Keep API keys on a backend service, not inside the APK.
        4. Save user data under the signed-in user's ID.
        5. Test login, history saving, error states, and offline/slow-network behavior.

        Implementation checklist
        - Authentication
        - Chat input and response screen
        - History storage
        - Model selector
        - Error fallback
        - Secure backend deployment
    """.trimIndent()

    private fun buildComparisonAnswer(prompt: String, modelLabel: String): String = """
        $modelLabel response

        Comparison request: ${prompt.trim()}

        Quick comparison framework
        - Accuracy: Which option gives more correct and verifiable output?
        - Speed: Which option responds faster for daily use?
        - Cost: Which option is cheaper at scale?
        - Privacy: Which option gives better control over user data?
        - Features: Which option supports text, code, images, files, or tools?

        Recommendation
        Choose the option that balances accuracy, cost, and user experience for your actual use case. If this is for an app, start with one reliable model and add arena comparison after the MVP works.
    """.trimIndent()

    private fun buildGeneralTaskAnswer(prompt: String, modelLabel: String): String = """
        $modelLabel response

        I understand your task:
        ${prompt.trim()}

        Best next steps
        1. Clarify the final output you want.
        2. Break the task into smaller parts.
        3. Create a first draft or plan.
        4. Review for accuracy, missing details, and improvements.
        5. Finalize the answer in a clean format.

        Practical answer
        Start with the most important requirement, keep the result simple, and improve it step by step. If you want, send more details and I can turn this into a polished final version.
    """.trimIndent()

    private fun buildAlternativeArenaAnswer(prompt: String): String = """
        Reasoning checklist

        Task: ${prompt.trim()}

        - What is the user's real goal?
        - What output format is most useful?
        - What assumptions need checking?
        - What would make the answer actionable?
        - What risks or missing information should be mentioned?

        Improved direction
        Produce a clear answer with sections, examples, and next steps. Avoid vague claims and make the response easy to use immediately.
    """.trimIndent()

    private fun makeFileBaseName(prompt: String): String {
        return makeTitle(prompt)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "arena-response" }
            .take(40)
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(""", "&quot;")
    }

    private fun localModelName(mode: ArenaMode, selectedModel: AiModelChoice): String {
        return if (mode == ArenaMode.DUEL) {
            "Arena Local Duel"
        } else {
            "Arena Local ${selectedModel.label}"
        }
    }

    private fun makeTitle(prompt: String): String {
        return prompt.replace(Regex("\\s+"), " ").trim().take(64).ifBlank { "New chat" }
    }

    private suspend fun saveWorkspaceArtifacts(
        uid: String,
        chatId: String,
        prompt: String,
        answer: String,
        mode: ArenaMode,
        modelUsed: String
    ) {
        val baseName = makeFileBaseName(prompt)
        val artifactsRef = db.collection("users").document(uid).collection("artifacts")
        val markdown = listOf(
            "# ${makeTitle(prompt)}",
            "",
            "**Mode:** ${mode.label}",
            "**Model:** $modelUsed",
            "",
            "## Prompt",
            prompt.trim(),
            "",
            "## Response",
            answer.trim()
        ).joinToString("\n")
        artifactsRef.add(
            mapOf(
                "fileName" to "$baseName.md",
                "fileType" to "Markdown",
                "content" to markdown,
                "chatId" to chatId,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()

        val html = """
            <!doctype html>
            <html><head><meta charset=\"utf-8\"><title>${makeTitle(prompt)}</title></head>
            <body style=\"font-family: sans-serif; line-height: 1.5; padding: 24px;\">
            <h1>${makeTitle(prompt)}</h1>
            <p><b>Mode:</b> ${mode.label}<br><b>Model:</b> $modelUsed</p>
            <h2>Prompt</h2><p>${escapeHtml(prompt)}</p>
            <h2>Response</h2><pre style=\"white-space: pre-wrap; font-family: sans-serif;\">${escapeHtml(answer)}</pre>
            </body></html>
        """.trimIndent()
        artifactsRef.add(
            mapOf(
                "fileName" to "$baseName.html",
                "fileType" to "HTML / PDF-ready",
                "content" to html,
                "chatId" to chatId,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    private fun listenToArtifacts(uid: String) {
        artifactsListener?.remove()
        artifactsListener = db.collection("users")
            .document(uid)
            .collection("artifacts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    uiState = uiState.copy(error = error.localizedMessage)
                    return@addSnapshotListener
                }
                val artifacts = snapshot?.documents.orEmpty().map { doc ->
                    WorkspaceArtifact(
                        id = doc.id,
                        fileName = doc.getString("fileName") ?: "generated-file.md",
                        fileType = doc.getString("fileType") ?: "Text",
                        content = doc.getString("content") ?: "",
                        chatId = doc.getString("chatId"),
                        createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    )
                }
                uiState = uiState.copy(artifacts = artifacts)
            }
    }

    private fun listenToChats(uid: String) {
        uiState = uiState.copy(isLoadingHistory = true)
        chatsListener = db.collection("users")
            .document(uid)
            .collection("chats")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    uiState = uiState.copy(isLoadingHistory = false, error = error.localizedMessage)
                    return@addSnapshotListener
                }
                val chats = snapshot?.documents.orEmpty().map { doc ->
                    ChatSummary(
                        id = doc.id,
                        title = doc.getString("title") ?: "New chat",
                        lastMessage = doc.getString("lastMessage") ?: "",
                        modelUsed = doc.getString("modelUsed") ?: "",
                        updatedAtMillis = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                    )
                }
                uiState = uiState.copy(chats = chats, isLoadingHistory = false)
            }
    }

    private fun ensureUserProfile() {
        val user = auth.currentUser ?: return
        val profile = hashMapOf(
            "uid" to user.uid,
            "displayName" to (user.displayName ?: ""),
            "email" to (user.email ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "lastLoginAt" to FieldValue.serverTimestamp()
        )
        db.collection("users")
            .document(user.uid)
            .set(profile, SetOptions.merge())
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        chatsListener?.remove()
        messagesListener?.remove()
        artifactsListener?.remove()
        super.onCleared()
    }
}
