package com.ai.receptionist.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.ai.receptionist.websocket.MediaStreamHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MediaStreamHandler mediaStreamHandler;

    @Value("${websocket.allowed-origins:}")
    private String allowedOrigins;

    public WebSocketConfig(MediaStreamHandler mediaStreamHandler) {
        this.mediaStreamHandler = mediaStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Twilio Media Streams connect server-to-server (not from a browser),
        // so browser CORS restrictions do not affect them.
        // Default: no browser origins allowed. Override for local dev if needed.
        String[] origins = (allowedOrigins != null && !allowedOrigins.isBlank())
                ? allowedOrigins.split(",")
                : new String[0];

        registry.addHandler(mediaStreamHandler, "/media-stream")
                .setAllowedOrigins(origins);
    }
}

