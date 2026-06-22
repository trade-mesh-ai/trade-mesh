package com.trademesh.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.swagger.v3.oas.models.OpenAPI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JdbcConfig sets up the DataSource for database connectivity and configures OpenAPI for API documentation.
 * It reads database connection properties from the application.yml file and initializes a HikariDataSource.    
 */
@Configuration
public class JdbcConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();

        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);

        return ds;
    }

    /**
     * Configures OpenAPI for API documentation. This bean initializes the OpenAPI specification with basic information about the API.
     *  
     * @return
     */
     @Bean
    public OpenAPI tradeMeshOpenAPI() {
        return new OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info()
                        .title("TradeMesh API")
                        .description("Algorithmic Trading Platform")
                        .version("1.0.0"));
    }
}