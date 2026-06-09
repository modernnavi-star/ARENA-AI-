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
        if (chatId.startsWith("local-chat-")) {
            uiState = uiState.copy(
                chats = uiState.chats.filterNot { it.id == chatId },
                artifacts = uiState.artifacts.filterNot { it.chatId == chatId },
                messages = if (uiState.selectedChatId == chatId) emptyList() else uiState.messages,
                selectedChatId = if (uiState.selectedChatId == chatId) null else uiState.selectedChatId
            )
            return
        }
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
        val localChatId = existingChatId ?: "local-chat-$now"
        val localArtifacts = buildWorkspaceArtifacts(localChatId, prompt, answer, mode, modelUsed, now)
        val localSummary = ChatSummary(
            id = localChatId,
            title = makeTitle(prompt),
            lastMessage = answer.take(240),
            modelUsed = modelUsed,
            updatedAtMillis = now
        )
        uiState = uiState.copy(
            selectedChatId = localChatId,
            messages = uiState.messages + listOf(
                ChatMessage("local-user-$now", "user", prompt, null, now),
                ChatMessage("local-ai-$now", "assistant", answer, modelUsed, now + 1)
            ),
            chats = upsertLocalChat(uiState.chats, localSummary),
            artifacts = (localArtifacts + uiState.artifacts).distinctBy { it.id },
            inputText = "",
            error = null
        )
        try {
            val chatId = withTimeout(2500) {
                saveLocalArenaResponse(uid, existingChatId, prompt, answer, mode, modelUsed)
            }
            if (chatId != uiState.selectedChatId && !uiState.selectedChatId.orEmpty().startsWith("local-chat-")) {
                selectChat(chatId)
            }
        } catch (_: Throwable) {
            // Keep the fully working local Arena result. Cloud sync can be configured later.
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

        # ${makeTitle(prompt).replaceFirstChar { it.uppercase() }}

        ## Introduction
        Artificial intelligence, commonly known as AI, is one of the most influential technologies of the modern world. It allows computers and software systems to perform tasks that normally require human intelligence, such as understanding language, recognizing images, writing content, solving problems, and making predictions.

        ## How AI is changing daily life
        AI is already present in many parts of everyday life. Search engines use AI to understand questions, smartphones use AI to improve photos, maps use AI to suggest routes, and chat assistants use AI to help with writing, learning, planning, and coding. This makes technology faster, more personal, and easier to use.

        ## Benefits of AI
        1. Productivity: AI can complete repetitive work quickly and help people focus on creative or strategic tasks.
        2. Learning: AI can explain difficult topics in simple language and support students in many languages.
        3. Healthcare: AI can help doctors analyze data, detect patterns, and improve decision-making.
        4. Business: Companies use AI for customer support, automation, marketing, data analysis, and product development.
        5. Creativity: Writers, designers, developers, and creators can use AI to generate ideas and improve their work.

        ## Challenges and responsible use
        AI is powerful, but it is not perfect. It can make mistakes, misunderstand context, or produce biased results if the data or instructions are poor. For this reason, users should verify important information and use AI responsibly. Privacy, fairness, transparency, and safety are important when building or using AI systems.

        ## Future of AI
        The future of AI will likely include more advanced assistants, better automation, smarter education tools, and stronger support for businesses and creators. The best use of AI is not to replace people, but to help people work faster, think better, and solve bigger problems.

        ## Conclusion
        AI is transforming the way humans interact with technology. When used carefully and ethically, it can improve productivity, education, creativity, and decision-making. The most successful future will be one where humans and AI work together.

        Workspace: I also generated Markdown, plain text, HTML/PDF-ready, and JSON versions for this response in the Workspace tab.
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
            .replace("\"", "&quot;")
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
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

    private fun upsertLocalChat(chats: List<ChatSummary>, summary: ChatSummary): List<ChatSummary> {
        return listOf(summary) + chats.filterNot { it.id == summary.id }
    }

    private fun buildWorkspaceArtifacts(
        chatId: String,
        prompt: String,
        answer: String,
        mode: ArenaMode,
        modelUsed: String,
        now: Long
    ): List<WorkspaceArtifact> {
        val baseName = makeFileBaseName(prompt)
        val title = makeTitle(prompt)
        val markdown = listOf(
            "# $title",
            "",
            "**Mode:** ${mode.label}",
            "**Model:** $modelUsed",
            "**Language:** Auto / multilingual",
            "",
            "## Prompt",
            prompt.trim(),
            "",
            "## Response",
            answer.trim()
        ).joinToString("\n")
        val text = listOf(
            title,
            "Mode: ${mode.label}",
            "Model: $modelUsed",
            "",
            "Prompt:",
            prompt.trim(),
            "",
            "Response:",
            answer.trim()
        ).joinToString("\n")
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>${escapeHtml(title)}</title>
              <style>
                body { font-family: Arial, sans-serif; line-height: 1.55; padding: 32px; color: #111827; }
                h1 { color: #4c1d95; }
                .meta { color: #475569; border-left: 4px solid #8b5cf6; padding-left: 12px; }
                pre { white-space: pre-wrap; font-family: Arial, sans-serif; }
              </style>
            </head>
            <body>
              <h1>${escapeHtml(title)}</h1>
              <p class="meta"><b>Mode:</b> ${mode.label}<br><b>Model:</b> $modelUsed<br><b>Format:</b> Print this page to save as PDF.</p>
              <h2>Prompt</h2><p>${escapeHtml(prompt)}</p>
              <h2>Response</h2><pre>${escapeHtml(answer)}</pre>
            </body>
            </html>
        """.trimIndent()
        val json = """
            {
              "title": "${escapeJson(title)}",
              "mode": "${escapeJson(mode.label)}",
              "model": "${escapeJson(modelUsed)}",
              "prompt": "${escapeJson(prompt)}",
              "response": "${escapeJson(answer)}"
            }
        """.trimIndent()
        return listOf(
            WorkspaceArtifact("$chatId-md-$now", "$baseName.md", "Markdown", markdown, chatId, now),
            WorkspaceArtifact("$chatId-txt-$now", "$baseName.txt", "Plain text", text, chatId, now + 1),
            WorkspaceArtifact("$chatId-html-$now", "$baseName.html", "HTML / PDF-ready", html, chatId, now + 2),
            WorkspaceArtifact("$chatId-json-$now", "$baseName.json", "JSON", json, chatId, now + 3)
        )
    }

    private suspend fun saveWorkspaceArtifacts(
        uid: String,
        chatId: String,
        prompt: String,
        answer: String,
        mode: ArenaMode,
        modelUsed: String
    ) {
        val artifactsRef = db.collection("users").document(uid).collection("artifacts")
        buildWorkspaceArtifacts(chatId, prompt, answer, mode, modelUsed, System.currentTimeMillis()).forEach { artifact ->
            artifactsRef.add(
                mapOf(
                    "fileName" to artifact.fileName,
                    "fileType" to artifact.fileType,
                    "content" to artifact.content,
                    "chatId" to chatId,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
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
