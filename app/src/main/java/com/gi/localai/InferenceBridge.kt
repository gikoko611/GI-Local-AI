package com.gi.localai

object InferenceBridge {
    init { System.loadLibrary("gi-local-ai") }
    external fun nativeLoad(path: String, contextSize: Int, threads: Int): String
    external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, topP: Float): String
    external fun nativeStop()
    external fun nativeUnload()
}
