package com.commerce.product.mapper;

import com.commerce.product.dto.ProductRequest;
import com.commerce.product.dto.ProductResponse;
import com.commerce.product.entity.Product;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductResponse toResponse(Product product);

    Product toEntity(ProductRequest request);
}