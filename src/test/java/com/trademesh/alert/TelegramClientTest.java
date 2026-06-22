package com.trademesh.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for TelegramClient.
 */
@ExtendWith(MockitoExtension.class)
class TelegramClientTest {

    @Mock
    private WebClient.Builder webClientBuilder;

    private TelegramClient telegramClient;
    private TelegramProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TelegramProperties();
        properties.setBotToken("test-bot-token");
        properties.setChatId("test-chat-id");
        telegramClient = new TelegramClient(webClientBuilder, properties);
    }

    @Test
    void shouldNotBuildClientWhenBotTokenIsNull() {
        properties.setBotToken(null);

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenChatIdIsNull() {
        properties.setChatId(null);

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenBotTokenIsBlank() {
        properties.setBotToken("   ");

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenChatIdIsBlank() {
        properties.setChatId("   ");

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenBothTokenAndChatIdAreMissing() {
        properties.setBotToken(null);
        properties.setChatId(null);

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenBotTokenIsEmpty() {
        properties.setBotToken("");

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenChatIdIsEmpty() {
        properties.setChatId("");

        StepVerifier.create(telegramClient.sendMessage("Test message"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }


    @Test
    void shouldNotBuildClientWhenBotTokenIsEmptyAndChatIdIsNull() {
        properties.setBotToken("");
        properties.setChatId(null);

        StepVerifier.create(telegramClient.sendMessage("Test"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenBotTokenIsNullAndChatIdIsEmpty() {
        properties.setBotToken(null);
        properties.setChatId("");

        StepVerifier.create(telegramClient.sendMessage("Test"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldNotBuildClientWhenBotTokenIsWhitespaceOnlyAndChatIdIsWhitespaceOnly() {
        properties.setBotToken("   ");
        properties.setChatId("\t\n");

        StepVerifier.create(telegramClient.sendMessage("Test"))
                .verifyComplete();

        verify(webClientBuilder, never()).build();
    }

    @Test
    void shouldReturnCompletedMonoWhenNotConfigured() {
        properties.setBotToken(null);

        Mono<Void> result = telegramClient.sendMessage("Test");

        StepVerifier.create(result)
                .expectComplete()
                .verify();
    }
}




