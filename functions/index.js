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

    await saveWorkspaceArtifacts(userRef, chatRef.id, prompt, answerPayload.answer, mode, answerPayload.modelUsed);

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
  return providers.find((provider) => provider.id === requestedModel) || {
    id: "local",
    label: `Arena Embedded ${requestedModel}`,
    model: `arena-embedded-${requestedModel}`,
    requestedModel,
  };
}


function missingProviderResponse(requestedModel) {
  const provider = {
    id: "local",
    label: `Arena Embedded ${requestedModel || "local"}`,
    model: `arena-embedded-${requestedModel || "local"}`,
  };
  const response = callEmbeddedArena(provider, [{ role: "user", content: "Provider fallback" }]);
  return { answer: response.content, modelUsed: response.model, candidates: [] };
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
    providers.push({ id: "local", label: "Arena Embedded", model: "arena-embedded-local" });
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
    case "local":
      return callEmbeddedArena(provider, messages);
    default:
      return callEmbeddedArena(provider, messages);
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

function callEmbeddedArena(provider, messages) {
  const lastPrompt = messages[messages.length - 1]?.content || "your prompt";
  return {
    model: provider.model || "arena-embedded-local",
    content: buildEmbeddedArenaAnswer(lastPrompt, provider.label || "Arena Embedded"),
  };
}

function buildEmbeddedArenaAnswer(prompt, label) {
  const lower = prompt.toLowerCase();
  if (["article", "blog", "essay", "write"].some((word) => lower.includes(word))) {
    return [
      `${label} response`,
      "",
      `# ${makeTitle(prompt)}`,
      "",
      "## Introduction",
      "Artificial intelligence, or AI, is one of the most important technologies in the modern world. It helps computers perform tasks that usually require human intelligence, such as understanding language, generating content, recognizing images, solving problems, and making predictions.",
      "",
      "## Benefits",
      "1. AI improves productivity by automating repetitive work.",
      "2. AI supports learning by explaining complex topics in simple language.",
      "3. AI helps businesses analyze data and improve customer support.",
      "4. AI assists creators, developers, writers, and researchers with faster drafting and planning.",
      "",
      "## Challenges",
      "AI can make mistakes, reflect bias, or miss important context. For important decisions, people should verify AI output and use it responsibly.",
      "",
      "## Future",
      "The future of AI will include stronger assistants, better automation, improved education tools, and more personalized software experiences.",
      "",
      "## Conclusion",
      "AI is most powerful when it works with humans. Used carefully, it can improve creativity, productivity, learning, and decision-making."
    ].join("\n");
  }
  if (["code", "app", "android", "kotlin", "firebase"].some((word) => lower.includes(word))) {
    return [
      `${label} response`,
      "",
      "## Implementation plan",
      `Goal: ${prompt}`,
      "",
      "1. Define the user flow and screens.",
      "2. Build the UI first.",
      "3. Add authentication and database storage.",
      "4. Route AI calls through a secure backend.",
      "5. Add history, workspace exports, and error fallback.",
      "6. Test login, sending, saving, and export behavior."
    ].join("\n");
  }
  return [
    `${label} response`,
    "",
    `I understand your request: ${prompt}`,
    "",
    "## Best answer",
    "Break the task into clear sections, produce a practical first result, then refine it for accuracy and usefulness.",
    "",
    "## Next steps",
    "1. Clarify the final output format.",
    "2. Draft the core answer.",
    "3. Add examples or details.",
    "4. Review and improve.",
    "5. Export the result if needed."
  ].join("\n");
}

async function saveWorkspaceArtifacts(userRef, chatId, prompt, answer, mode, modelUsed) {
  const artifacts = buildWorkspaceArtifacts(chatId, prompt, answer, mode, modelUsed);
  const batch = db.batch();
  const artifactsRef = userRef.collection("artifacts");
  artifacts.forEach((artifact) => {
    const ref = artifactsRef.doc();
    batch.set(ref, {
      ...artifact,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  });
  await batch.commit();
}

function buildWorkspaceArtifacts(chatId, prompt, answer, mode, modelUsed) {
  const title = makeTitle(prompt);
  const baseName = makeFileBaseName(prompt);
  const markdown = [
    `# ${title}`,
    "",
    `**Mode:** ${mode}`,
    `**Model:** ${modelUsed}`,
    "**Language:** Auto / multilingual",
    "",
    "## Prompt",
    prompt,
    "",
    "## Response",
    answer,
  ].join("\n");
  const text = [title, `Mode: ${mode}`, `Model: ${modelUsed}`, "", "Prompt:", prompt, "", "Response:", answer].join("\n");
  const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title><style>body{font-family:Arial,sans-serif;line-height:1.55;padding:32px;color:#111827}h1{color:#4c1d95}.meta{color:#475569;border-left:4px solid #8b5cf6;padding-left:12px}pre{white-space:pre-wrap;font-family:Arial,sans-serif}</style></head><body><h1>${escapeHtml(title)}</h1><p class="meta"><b>Mode:</b> ${escapeHtml(mode)}<br><b>Model:</b> ${escapeHtml(modelUsed)}<br><b>Format:</b> Print this page to save as PDF.</p><h2>Prompt</h2><p>${escapeHtml(prompt)}</p><h2>Response</h2><pre>${escapeHtml(answer)}</pre></body></html>`;
  const json = JSON.stringify({ title, mode, model: modelUsed, prompt, response: answer }, null, 2);
  return [
    { fileName: `${baseName}.md`, fileType: "Markdown", content: markdown, chatId },
    { fileName: `${baseName}.txt`, fileType: "Plain text", content: text, chatId },
    { fileName: `${baseName}.html`, fileType: "HTML / PDF-ready", content: html, chatId },
    { fileName: `${baseName}.json`, fileType: "JSON", content: json, chatId },
  ];
}

function makeFileBaseName(prompt) {
  return makeTitle(prompt).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40) || "arena-response";
}

function escapeHtml(value) {
  return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
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
