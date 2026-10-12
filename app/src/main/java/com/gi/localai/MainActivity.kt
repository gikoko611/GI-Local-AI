package com.gi.localai

import android.net.Uri
import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF080D0B)
private val Panel = Color(0xFF111A16)
private val Neon = Color(0xFF65F0A5)
private val Ink = Color(0xFFE5F5EB)

class MainActivity : ComponentActivity() {
    private lateinit var store: ChatStore
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ChatStore(this)
        setContent { GIApp(store) }
    }
}

@Composable
private fun GIApp(store: ChatStore) {
    var tab by remember { mutableStateOf("CHAT") }
    var input by remember { mutableStateOf("") }
    var modelPath by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("NO MODEL LOADED") }
    var busy by remember { mutableStateOf(false) }
    var systemPrompt by remember { mutableStateOf(store.prompts().firstOrNull()?.second ?: "You are G.I, a helpful local AI.") }
    var promptName by remember { mutableStateOf("My prompt") }
    var maxTokens by remember { mutableIntStateOf(128) }
    var lines by remember { mutableStateOf(store.all()) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            modelPath = uri.toString()
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Neon, background = Bg, surface = Panel)) {
        Column(Modifier.fillMaxSize().background(Bg).padding(14.dp)) {
            Text("G.I // LOCAL AI", color = Neon, fontSize = 24.sp)
            Text("OFFLINE INFERENCE TERMINAL", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("CHAT","MODELS","HISTORY","PROMPTS","METRICS").forEach { name ->
                    TextButton(onClick = { tab = name }) {
                        Text(name, color = if (tab == name) Neon else Ink, fontSize = 10.sp)
                    }
                }
            }
            HorizontalDivider(color = Neon.copy(alpha = .4f))
            Spacer(Modifier.height(10.dp))
            when (tab) {
                "CHAT" -> {
                    Text("ENGINE: $modelStatus", color = if (modelStatus == "READY") Neon else Color.Yellow, fontSize = 11.sp)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(lines) { line ->
                            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(10.dp)) {
                                Text(line.role.uppercase(), color = Neon, fontSize = 10.sp)
                                Text(line.body, color = Ink)
                            }
                        }
                    }
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Message G.I") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && modelStatus == "READY" && input.isNotBlank(), onClick = {
                            val prompt = "$systemPrompt\n\nUser: $input\nAssistant:"
                            val userText = input
                            input = ""; busy = true
                            store.add("user", userText); lines = store.all()
                            scope.launch {
                                val answer = withContext(Dispatchers.IO) {
                                    try { InferenceBridge.nativeGenerate(prompt, maxTokens) }
                                    catch (e: Exception) { "ERROR: ${e.message}" }
                                }
                                store.add("assistant", answer); lines = store.all()
                                busy = false
                            }
                        }) { Text(if (busy) "RUNNING..." else "GENERATE") }
                        OutlinedButton(onClick = { input = "" }) { Text("CLEAR") }
                    }
                }
                "MODELS" -> {
                    Text("GGUF MODEL MANAGER", color = Neon)
                    Text("Selected: ${modelPath.ifBlank { "none" }}", color = Ink, fontSize = 11.sp)
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("IMPORT .GGUF") }
                    Button(enabled = modelPath.isNotBlank() && !busy, onClick = {
                        busy = true; modelStatus = "LOADING..."
                        scope.launch {
                            val status = withContext(Dispatchers.IO) {
                                try {
                                    val uri = Uri.parse(modelPath)
                                    val target = java.io.File(context.filesDir, "active-model.gguf")
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        target.outputStream().use { output -> input.copyTo(output) }
                                    } ?: throw IllegalStateException("Cannot read selected file")
                                    InferenceBridge.nativeLoad(target.absolutePath)
                                } catch (e: Exception) { "ERROR: ${e.message}" }
                            }
                            result = status
                            modelStatus = if (status == "OK") "READY" else status
                            busy = false
                        }
                    }) { Text("LOAD MODEL") }
                    OutlinedButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { try { InferenceBridge.nativeUnload() } catch (_: Exception) {} } }
                        modelStatus = "NO MODEL LOADED"
                    }) { Text("UNLOAD") }
                    Text(result, color = Neon)
                    Text("ARMv7 baseline: CPU-only, context 1024, max output 256 tokens.", color = Color.Gray, fontSize = 12.sp)
                }
                "HISTORY" -> {
                    Text("LOCAL CHAT HISTORY", color = Neon)
                    Button(onClick = { store.clear(); lines = store.all() }) { Text("CLEAR HISTORY") }
                    LazyColumn { items(lines) { Text("${it.role}: ${it.body}", color = Ink, modifier = Modifier.padding(6.dp)) } }
                }
                "PROMPTS" -> {
                    Text("SYSTEM PROMPT PRESETS", color = Neon)
                    OutlinedTextField(promptName, { promptName = it }, Modifier.fillMaxWidth(), label = { Text("Preset name") })
                    OutlinedTextField(systemPrompt, { systemPrompt = it }, Modifier.fillMaxWidth().weight(1f), label = { Text("System prompt") })
                    Button(onClick = { store.savePrompt(promptName, systemPrompt); result = "Prompt saved locally." }) { Text("SAVE PRESET") }
                    Text(result, color = Neon)
                    store.prompts().forEach { (name, prompt) ->
                        TextButton(onClick = { promptName = name; systemPrompt = prompt }) { Text(name, color = Neon) }
                    }
                }
                "METRICS" -> {
                    Text("DEVICE / INFERENCE METRICS", color = Neon)
                    Text("Native engine: llama.cpp", color = Ink)
                    Text("Target ABI: armeabi-v7a", color = Ink)
                    Text("Max output tokens: $maxTokens", color = Ink)
                    Slider(value = maxTokens.toFloat(), onValueChange = { maxTokens = it.toInt() }, valueRange = 16f..256f)
                    Text("Java heap used: ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024)} MB", color = Ink)
                    Text("Native RSS / tokens per second: not yet instrumented in this MVP.", color = Color.Gray)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("LOCAL DATA ONLY • MODEL FILES STAY ON DEVICE", color = Color.Gray, fontSize = 10.sp)
        }
    }
}
