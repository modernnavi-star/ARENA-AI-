package com.modernnavi.arenaai.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.modernnavi.arenaai.data.AiModelChoice
import com.modernnavi.arenaai.data.ArenaMode
import com.modernnavi.arenaai.data.ArenaUiState
import com.modernnavi.arenaai.data.ArenaViewModel
import com.modernnavi.arenaai.data.ChatMessage
import com.modernnavi.arenaai.data.ChatSummary
import com.modernnavi.arenaai.data.WorkspaceArtifact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppTab(val label: String) { CHAT("Chat"), HISTORY("History"), WORKSPACE("Workspace"), SETTINGS("Settings") }

@Composable
fun ArenaAiApp(viewModel: ArenaViewModel) {
    val state = viewModel.uiState
    if (state.currentUser == null) {
        SignInScreen()
    } else {
        WebArenaScreen(
            state = state,
            onSignOut = viewModel::signOut
        )
    }
}

@Composable
private fun SignInScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val auth = remember { FirebaseAuth.getInstance() }
    var error by remember { mutableStateOf<String?>(null) }
    var isSigningIn by remember { mutableStateOf(false) }

    val webClientId = remember(context) {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) "" else context.getString(id)
    }
    val googleAppId = remember(context) {
        val id = context.resources.getIdentifier("google_app_id", "string", context.packageName)
        if (id == 0) "" else context.getString(id)
    }
    val isDemoFirebaseConfig = webClientId.startsWith("123456789012-") || googleAppId.contains("abcdef1234567890")

    val googleSignInClient = remember(webClientId) {
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .apply { if (webClientId.isNotBlank()) requestIdToken(webClientId) }
                .build()
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            isSigningIn = false
            error = "Google sign-in did not complete. If you did not press Back, check that Firebase Google login is enabled and this APK signing SHA-1/SHA-256 is added in Firebase."
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val token = account.idToken
            if (token.isNullOrBlank()) {
                isSigningIn = false
                error = "Missing Google ID token. Check your Firebase web client configuration."
            } else {
                val credential = GoogleAuthProvider.getCredential(token, null)
                auth.signInWithCredential(credential)
                    .addOnCompleteListener { signInTask ->
                        isSigningIn = false
                        if (!signInTask.isSuccessful) {
                            error = signInTask.exception?.localizedMessage ?: "Firebase sign-in failed."
                        }
                    }
            }
        } catch (exception: ApiException) {
            isSigningIn = false
            error = exception.localizedMessage ?: "Google sign-in failed."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(42.dp))
                }
                Text("Arena AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "A user-friendly AI arena that can route complex tasks to random or competing models, then safely sync history with your Google account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (webClientId.isBlank()) {
                    SetupWarning("Setup needed: download google-services.json from Firebase and place it in app/ to generate the Google web client ID.")
                } else if (isDemoFirebaseConfig) {
                    SetupWarning("This APK was built with demo Firebase settings, so Google sign-in cannot work. Add your real Firebase google-services.json or GitHub secret, then rebuild the APK.")
                }
                Button(
                    onClick = {
                        error = null
                        if (activity == null) {
                            error = "Unable to start sign-in from this screen."
                        } else if (webClientId.isBlank()) {
                            error = "Add app/google-services.json from Firebase before enabling Google login."
                        } else if (isDemoFirebaseConfig) {
                            error = "This demo APK cannot use Google login. Build again with your real Firebase google-services.json."
                        } else {
                            isSigningIn = true
                            launcher.launch(googleSignInClient.signInIntent)
                        }
                    },
                    enabled = !isSigningIn && !isDemoFirebaseConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Login, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(if (isSigningIn) "Signing in..." else "Continue with Google")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SetupWarning(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScaffold(
    state: ArenaUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onModeChange: (ArenaMode) -> Unit,
    onModelChange: (AiModelChoice) -> Unit,
    onSelectChat: (String?) -> Unit,
    onNewChat: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onSignOut: () -> Unit,
    onClearError: () -> Unit
) {
    var tab by remember { mutableStateOf(AppTab.CHAT) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            onClearError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Arena AI") },
                actions = {
                    IconButton(onClick = {
                        onNewChat()
                        tab = AppTab.CHAT
                    }) { Icon(Icons.Rounded.Add, contentDescription = "New chat") }
                }
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = tab == AppTab.CHAT,
                    onClick = { tab = AppTab.CHAT },
                    icon = { Icon(Icons.Rounded.ChatBubble, contentDescription = null) },
                    label = { Text(AppTab.CHAT.label) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.HISTORY,
                    onClick = { tab = AppTab.HISTORY },
                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    label = { Text(AppTab.HISTORY.label) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.WORKSPACE,
                    onClick = { tab = AppTab.WORKSPACE },
                    icon = { Icon(Icons.Rounded.SmartToy, contentDescription = null) },
                    label = { Text(AppTab.WORKSPACE.label) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.SETTINGS,
                    onClick = { tab = AppTab.SETTINGS },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(AppTab.SETTINGS.label) }
                )
            }
        }
    ) { padding ->
        when (tab) {
            AppTab.CHAT -> ChatScreen(
                state = state,
                onInputChange = onInputChange,
                onSend = onSend,
                onModeChange = onModeChange,
                onModelChange = onModelChange,
                onOpenHistory = { tab = AppTab.HISTORY },
                modifier = Modifier.padding(padding)
            )
            AppTab.HISTORY -> HistoryScreen(
                state = state,
                onSelectChat = {
                    onSelectChat(it)
                    tab = AppTab.CHAT
                },
                onDeleteChat = onDeleteChat,
                modifier = Modifier.padding(padding)
            )
            AppTab.WORKSPACE -> WorkspaceScreen(
                artifacts = state.artifacts,
                modifier = Modifier.padding(padding)
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                onSignOut = onSignOut,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ChatScreen(
    state: ArenaUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onModeChange: (ArenaMode) -> Unit,
    onModelChange: (AiModelChoice) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModeSelector(selected = state.mode, onModeChange = onModeChange)
        ModelSelector(selected = state.selectedModel, onModelChange = onModelChange)

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.selectedChatId == null && state.messages.isEmpty() && !state.isSending) {
                EmptyChat(
                    onOpenHistory = onOpenHistory,
                    onSamplePrompt = onInputChange
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                    if (state.isSending) {
                        item { TypingBubble(state.mode) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.inputText,
                onValueChange = onInputChange,
                minLines = 1,
                maxLines = 5,
                placeholder = { Text("Message Arena AI...") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSend, enabled = state.inputText.isNotBlank() && !state.isSending) {
                Icon(Icons.Rounded.Send, contentDescription = "Send")
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ModeSelector(selected: ArenaMode, onModeChange: (ArenaMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArenaMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(mode.label) }
                )
            }
        }
        Text(selected.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun ModelSelector(selected: AiModelChoice, onModelChange: (AiModelChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Models", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AiModelChoice.entries, key = { it.wireName }) { model ->
                FilterChip(
                    selected = selected == model,
                    onClick = { onModelChange(model) },
                    label = { Text(model.label) }
                )
            }
        }
        Text(selected.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyChat(onOpenHistory: () -> Unit, onSamplePrompt: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Ready for your next task", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Pick a model, choose Random AI or Arena Duel, then type below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(
                listOf(
                    "Plan my study schedule",
                    "Write code for an app screen",
                    "Compare two phones",
                    "Summarize a PDF idea"
                )
            ) { prompt ->
                AssistChip(onClick = { onSamplePrompt(prompt) }, label = { Text(prompt) })
            }
        }
        AssistChip(onClick = onOpenHistory, label = { Text("Open saved history") }, leadingIcon = { Icon(Icons.Rounded.History, null) })
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            ),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                if (!message.modelUsed.isNullOrBlank()) {
                    Text(
                        "Model: ${message.modelUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TypingBubble(mode: ArenaMode) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
            Text(
                if (mode == ArenaMode.DUEL) "Two AI models are competing..." else "Random AI is thinking...",
                modifier = Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    state: ArenaUiState,
    onSelectChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, state.chats) {
        if (query.isBlank()) state.chats else state.chats.filter {
            it.title.contains(query, ignoreCase = true) || it.lastMessage.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search saved chats") },
            modifier = Modifier.fillMaxWidth()
        )
        if (filtered.isEmpty()) {
            Text(
                if (state.isLoadingHistory) "Loading history..." else "No saved chats yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { chat ->
                    ChatHistoryItem(chat, onOpen = { onSelectChat(chat.id) }, onDelete = { onDeleteChat(chat.id) })
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryItem(chat: ChatSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onOpen, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chat.lastMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(formatDate(chat.updatedAtMillis), chat.modelUsed).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete chat") }
        }
    }
}

@Composable
private fun WorkspaceScreen(artifacts: List<WorkspaceArtifact>, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var selectedArtifact by remember { mutableStateOf<WorkspaceArtifact?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Workspace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Generated files from chats appear here. Copy Markdown/HTML into any editor, browser, or PDF converter.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        if (artifacts.isEmpty()) {
            Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No generated files yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ask Arena AI to write an article, plan, code, report, or comparison. The app will save generated Markdown and HTML/PDF-ready files here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(artifacts, key = { it.id }) { artifact ->
                    Card(onClick = { selectedArtifact = artifact }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(artifact.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(artifact.fileType, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(artifact.content.take(160), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        selectedArtifact?.let { artifact ->
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Preview: ${artifact.fileName}", fontWeight = FontWeight.SemiBold)
                    Text(artifact.content.take(900), maxLines = 10, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { clipboard.setText(AnnotatedString(artifact.content)) }) {
                            Text("Copy file content")
                        }
                        TextButton(onClick = { selectedArtifact = null }) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: ArenaUiState, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    val user = state.currentUser
    Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(user?.displayName ?: "Arena user", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(user?.email ?: "Google account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Your chats are stored under your Firebase user ID in Firestore, so history restores automatically after Google login on another Android device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Saved chats: ${state.chats.size}", style = MaterialTheme.typography.labelLarge)
            }
        }

        Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "AI API keys stay on Firebase Cloud Functions. The Android app only calls an authenticated backend function.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Rounded.Logout, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Sign out")
        }
    }
}

private fun formatDate(millis: Long): String {
    if (millis <= 0L) return ""
    return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(millis))
}
