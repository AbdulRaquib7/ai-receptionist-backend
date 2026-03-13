package com.ai.receptionist.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides a dedicated thread pool for the voice pipeline (STT → LLM → TTS).
 *
 * The default ForkJoinPool.commonPool() has only CPU-core-count threads and is designed
 * for CPU-bound work. Our pipeline is I/O-bound (HTTP calls to OpenAI, ElevenLabs, Twilio),
 * so we need a much larger pool to avoid thread starvation under concurrent calls.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "voicePipelineExecutor")
    public ExecutorService voicePipelineExecutor() {
        int poolSize = Runtime.getRuntime().availableProcessors() * 10;
        return Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "voice-pipeline-" + COUNTER.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    private static final AtomicInteger COUNTER = new AtomicInteger(0);
}
