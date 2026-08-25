package com.commerce.product.service;

import com.commerce.product.dto.ProductRequest;
import com.commerce.product.dto.ProductResponse;
import com.commerce.product.dto.PagedResponse;
import com.commerce.product.entity.Product;
import com.commerce.product.exception.DuplicateSkuException;
import com.commerce.product.exception.ProductNotFoundException;
import com.commerce.product.mapper.ProductMapper;
import com.commerce.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository repository;
    private final ProductMapper mapper;

    public ProductResponse create(ProductRequest request) {
        if (repository.findBySku(request.getSku()).isPresent()) {
            throw new DuplicateSkuException("Product with SKU '" + request.getSku() + "' already exists");
        }
        Product product = mapper.toEntity(request);
        return mapper.toResponse(repository.save(product));
    }

    public ProductResponse getById(UUID id) {
        Product product = repository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product with id " + id + " not found"));
        return mapper.toResponse(product);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> getAll(Pageable pageable) {
        Pageable sorted = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<Product> page = repository.findAll(sorted);
        return PagedResponse.of(page.map(mapper::toResponse));
    }

    public ProductResponse update(UUID id, ProductRequest request) {
        Product existing = repository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product with id " + id + " not found"));
        if (!existing.getSku().equals(request.getSku()) && repository.findBySku(request.getSku()).isPresent()) {
            throw new DuplicateSkuException("Product with SKU '" + request.getSku() + "' already exists");
        }
        existing.setName(request.getName());
        existing.setPrice(request.getPrice());
        existing.setSku(request.getSku());
        existing.setStock(request.getStock());
        existing.setDescription(request.getDescription());
        return mapper.toResponse(repository.save(existing));
    }

    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ProductNotFoundException("Product with id " + id + " not found");
        }
        repository.deleteById(id);
    }
}