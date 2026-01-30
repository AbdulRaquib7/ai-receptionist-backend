package com.ai.receptionist.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.ai.receptionist.websocket.MediaStreamHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MediaStreamHandler mediaStreamHandler;

    public WebSocketConfig(MediaStreamHandler mediaStreamHandler) {
        this.mediaStreamHandler = mediaStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(mediaStreamHandler, "/media-stream")
                .setAllowedOrigins("*");
    }
}

