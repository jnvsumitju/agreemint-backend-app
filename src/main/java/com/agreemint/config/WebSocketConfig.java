package com.agreemint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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

    /**
     * Heartbeat interval advertised to clients, in milliseconds.
     *
     * <p>These are load-bearing for impersonation, not just hygiene. Without a
     * task scheduler the simple broker leaves its heartbeat null, the CONNECTED
     * frame goes out as {@code heart-beat:0,0}, and stomp.js then never starts
     * its pinger — the client's own {@code heartbeatOutgoing} is inert unless
     * the server advertises an incoming interval.
     *
     * <p>{@link WebSocketAuthInterceptor} re-checks an impersonation session on
     * every inbound frame, so with no frames there is no re-check: a revoked
     * operator who was only <em>reading</em> — sitting in the editor watching a
     * customer's document stream in over their subscriptions — was never
     * disconnected. The heartbeat guarantees an inbound frame, which is what
     * turns "revoked" into "disconnected" for an idle session.
     */
    private static final long HEARTBEAT_MS = 10_000;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setTaskScheduler(heartbeatScheduler())
                .setHeartbeatValue(new long[] { HEARTBEAT_MS, HEARTBEAT_MS });
        registry.setApplicationDestinationPrefixes("/app");
    }

    /** Dedicated scheduler; the simple broker requires one to emit heartbeats at all. */
    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
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
