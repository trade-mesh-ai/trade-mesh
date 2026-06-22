package com.trademesh.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for TelegramProperties.
 */
class TelegramPropertiesTest {

    @Test
    void shouldSetBotToken() {
        TelegramProperties properties = new TelegramProperties();
        properties.setBotToken("my-bot-token");

        assertEquals("my-bot-token", properties.getBotToken());
    }

    @Test
    void shouldSetChatId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setChatId("my-chat-id");

        assertEquals("my-chat-id", properties.getChatId());
    }

    @Test
    void shouldHandleNullBotToken() {
        TelegramProperties properties = new TelegramProperties();
        properties.setBotToken(null);

        assertNull(properties.getBotToken());
    }

    @Test
    void shouldHandleNullChatId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setChatId(null);

        assertNull(properties.getChatId());
    }

    @Test
    void shouldHandleEmptyStringBotToken() {
        TelegramProperties properties = new TelegramProperties();
        properties.setBotToken("");

        assertEquals("", properties.getBotToken());
    }

    @Test
    void shouldHandleEmptyStringChatId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setChatId("");

        assertEquals("", properties.getChatId());
    }

    @Test
    void shouldHandleWhitespaceBotToken() {
        TelegramProperties properties = new TelegramProperties();
        properties.setBotToken("   ");

        assertEquals("   ", properties.getBotToken());
    }

    @Test
    void shouldHandleWhitespaceChatId() {
        TelegramProperties properties = new TelegramProperties();
        properties.setChatId("   ");

        assertEquals("   ", properties.getChatId());
    }

    @Test
    void shouldSetMultipleBotTokens() {
        TelegramProperties properties = new TelegramProperties();
        properties.setBotToken("first-token");
        assertEquals("first-token", properties.getBotToken());

        properties.setBotToken("second-token");
        assertEquals("second-token", properties.getBotToken());
    }

    @Test
    void shouldSetMultipleChatIds() {
        TelegramProperties properties = new TelegramProperties();
        properties.setChatId("first-chat");
        assertEquals("first-chat", properties.getChatId());

        properties.setChatId("second-chat");
        assertEquals("second-chat", properties.getChatId());
    }

    @Test
    void shouldInitializeWithNullValues() {
        TelegramProperties properties = new TelegramProperties();

        assertNull(properties.getBotToken());
        assertNull(properties.getChatId());
    }

    @Test
    void shouldAllowLongBotToken() {
        TelegramProperties properties = new TelegramProperties();
        String longToken = "a".repeat(1000);
        properties.setBotToken(longToken);

        assertEquals(longToken, properties.getBotToken());
    }

    @Test
    void shouldAllowLongChatId() {
        TelegramProperties properties = new TelegramProperties();
        String longId = "123456789".repeat(100);
        properties.setChatId(longId);

        assertEquals(longId, properties.getChatId());
    }
}

