package com.app.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.PartyVehicle;

public interface PartyVehicleRepository extends JpaRepository<PartyVehicle, Long>{

	Optional<List<PartyVehicle>> findByPartyId(Long id);

}
