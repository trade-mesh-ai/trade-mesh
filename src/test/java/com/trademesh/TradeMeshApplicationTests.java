package com.trademesh;

import com.trademesh.config.TradeMeshTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TradeMeshTestConfiguration.class)
class TradeMeshApplicationTests {

    @Test
    void contextLoads() {
    }
}
