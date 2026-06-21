package com.trademeshai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.trademeshai.zerodha.auth.ZerodhaConfig;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ZerodhaConfig.class)
public class TradeMeshAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeMeshAiApplication.class, args);
    }
}
