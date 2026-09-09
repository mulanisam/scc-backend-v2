package com.app.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.Driver;

public interface DriverRepository extends JpaRepository<Driver, Long> {
}

