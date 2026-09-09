package com.app.service;

import java.util.List;

import com.app.dto.CityDTO;
import com.app.dto.CustomerDTO;
import com.app.entity.City;
import com.app.entity.Customer;
import com.app.entity.Driver;
import com.app.entity.Party;
import com.app.entity.PartyVehicle;
import com.app.entity.Route;
import com.app.entity.Supplier;
import com.app.entity.Vehicle;

public interface MasterDataService {
    List<Customer> getAllCustomers();
    Customer createCustomer(CustomerDTO customerDto);
    Customer getCustomerById(Long id);
    Customer updateCustomer(Long id, CustomerDTO customerDto);
    void deleteCustomer(Long id);
    List<Customer> getCustomersByRoute(Long routeId);

    List<Supplier> getAllSuppliers();
    Supplier createSupplier(Supplier supplier);
    Supplier getSupplierById(Long id);
    Supplier updateSupplier(Long id, Supplier supplierDetails);
    void deleteSupplier(Long id);

    List<Driver> getAllDrivers();
    Driver createDriver(Driver driver);
    Driver getDriverById(Long id);
    Driver updateDriver(Long id, Driver driverDetails);
    void deleteDriver(Long id);

    List<Route> getAllRoutes();
    Route createRoute(Route route);
    Route getRouteById(Long id);
    Route updateRoute(Long id, Route routeDetails);
    void deleteRoute(Long id);

    List<City> getAllCities();
    City createCity(CityDTO cityDto);
    City getCityById(Long id);
    City updateCity(Long id, CityDTO cityDetails);
    void deleteCity(Long id);

    List<Vehicle> getAllVehicles();
    Vehicle createVehicle(Vehicle vehicle);
    Vehicle getVehicleById(Long id);
    Vehicle updateVehicle(Long id, Vehicle vehicleDetails);
    void deleteVehicle(Long id);
    
 // Party methods
    List<Party> getAllParties();
    Party createParty(Party party);
    Party getPartyById(Long id);
    Party updateParty(Long id, Party partyDetails);
    void deleteParty(Long id);

    // PartyVehicle methods
    List<PartyVehicle> getAllPartyVehicles();
    PartyVehicle createPartyVehicle(PartyVehicle partyVehicle);
    PartyVehicle getPartyVehicleById(Long id);
    PartyVehicle updatePartyVehicle(Long id, PartyVehicle partyVehicleDetails);
    void deletePartyVehicle(Long id);
    List<PartyVehicle> getPartyVehicleByPartyId(Long id);
}
