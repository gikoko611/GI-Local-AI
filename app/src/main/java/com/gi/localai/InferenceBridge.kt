package com.gi.localai

object InferenceBridge {
    init { System.loadLibrary("gi-local-ai") }
    external fun nativeLoad(path: String): String
    external fun nativeGenerate(prompt: String, maxTokens: Int): String
    external fun nativeUnload()
}
