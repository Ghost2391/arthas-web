package com.example.arthasweb.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.example.arthasweb.llm.ChatWebSocketHandler;
import com.example.arthasweb.tunnel.TunnelWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private TunnelWebSocketHandler tunnelWebSocketHandler;

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tunnelWebSocketHandler, "/ws").setAllowedOrigins("*");
        registry.addHandler(chatWebSocketHandler, "/ws/chat/{serverId}").setAllowedOrigins("*");
    }
}
