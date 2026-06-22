package com.trademesh.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JdbcConfig.
 */
class JdbcConfigTest {

    private final JdbcConfig jdbcConfig = new JdbcConfig();

    @Test
    void shouldCreateOpenAPIBean() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertNotNull(openAPI);
    }

    @Test
    void shouldSetOpenAPITitle() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertEquals("TradeMesh API", openAPI.getInfo().getTitle());
    }

    @Test
    void shouldSetOpenAPIDescription() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertEquals("Algorithmic Trading Platform", openAPI.getInfo().getDescription());
    }

    @Test
    void shouldSetOpenAPIVersion() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertEquals("1.0.0", openAPI.getInfo().getVersion());
    }

    @Test
    void shouldCreateOpenAPIWithAllInfoFields() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertNotNull(openAPI.getInfo());
        assertNotNull(openAPI.getInfo().getTitle());
        assertNotNull(openAPI.getInfo().getDescription());
        assertNotNull(openAPI.getInfo().getVersion());
    }

    @Test
    void shouldCreateValidOpenAPIObject() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertNotNull(openAPI);
        assertTrue(openAPI.getInfo().getTitle().length() > 0);
        assertTrue(openAPI.getInfo().getDescription().length() > 0);
        assertTrue(openAPI.getInfo().getVersion().length() > 0);
    }

    @Test
    void shouldOpenAPITitleNotBeEmpty() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertNotEquals("", openAPI.getInfo().getTitle());
    }

    @Test
    void shouldOpenAPIDescriptionNotBeEmpty() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertNotEquals("", openAPI.getInfo().getDescription());
    }

    @Test
    void shouldOpenAPIVersionNotBeEmpty() {
        OpenAPI openAPI = jdbcConfig.tradeMeshOpenAPI();

        assertNotEquals("", openAPI.getInfo().getVersion());
    }
}


