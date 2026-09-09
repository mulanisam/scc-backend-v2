package com.app.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.Party;

public interface PartyRepository extends JpaRepository<Party, Long>{

}
