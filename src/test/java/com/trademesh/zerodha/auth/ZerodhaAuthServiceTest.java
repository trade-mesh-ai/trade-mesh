package com.trademesh.zerodha.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZerodhaAuthServiceTest {

    @Test
    void sessionResponseAcceptsAdditionalKiteFields() throws Exception {
        String responseBody = """
                {
                  "status": "success",
                  "data": {
                    "access_token": "access-token",
                    "user_id": "AB1234",
                    "user_type": "individual"
                  }
                }
                """;

        ZerodhaAuthService.KiteSessionResponse response = new ObjectMapper()
                .readValue(responseBody, ZerodhaAuthService.KiteSessionResponse.class);

        assertEquals("access-token", response.getData().getAccessToken());
        assertEquals("AB1234", response.getData().getUserId());
    }
}
