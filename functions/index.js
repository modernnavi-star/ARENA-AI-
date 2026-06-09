const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const crypto = require("node:crypto");

admin.initializeApp();

const db = admin.firestore();
const REGION = "us-central1";
const MAX_PROMPT_LENGTH = 20000;

exports.sendArenaPrompt = onCall(
  {
    region: REGION,
    timeoutSeconds: 180,
    memory: "512MiB",
  },
  async (request) => {
    const uid = request.auth && request.auth.uid;
    if (!uid) {
      throw new HttpsError("unauthenticated", "Sign in with Google before sending prompts.");
    }

    const data = request.data || {};
    const prompt = typeof data.prompt === "string" ? data.prompt.trim() : "";
    const mode = data.mode === "duel" ? "duel" : "random";
    const requestedModel = typeof data.model === "string" && data.model.trim()
      ? data.model.trim().toLowerCase()
      : "random";
    const requestedChatId = typeof data.chatId === "string" && data.chatId.trim() ? data.chatId.trim() : null;

    if (!prompt) {
      throw new HttpsError("invalid-argument", "Prompt cannot be empty.");
    }
    if (prompt.length > MAX_PROMPT_LENGTH) {
      throw new HttpsError("invalid-argument", `Prompt is too long. Limit is ${MAX_PROMPT_LENGTH} characters.`);
    }

    const userRef = db.collection("users").doc(uid);
    const chatsRef = userRef.collection("chats");
    const chatRef = requestedChatId ? chatsRef.doc(requestedChatId) : chatsRef.doc();

    if (requestedChatId) {
      const existingChat = await chatRef.get();
      if (!existingChat.exists) {
        throw new HttpsError("not-found", "The selected chat was not found for this user.");
      }
    }

    const previousMessages = await loadRecentMessages(chatRef, 12);
    await upsertUserDocument(userRef, request.auth.token || {});

    if (!requestedChatId) {
      await chatRef.set({
        title: makeTitle(prompt),
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        lastMessage: prompt.slice(0, 240),
        modelUsed: "pending",
      });
    }

    await chatRef.collection("messages").add({
      role: "user",
      content: prompt,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const aiMessages = [...previousMessages, { role: "user", content: prompt }];
    const answerPayload = mode === "duel"
      ? await runDuel(aiMessages, requestedModel)
      : await runRandom(aiMessages, requestedModel);

    await chatRef.collection("messages").add({
      role: "assistant",
      content: answerPayload.answer,
      modelUsed: answerPayload.modelUsed,
      candidates: answerPayload.candidates || [],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    await chatRef.set({
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      lastMessage: answerPayload.answer.slice(0, 240),
      modelUsed: answerPayload.modelUsed,
      mode,
    }, { merge: true });

    return {
      chatId: chatRef.id,
      answer: answerPayload.answer,
      modelUsed: answerPayload.modelUsed,
      candidates: answerPayload.candidates || [],
    };
  }
);

async function upsertUserDocument(userRef, token) {
  const snapshot = await userRef.get();
  const profile = {
    uid: userRef.id,
    email: token.email || "",
    displayName: token.name || "",
    photoUrl: token.picture || "",
    lastSeenAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  if (!snapshot.exists) {
    profile.createdAt = admin.firestore.FieldValue.serverTimestamp();
  }
  await userRef.set(profile, { merge: true });
}

async function loadRecentMessages(chatRef, limit) {
  const snapshot = await chatRef.collection("messages")
    .orderBy("createdAt", "desc")
    .limit(limit)
    .get();

  return snapshot.docs
    .reverse()
    .map((doc) => {
      const data = doc.data() || {};
      return {
        role: data.role === "assistant" ? "assistant" : "user",
        content: String(data.content || "").slice(0, MAX_PROMPT_LENGTH),
      };
    })
    .filter((message) => message.content.trim());
}

async function runRandom(messages, requestedModel = "random") {
  const providers = configuredProviders();
  const provider = chooseProvider(providers, requestedModel);
  if (!provider) {
    return missingProviderResponse(requestedModel);
  }
  const response = await callProvider(provider, messages);
  return {
    answer: response.content,
    modelUsed: response.model,
    candidates: [{ model: response.model, provider: provider.id }],
  };
}

async function runDuel(messages, requestedModel = "random") {
  const providers = configuredProviders();
  let picked;
  if (requestedModel !== "random") {
    const first = chooseProvider(providers, requestedModel);
    if (!first) return missingProviderResponse(requestedModel);
    const others = providers.filter((provider) => provider.id !== first.id);
    picked = [first, ...pickMany(others, Math.min(1, others.length))];
  } else {
    picked = pickMany(providers, Math.min(2, providers.length));
  }
  const responses = await Promise.all(
    picked.map(async (provider) => {
      try {
        return await callProvider(provider, messages);
      } catch (error) {
        logger.warn("Provider failed in duel", { provider: provider.id, message: error.message });
        return { model: `${provider.label} failed`, content: `This provider failed: ${error.message}` };
      }
    })
  );

  if (responses.length === 1) {
    return {
      answer: responses[0].content,
      modelUsed: responses[0].model,
      candidates: responses.map((response) => ({ model: response.model })),
    };
  }

  const answer = [
    "🏁 Arena Duel Result",
    "",
    `A — ${responses[0].model}`,
    responses[0].content,
    "",
    `B — ${responses[1].model}`,
    responses[1].content,
    "",
    "Tip: compare clarity, accuracy, and actionability before using the result."
  ].join("\n");

  return {
    answer,
    modelUsed: responses.map((response) => response.model).join(" vs "),
    candidates: responses.map((response, index) => ({ slot: index === 0 ? "A" : "B", model: response.model })),
  };
}

function chooseProvider(providers, requestedModel) {
  if (!requestedModel || requestedModel === "random") return pickRandom(providers);
  return providers.find((provider) => provider.id === requestedModel) || null;
}

function missingProviderResponse(requestedModel) {
  const labels = {
    gemini: "Gemini",
    openai: "OpenAI",
    anthropic: "Claude",
    mistral: "Mistral",
  };
  const label = labels[requestedModel] || requestedModel;
  return {
    answer: [
      `${label} is selected, but that provider is not configured on the backend yet.`,
      "",
      "Add the matching Firebase Functions environment variable, then deploy functions again:",
      "GEMINI_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY, or MISTRAL_API_KEY.",
      "",
      "You can switch back to Random to use any provider that is already configured."
    ].join("\n"),
    modelUsed: "Setup Assistant",
    candidates: [],
  };
}

function configuredProviders() {
  const providers = [];
  if (process.env.GEMINI_API_KEY) {
    providers.push({ id: "gemini", label: "Gemini", model: "gemini-1.5-flash", key: process.env.GEMINI_API_KEY });
  }
  if (process.env.OPENAI_API_KEY) {
    providers.push({ id: "openai", label: "OpenAI", model: "gpt-4o-mini", key: process.env.OPENAI_API_KEY });
  }
  if (process.env.ANTHROPIC_API_KEY) {
    providers.push({ id: "anthropic", label: "Claude", model: "claude-3-5-haiku-latest", key: process.env.ANTHROPIC_API_KEY });
  }
  if (process.env.MISTRAL_API_KEY) {
    providers.push({ id: "mistral", label: "Mistral", model: "mistral-small-latest", key: process.env.MISTRAL_API_KEY });
  }

  if (!providers.length) {
    providers.push({ id: "setup", label: "Local Setup Assistant", model: "setup-mode" });
  }
  return providers;
}

async function callProvider(provider, messages) {
  switch (provider.id) {
    case "gemini":
      return callGemini(provider, messages);
    case "openai":
      return callOpenAi(provider, messages);
    case "anthropic":
      return callAnthropic(provider, messages);
    case "mistral":
      return callMistral(provider, messages);
    default:
      return callSetupAssistant(messages);
  }
}

function systemPrompt() {
  return "You are Arena AI inside a mobile app. Help the user complete complex tasks with clear, safe, practical, step-by-step answers. If information is uncertain, say so. Never mention hidden API keys or backend internals.";
}

async function callGemini(provider, messages) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${provider.model}:generateContent?key=${encodeURIComponent(provider.key)}`;
  const contents = messages.map((message) => ({
    role: message.role === "assistant" ? "model" : "user",
    parts: [{ text: message.content }],
  }));
  const response = await postJson(url, {
    systemInstruction: { parts: [{ text: systemPrompt() }] },
    contents,
    generationConfig: { temperature: 0.7, maxOutputTokens: 2048 },
  });
  const text = response.candidates?.[0]?.content?.parts?.map((part) => part.text || "").join("").trim();
  return { model: `Gemini ${provider.model}`, content: text || "Gemini returned an empty response." };
}

async function callOpenAi(provider, messages) {
  const response = await postJson("https://api.openai.com/v1/chat/completions", {
    model: provider.model,
    temperature: 0.7,
    messages: [
      { role: "system", content: systemPrompt() },
      ...messages.map((message) => ({ role: message.role, content: message.content })),
    ],
  }, { Authorization: `Bearer ${provider.key}` });
  const text = response.choices?.[0]?.message?.content?.trim();
  return { model: `OpenAI ${provider.model}`, content: text || "OpenAI returned an empty response." };
}

async function callAnthropic(provider, messages) {
  const response = await postJson("https://api.anthropic.com/v1/messages", {
    model: provider.model,
    max_tokens: 2048,
    temperature: 0.7,
    system: systemPrompt(),
    messages: messages.map((message) => ({ role: message.role === "assistant" ? "assistant" : "user", content: message.content })),
  }, {
    "x-api-key": provider.key,
    "anthropic-version": "2023-06-01",
  });
  const text = response.content?.map((part) => part.text || "").join("\n").trim();
  return { model: `Claude ${provider.model}`, content: text || "Claude returned an empty response." };
}

async function callMistral(provider, messages) {
  const response = await postJson("https://api.mistral.ai/v1/chat/completions", {
    model: provider.model,
    temperature: 0.7,
    messages: [
      { role: "system", content: systemPrompt() },
      ...messages.map((message) => ({ role: message.role, content: message.content })),
    ],
  }, { Authorization: `Bearer ${provider.key}` });
  const text = response.choices?.[0]?.message?.content?.trim();
  return { model: `Mistral ${provider.model}`, content: text || "Mistral returned an empty response." };
}

function callSetupAssistant(messages) {
  const lastPrompt = messages[messages.length - 1]?.content || "your prompt";
  return {
    model: "Local Setup Assistant",
    content: [
      "Arena AI backend is running, but no AI provider API key is configured yet.",
      "",
      "To enable random AI routing, set one or more environment variables in Firebase Functions:",
      "GEMINI_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY, or MISTRAL_API_KEY.",
      "",
      `Your latest prompt was saved for history/backup: ${lastPrompt.slice(0, 500)}`
    ].join("\n"),
  };
}

async function postJson(url, body, headers = {}) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...headers,
    },
    body: JSON.stringify(body),
  });

  const text = await response.text();
  let json;
  try {
    json = text ? JSON.parse(text) : {};
  } catch (error) {
    throw new Error(`Provider returned non-JSON response: ${text.slice(0, 300)}`);
  }

  if (!response.ok) {
    const providerMessage = json.error?.message || json.message || text;
    throw new Error(providerMessage.slice(0, 500));
  }
  return json;
}

function makeTitle(prompt) {
  return prompt.replace(/\s+/g, " ").slice(0, 64) || "New chat";
}

function pickRandom(items) {
  return items[randomInt(items.length)];
}

function pickMany(items, count) {
  const copy = [...items];
  const picked = [];
  while (copy.length && picked.length < count) {
    picked.push(copy.splice(randomInt(copy.length), 1)[0]);
  }
  return picked;
}

function randomInt(maxExclusive) {
  return crypto.randomInt(0, maxExclusive);
}
