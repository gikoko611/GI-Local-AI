package com.gi.localai

import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private val Bg = Color(0xFF100D0A)
private val Panel = Color(0xFF1D1711)
private val Panel2 = Color(0xFF282017)
private val Gold = Color(0xFFE0B96B)
private val GoldSoft = Color(0xFFB99550)
private val Ink = Color(0xFFF4EBDD)
private val Muted = Color(0xFFB4A58D)
private val ErrorRed = Color(0xFFFF8B78)

class MainActivity : ComponentActivity() {
    private lateinit var store: ChatStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ChatStore(this)
        setContent { GIApp(store) }
    }
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.2f GB", kb / 1024.0 / 1024.0)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Composable
private fun GIApp(store: ChatStore) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("gi_local_settings", 0) }

    var tab by remember { mutableStateOf("CHAT") }
    var input by remember { mutableStateOf("") }
    var modelUri by remember { mutableStateOf(prefs.getString("model_uri", "") ?: "") }
    var modelName by remember { mutableStateOf(prefs.getString("model_name", "") ?: "") }
    var modelBytes by remember { mutableLongStateOf(prefs.getLong("model_bytes", 0L)) }
    var modelHash by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("NO MODEL LOADED") }
    var busy by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf(store.prompts().firstOrNull()?.second ?: "You are G.I, a helpful local AI. Reply in the user's language.") }
    var promptName by remember { mutableStateOf("My prompt") }
    var maxTokens by remember { mutableIntStateOf(prefs.getInt("max_tokens", 128)) }
    var contextSize by remember { mutableIntStateOf(prefs.getInt("context_size", 1024)) }
    var threads by remember { mutableIntStateOf(prefs.getInt("threads", 2)) }
    var temperature by remember { mutableFloatStateOf(prefs.getFloat("temperature", 0.7f)) }
    var topP by remember { mutableFloatStateOf(prefs.getFloat("top_p", 0.9f)) }
    var lowRam by remember { mutableStateOf(prefs.getBoolean("low_ram", true)) }
    var historyQuery by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(store.all()) }
    var result by remember { mutableStateOf("") }
    var lastDurationMs by remember { mutableLongStateOf(0L) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            val doc = DocumentFile.fromSingleUri(context, uri)
            modelUri = uri.toString()
            modelName = doc?.name ?: "Selected GGUF model"
            modelBytes = doc?.length() ?: 0L
            prefs.edit().putString("model_uri", modelUri).putString("model_name", modelName).putLong("model_bytes", modelBytes).apply()
            result = "Model selected. Tap LOAD MODEL to import it."
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri != null) {
            try {
                val markdown = buildString {
                    append("# G.I Local AI conversation\n\n")
                    lines.forEach { append("## ").append(it.role.uppercase()).append("\n\n").append(it.body).append("\n\n") }
                }
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(markdown) }
                result = "Conversation exported."
            } catch (e: Exception) { result = "Export error: ${e.message}" }
        }
    }

    fun persistSettings() {
        prefs.edit()
            .putInt("max_tokens", maxTokens)
            .putInt("context_size", contextSize)
            .putInt("threads", threads)
            .putFloat("temperature", temperature)
            .putFloat("top_p", topP)
            .putBoolean("low_ram", lowRam)
            .apply()
        result = "Settings saved on this device."
    }

    fun loadModel() {
        if (modelUri.isBlank() || busy) return
        busy = true
        modelStatus = "IMPORTING MODEL..."
        result = "Copying model to private app storage..."
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(modelUri)
                    val source = context.contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
                    val modelDir = File(context.filesDir, "models")
                    if (!modelDir.exists()) modelDir.mkdirs()
                    val safeName = modelName.substringBeforeLast('.', modelName).replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
                    val target = File(modelDir, (safeName.ifBlank { "local-model" }) + ".gguf")
                    val available = StatFs(modelDir.absolutePath).availableBytes
                    if (modelBytes > 0 && available < modelBytes + 48L * 1024L * 1024L) error("Not enough free storage for model copy")
                    source.use { input -> target.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) } }
                    if (target.length() == 0L) error("Selected file is empty")
                    val digest = sha256(target)
                    val native = InferenceBridge.nativeLoad(target.absolutePath, contextSize, threads)
                    if (native != "OK") error(native)
                    digest
                } catch (e: Exception) { "ERROR: ${e.message}" }
            }
            if (status.startsWith("ERROR:")) {
                modelStatus = status
                result = status
            } else {
                modelHash = status
                modelStatus = "READY"
                result = "Model loaded • SHA-256 ${status.take(16)}…"
            }
            busy = false
        }
    }

    fun sendMessage() {
        val userText = input.trim()
        if (userText.isEmpty() || busy || modelStatus != "READY") return
        val previous = lines.takeLast(8).joinToString("\n") { "${it.role}: ${it.body}" }
        val prompt = "$systemPrompt\n\n$previous\nuser: $userText\nassistant:"
        input = ""
        store.add("user", userText)
        lines = store.all()
        busy = true
        val started = System.currentTimeMillis()
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                try { InferenceBridge.nativeGenerate(prompt, maxTokens, temperature, topP) }
                catch (e: Exception) { "ERROR: ${e.message}" }
            }
            lastDurationMs = System.currentTimeMillis() - started
            store.add("assistant", answer)
            lines = store.all()
            busy = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, onPrimary = Bg, background = Bg, onBackground = Ink,
        surface = Panel, onSurface = Ink, secondary = GoldSoft
    )) {
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("G.I LOCAL AI", color = Gold, fontSize = 23.sp)
                    Text("PRIVATE • OFFLINE • GGUF ENGINE", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Surface(color = Panel2, shape = RoundedCornerShape(50)) {
                    Text(if (modelStatus == "READY") "● READY" else "○ LOCAL", color = if (modelStatus == "READY") Gold else Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                items(listOf("CHAT", "MODELS", "HISTORY", "PROMPTS", "METRICS", "SETTINGS", "ABOUT")) { name ->
                    TextButton(onClick = { tab = name }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(name, color = if (tab == name) Gold else Muted, fontSize = 10.sp)
                    }
                }
            }
            HorizontalDivider(color = Gold.copy(alpha = .35f))
            Spacer(Modifier.height(10.dp))

            when (tab) {
                "CHAT" -> {
                    Text("ENGINE  /  $modelStatus", color = if (modelStatus == "READY") Gold else Muted, fontSize = 10.sp)
                    if (lines.isEmpty()) {
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("G.I", color = Gold, fontSize = 48.sp)
                            Text("Your private AI runs on this phone.", color = Ink)
                            Text("Import a small GGUF model to begin.", color = Muted, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(lines, key = { it.id }) { line ->
                                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text(line.role.uppercase(), color = Gold, fontSize = 9.sp)
                                        TextButton(onClick = { clipboard.setText(AnnotatedString(line.body)) }, contentPadding = PaddingValues(2.dp)) { Text("COPY", color = Muted, fontSize = 9.sp) }
                                    }
                                    SelectionContainer { Text(line.body, color = Ink, fontSize = 14.sp) }
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message G.I") }, maxLines = 4, enabled = !busy)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && modelStatus == "READY" && input.isNotBlank(), onClick = { sendMessage() }, modifier = Modifier.weight(1f)) { Text(if (busy) "WORKING..." else "GENERATE") }
                        OutlinedButton(enabled = busy, onClick = { try { InferenceBridge.nativeStop() } catch (_: Exception) {} }) { Text("STOP") }
                        OutlinedButton(onClick = { input = "" }) { Text("CLEAR") }
                    }
                    if (busy) Text("Inference running on device CPU…", color = GoldSoft, fontSize = 10.sp)
                }

                "MODELS" -> {
                    Text("GGUF MODEL LIBRARY", color = Gold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(modelName.ifBlank { "No model selected" }, color = Ink)
                            Text(if (modelBytes > 0) humanBytes(modelBytes) else "Choose a .gguf file from device storage", color = Muted, fontSize = 11.sp)
                            if (modelHash.isNotBlank()) Text("SHA-256: ${modelHash.take(24)}…", color = GoldSoft, fontSize = 9.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("IMPORT GGUF FROM STORAGE") }
                    Button(enabled = modelUri.isNotBlank() && !busy, onClick = { loadModel() }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "PLEASE WAIT..." else "LOAD MODEL") }
                    OutlinedButton(enabled = modelStatus == "READY" || busy, onClick = {
                        try { InferenceBridge.nativeStop(); InferenceBridge.nativeUnload() } catch (_: Exception) {}
                        modelStatus = "NO MODEL LOADED"
                        result = "Model unloaded."
                    }, modifier = Modifier.fillMaxWidth()) { Text("UNLOAD MODEL") }
                    Text(result, color = if (result.startsWith("ERROR")) ErrorRed else GoldSoft, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Device preset: ARMv7 CPU-only • Context ${contextSize} • Threads ${threads}", color = Muted, fontSize = 10.sp)
                    Text("Recommended starting point: 0.3B–0.6B GGUF Q4. Keep models outside the APK.", color = Muted, fontSize = 10.sp)
                }

                "HISTORY" -> {
                    Text("LOCAL CONVERSATION HISTORY", color = Gold, fontSize = 15.sp)
                    OutlinedTextField(value = historyQuery, onValueChange = { historyQuery = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search messages") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { exporter.launch("gi-conversation.md") }, modifier = Modifier.weight(1f)) { Text("EXPORT MD") }
                        OutlinedButton(onClick = { store.clear(); lines = store.all(); result = "History cleared." }, modifier = Modifier.weight(1f)) { Text("CLEAR ALL") }
                    }
                    val filtered = lines.filter { historyQuery.isBlank() || it.body.contains(historyQuery, ignoreCase = true) || it.role.contains(historyQuery, ignoreCase = true) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(filtered, key = { it.id }) { line ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(line.role.uppercase(), color = Gold, fontSize = 9.sp)
                                    TextButton(onClick = { clipboard.setText(AnnotatedString(line.body)) }) { Text("COPY", color = Muted, fontSize = 9.sp) }
                                }
                                SelectionContainer { Text(line.body, color = Ink, fontSize = 13.sp) }
                                HorizontalDivider(color = Panel2)
                            }
                        }
                    }
                }

                "PROMPTS" -> {
                    Text("SYSTEM PROMPT STUDIO", color = Gold, fontSize = 15.sp)
                    OutlinedTextField(value = promptName, onValueChange = { promptName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Preset name") }, singleLine = true)
                    OutlinedTextField(value = systemPrompt, onValueChange = { systemPrompt = it }, modifier = Modifier.weight(1f).fillMaxWidth(), label = { Text("System instruction") })
                    Button(onClick = { store.savePrompt(promptName.ifBlank { "Custom prompt" }, systemPrompt); result = "Prompt saved locally." }, modifier = Modifier.fillMaxWidth()) { Text("SAVE PROMPT PRESET") }
                    LazyColumn {
                        items(store.prompts()) { pair ->
                            TextButton(onClick = { promptName = pair.first; systemPrompt = pair.second }, modifier = Modifier.fillMaxWidth()) {
                                Text(pair.first, color = Gold)
                            }
                        }
                    }
                    Text(result, color = GoldSoft, fontSize = 10.sp)
                }

                "METRICS" -> {
                    Text("DEVICE & ENGINE METRICS", color = Gold, fontSize = 15.sp)
                    val runtime = Runtime.getRuntime()
                    val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                    val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                    val storage = StatFs(context.filesDir.absolutePath)
                    val rows = listOf(
                        "Target ABI" to "armeabi-v7a",
                        "Engine" to "llama.cpp • CPU",
                        "Model" to (modelName.ifBlank { "Not selected" }),
                        "Model size" to humanBytes(modelBytes),
                        "Java heap used" to "$heapUsed MB",
                        "Native heap allocated" to "$nativeHeap MB",
                        "App storage free" to humanBytes(storage.availableBytes),
                        "Context window" to "$contextSize tokens",
                        "CPU threads" to "$threads",
                        "Last generation time" to "$lastDurationMs ms"
                    )
                    rows.forEach { (key, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(key, color = Muted, fontSize = 12.sp)
                            Text(value, color = Ink, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Panel2)
                    }
                }

                "SETTINGS" -> {
                    Text("LOCAL INFERENCE SETTINGS", color = Gold, fontSize = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Low-memory preset", color = Ink)
                            Text("512 context • 1 thread • 96 output tokens", color = Muted, fontSize = 10.sp)
                        }
                        Switch(checked = lowRam, onCheckedChange = {
                            lowRam = it
                            if (it) { contextSize = 512; threads = 1; maxTokens = 96 }
                        })
                    }
                    Text("Context size: $contextSize", color = Ink, fontSize = 12.sp)
                    Slider(value = contextSize.toFloat(), onValueChange = { contextSize = it.toInt() }, valueRange = 512f..2048f, steps = 2)
                    Text("CPU threads: $threads", color = Ink, fontSize = 12.sp)
                    Slider(value = threads.toFloat(), onValueChange = { threads = it.toInt() }, valueRange = 1f..4f, steps = 2)
                    Text("Maximum output: $maxTokens tokens", color = Ink, fontSize = 12.sp)
                    Slider(value = maxTokens.toFloat(), onValueChange = { maxTokens = it.toInt() }, valueRange = 32f..256f, steps = 6)
                    Text("Temperature: ${"%.2f".format(Locale.US, temperature)}", color = Ink, fontSize = 12.sp)
                    Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0.1f..1.2f)
                    Text("Top-P: ${"%.2f".format(Locale.US, topP)}", color = Ink, fontSize = 12.sp)
                    Slider(value = topP, onValueChange = { topP = it }, valueRange = 0.1f..1.0f)
                    Button(onClick = { persistSettings() }, modifier = Modifier.fillMaxWidth()) { Text("SAVE SETTINGS") }
                    Text("Changes to context / threads apply the next time you load the model.", color = Muted, fontSize = 10.sp)
                    Text(result, color = GoldSoft, fontSize = 10.sp)
                }

                "ABOUT" -> {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("G.I", color = Gold, fontSize = 54.sp)
                        Text("LOCAL AI", color = Ink, fontSize = 22.sp)
                        Text("POWERED BY I KO KO", color = GoldSoft, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(18.dp))
                        Text("Private on-device GGUF inference", color = Ink)
                        Text("Version 0.2.0 • ARMv7 build", color = Muted, fontSize = 11.sp)
                        Text("No account • No cloud • No telemetry", color = Muted, fontSize = 11.sp)
                        Text("Model files remain in local app storage.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("LOCAL DATA ONLY  •  MODEL FILES STAY ON DEVICE", color = Muted, fontSize = 9.sp, letterSpacing = .5.sp)
        }
    }
}
