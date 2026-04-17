package com.agreemint.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Max inbound STOMP message size. Default is 64 KB, which is too small for
     * collab ops that embed base64 image data URLs (commonly 100 KB – 5 MB).
     * When exceeded, Spring silently drops the frame or closes the session with
     * CloseStatus 1009, so the collab op never reaches the server.
     *
     * 16 MB is enough headroom for typical image inserts. For anything larger,
     * images should be uploaded via a REST endpoint and the element should
     * carry only the URL — but that's a separate architectural change.
     */
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    public WebSocketConfig(WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Inbound (client → server) STOMP message cap.
        registration.setMessageSizeLimit(MAX_MESSAGE_SIZE);
        // Outbound (server → client) buffered bytes when a slow client can't keep up.
        // Generous so a broadcast of a large image-bearing op doesn't get dropped.
        registration.setSendBufferSizeLimit(MAX_MESSAGE_SIZE);
        // Give the server up to 20 s to flush a large message to a slow client.
        registration.setSendTimeLimit(20_000);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
