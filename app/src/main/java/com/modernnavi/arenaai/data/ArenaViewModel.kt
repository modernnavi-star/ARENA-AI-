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
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ArenaViewModel : ViewModel() {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = Firebase.firestore
    private val functions = Firebase.functions("us-central1")

    private var chatsListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null

    var uiState by mutableStateOf(ArenaUiState(currentUser = auth.currentUser))
        private set

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        uiState = ArenaUiState(currentUser = user)
        chatsListener?.remove()
        messagesListener?.remove()
        chatsListener = null
        messagesListener = null
        if (user != null) {
            ensureUserProfile()
            listenToChats(user.uid)
        }
    }

    init {
        auth.addAuthStateListener(authListener)
        auth.currentUser?.let {
            ensureUserProfile()
            listenToChats(it.uid)
        }
    }

    fun setInput(value: String) {
        uiState = uiState.copy(inputText = value, error = null)
    }

    fun setMode(mode: ArenaMode) {
        uiState = uiState.copy(mode = mode)
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
        uiState = uiState.copy(inputText = "", isSending = true, error = null)

        viewModelScope.launch {
            try {
                ensureUserProfile()
                val result = functions
                    .getHttpsCallable("sendArenaPrompt")
                    .call(
                        mapOf(
                            "prompt" to prompt,
                            "chatId" to selectedChatId,
                            "mode" to mode.wireName
                        )
                    )
                    .await()

                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any?>
                val returnedChatId = data?.get("chatId") as? String
                if (returnedChatId != null && returnedChatId != uiState.selectedChatId) {
                    selectChat(returnedChatId)
                }
            } catch (throwable: Throwable) {
                uiState = uiState.copy(
                    inputText = prompt,
                    error = throwable.localizedMessage ?: "Unable to get an AI response."
                )
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
            .set(profile, com.google.firebase.firestore.SetOptions.merge())
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        chatsListener?.remove()
        messagesListener?.remove()
        super.onCleared()
    }
}
