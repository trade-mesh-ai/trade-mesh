package com.trademesh.domain;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Domain entity representing a tradeable instrument (maps to the {@code instruments} table).
 */
@Data
@Builder
@Table("instruments")
public class Instrument {

    @Id
    private Long instrumentToken;
    private Long exchangeToken;
    private String tradingsymbol;
    private String name;
    private String exchange;
    private String segment;
    private String instrumentType;
    private String expiry;
    private Double strike;
    private Double tickSize;
    private Integer lotSize;
    private String lastUpdated;
}
