package com.trademesh;

import com.trademesh.zerodha.auth.ZerodhaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ZerodhaConfig.class)
public class TradeMeshApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeMeshApplication.class, args);
    }
}
