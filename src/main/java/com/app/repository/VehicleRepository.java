package com.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.Vehicle;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

}
