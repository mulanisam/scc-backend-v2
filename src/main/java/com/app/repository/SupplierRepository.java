package com.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
}

