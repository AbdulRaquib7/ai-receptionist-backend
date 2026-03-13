package com.ai.receptionist.realtime;

/**
 * Callback interface for events received from the OpenAI Realtime API WebSocket.
 * Implemented by RealtimeSessionManager to process audio, function calls, and lifecycle events.
 */
public interface RealtimeEventHandler {

    /** Received a chunk of AI-generated speech audio (PCM16 24kHz). */
    void onAudioDelta(byte[] pcm16Bytes);

    /** AI requested a function call (tool use). */
    void onFunctionCall(String functionName, String argumentsJson, String callId);

    /** AI finished generating its response. */
    void onResponseDone();

    /** Caller started speaking (server VAD detected speech start). */
    void onSpeechStarted();

    /** Caller stopped speaking (server VAD detected speech end). */
    void onSpeechStopped();

    /** Realtime API reported an error. */
    void onError(String errorMessage);

    /** WebSocket session closed. */
    void onSessionClosed();
}
