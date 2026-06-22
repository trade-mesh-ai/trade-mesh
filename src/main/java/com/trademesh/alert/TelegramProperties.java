package com.trademesh.alert;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Telegram Bot integration.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telegram")
public class TelegramProperties {

    private String botToken;
    private String chatId;
}


