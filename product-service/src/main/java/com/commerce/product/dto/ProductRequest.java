package com.commerce.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;


@Data
public class ProductRequest {
    @NotBlank
    private String name;

    private String description;

    @Size(max = 1000)
    private String imageUrl;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal price;

    @NotBlank
    @Size(max = 50)
    private String sku;

    @NotNull
    @Min(0)
    private Integer stock;
}
