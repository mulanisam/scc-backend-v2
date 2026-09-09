package com.app.repository;
import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.City;

public interface CityRepository extends JpaRepository<City, Long> {
}
