package com.app.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
import com.app.service.MasterDataService;

@RestController
@RequestMapping("/user")
public class MasterDataController {

    private static final Logger logger = LoggerFactory.getLogger(MasterDataController.class);

    @Autowired
    private MasterDataService masterDataService;

    @GetMapping("/customers")
    public ResponseEntity<List<Customer>> getAllCustomers() {
        logger.info("Entering getAllCustomers endpoint");
        List<Customer> customers = masterDataService.getAllCustomers();
        logger.info("Returning {} customers", customers.size());
        logger.info("Exiting getAllCustomers endpoint");
        return ResponseEntity.ok(customers);
    }

    @PostMapping("/customers")
    public ResponseEntity<Customer> createCustomer(@RequestBody CustomerDTO customerDto) {
        logger.info("Entering createCustomer endpoint with DTO: {}", customerDto);
        Customer customer = masterDataService.createCustomer(customerDto);
        logger.info("Created customer with ID: {}", customer.getId());
        logger.info("Exiting createCustomer endpoint");
        return new ResponseEntity<>(customer, HttpStatus.CREATED);
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<Customer> getCustomerById(@PathVariable Long id) {
        logger.info("Entering getCustomerById endpoint with ID: {}", id);
        Customer customer = masterDataService.getCustomerById(id);
        logger.info("Returning customer with ID: {}", id);
        logger.info("Exiting getCustomerById endpoint");
        return ResponseEntity.ok(customer);
    }

    @PutMapping("/customers/{id}")
    public ResponseEntity<Customer> updateCustomer(@PathVariable Long id, @RequestBody CustomerDTO customerDto) {
        logger.info("Entering updateCustomer endpoint with ID: {} and DTO: {}", id, customerDto);
        Customer customer = masterDataService.updateCustomer(id, customerDto);
        logger.info("Updated customer with ID: {}", id);
        logger.info("Exiting updateCustomer endpoint");
        return ResponseEntity.ok(customer);
    }

    @DeleteMapping("/customers/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable Long id) {
        logger.info("Entering deleteCustomer endpoint with ID: {}", id);
        masterDataService.deleteCustomer(id);
        logger.info("Deleted customer with ID: {}", id);
        logger.info("Exiting deleteCustomer endpoint");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/customers/byRoute/{routeId}")
    public ResponseEntity<List<Customer>> getCustomersByRoute(@PathVariable Long routeId) {
        logger.info("Entering getCustomersByRoute endpoint with Route ID: {}", routeId);
        List<Customer> customers = masterDataService.getCustomersByRoute(routeId);
        logger.info("Returning {} customers for Route ID: {}",customers.get(0).isObsolete(), routeId);
        logger.info("Exiting getCustomersByRoute endpoint");
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/suppliers")
    public ResponseEntity<List<Supplier>> getAllSupplier() {
        logger.info("Entering getAllSupplier endpoint");
        List<Supplier> suppliers = masterDataService.getAllSuppliers();
        logger.info("Returning {} suppliers", suppliers.size());
        logger.info("Exiting getAllSupplier endpoint");
        return ResponseEntity.ok(suppliers);
    }
    
    @PostMapping("/suppliers")
    public ResponseEntity<Supplier> createSupplier(@RequestBody Supplier supplier) {
        logger.info("Entering createSupplier endpoint with Supplier: {}", supplier);
        Supplier createdSupplier = masterDataService.createSupplier(supplier);
        logger.info("Created supplier with ID: {}", createdSupplier.getId());
        logger.info("Exiting createSupplier endpoint");
        return new ResponseEntity<>(createdSupplier, HttpStatus.CREATED);
    }

    @GetMapping("/suppliers/{id}")
    public ResponseEntity<Supplier> getSupplierById(@PathVariable Long id) {
        logger.info("Entering getSupplierById endpoint with ID: {}", id);
        Supplier supplier = masterDataService.getSupplierById(id);
        logger.info("Returning supplier with ID: {}", id);
        logger.info("Exiting getSupplierById endpoint");
        return ResponseEntity.ok(supplier);
    }

    @PutMapping("/suppliers/{id}")
    public ResponseEntity<Supplier> updateSupplier(@PathVariable Long id, @RequestBody Supplier supplier) {
        logger.info("Entering updateSupplier endpoint with ID: {} and Supplier: {}", id, supplier);
        Supplier updatedSupplier = masterDataService.updateSupplier(id, supplier);
        logger.info("Updated supplier with ID: {}", updatedSupplier.getId());
        logger.info("Exiting updateSupplier endpoint");
        return ResponseEntity.ok(updatedSupplier);
    }

    @DeleteMapping("/suppliers/{id}")
    public ResponseEntity<Void> deleteSupplier(@PathVariable Long id) {
        logger.info("Entering deleteSupplier endpoint with ID: {}", id);
        masterDataService.deleteSupplier(id);
        logger.info("Deleted supplier with ID: {}", id);
        logger.info("Exiting deleteSupplier endpoint");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/drivers")
    public ResponseEntity<List<Driver>> getAllDrivers() {
        logger.info("Entering getAllDrivers endpoint");
        List<Driver> drivers = masterDataService.getAllDrivers();
        logger.info("Returning {} drivers", drivers.size());
        logger.info("Exiting getAllDrivers endpoint");
        return ResponseEntity.ok(drivers);
    }

    @PostMapping("/drivers")
    public ResponseEntity<Driver> createDriver(@RequestBody Driver driver) {
        logger.info("Entering createDriver endpoint with Driver: {}", driver);
        Driver createdDriver = masterDataService.createDriver(driver);
        logger.info("Created driver with ID: {}", createdDriver.getId());
        logger.info("Exiting createDriver endpoint");
        return new ResponseEntity<>(createdDriver, HttpStatus.CREATED);
    }

    @GetMapping("/drivers/{id}")
    public ResponseEntity<Driver> getDriverById(@PathVariable Long id) {
        logger.info("Entering getDriverById endpoint with ID: {}", id);
        Driver driver = masterDataService.getDriverById(id);
        logger.info("Returning driver with ID: {}", id);
        logger.info("Exiting getDriverById endpoint");
        return ResponseEntity.ok(driver);
    }

    @PutMapping("/drivers/{id}")
    public ResponseEntity<Driver> updateDriver(@PathVariable Long id, @RequestBody Driver driver) {
        logger.info("Entering updateDriver endpoint with ID: {} and Driver: {}", id, driver);
        Driver updatedDriver = masterDataService.updateDriver(id, driver);
        logger.info("Updated driver with ID: {}", updatedDriver.getId());
        logger.info("Exiting updateDriver endpoint");
        return ResponseEntity.ok(updatedDriver);
    }

    @DeleteMapping("/drivers/{id}")
    public ResponseEntity<Void> deleteDriver(@PathVariable Long id) {
        logger.info("Entering deleteDriver endpoint with ID: {}", id);
        masterDataService.deleteDriver(id);
        logger.info("Deleted driver with ID: {}", id);
        logger.info("Exiting deleteDriver endpoint");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/routes")
    public ResponseEntity<List<Route>> getAllRoutes() {
        logger.info("Entering getAllRoutes endpoint");
        List<Route> routes = masterDataService.getAllRoutes();
        logger.info("Returning {} routes", routes.size());
        logger.info("Exiting getAllRoutes endpoint");
        return ResponseEntity.ok(routes);
    }

    @PostMapping("/routes")
    public ResponseEntity<Route> createRoute(@RequestBody Route route) {
        logger.info("Entering createRoute endpoint with Route: {}", route);
        Route createdRoute = masterDataService.createRoute(route);
        logger.info("Created route with ID: {}", createdRoute.getId());
        logger.info("Exiting createRoute endpoint");
        return new ResponseEntity<>(createdRoute, HttpStatus.CREATED);
    }

    @GetMapping("/routes/{id}")
    public ResponseEntity<Route> getRouteById(@PathVariable Long id) {
        logger.info("Entering getRouteById endpoint with ID: {}", id);
        Route route = masterDataService.getRouteById(id);
        logger.info("Returning route with ID: {}", id);
        logger.info("Exiting getRouteById endpoint");
        return ResponseEntity.ok(route);
    }

    @PutMapping("/routes/{id}")
    public ResponseEntity<Route> updateRoute(@PathVariable Long id, @RequestBody Route route) {
        logger.info("Entering updateRoute endpoint with ID: {} and Route: {}", id, route);
        Route updatedRoute = masterDataService.updateRoute(id, route);
        logger.info("Updated route with ID: {}", updatedRoute.getId());
        logger.info("Exiting updateRoute endpoint");
        return ResponseEntity.ok(updatedRoute);
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Void> deleteRoute(@PathVariable Long id) {
        logger.info("Entering deleteRoute endpoint with ID: {}", id);
        masterDataService.deleteRoute(id);
        logger.info("Deleted route with ID: {}", id);
        logger.info("Exiting deleteRoute endpoint");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cities")
    public ResponseEntity<List<City>> getAllCities() {
        logger.info("Entering getAllCities endpoint");
        List<City> cities = masterDataService.getAllCities();
        logger.info("Returning {} cities", cities.size());
        logger.info("Exiting getAllCities endpoint");
        return ResponseEntity.ok(cities);
    }

    @PostMapping("/cities")
    public ResponseEntity<City> createCity(@RequestBody CityDTO cityDto) {
        logger.info("Entering createCity endpoint with DTO: {}", cityDto);
        City createdCity = masterDataService.createCity(cityDto);
        logger.info("Created city with ID: {}", createdCity.getId());
        logger.info("Exiting createCity endpoint");
        return new ResponseEntity<>(createdCity, HttpStatus.CREATED);
    }

    @GetMapping("/cities/{id}")
    public ResponseEntity<City> getCityById(@PathVariable Long id) {
        logger.info("Entering getCityById endpoint with ID: {}", id);
        City city = masterDataService.getCityById(id);
        logger.info("Returning city with ID: {}", id);
        logger.info("Exiting getCityById endpoint");
        return ResponseEntity.ok(city);
    }

    @PutMapping("/cities/{id}")
    public ResponseEntity<City> updateCity(@PathVariable Long id, @RequestBody CityDTO cityDto) {
        logger.info("Entering updateCity endpoint with ID: {} and DTO: {}", id, cityDto);
        City updatedCity = masterDataService.updateCity(id, cityDto);
        logger.info("Updated city with ID: {}", updatedCity.getId());
        logger.info("Exiting updateCity endpoint");
        return ResponseEntity.ok(updatedCity);
    }

    @DeleteMapping("/cities/{id}")
    public ResponseEntity<Void> deleteCity(@PathVariable Long id) {
        logger.info("Entering deleteCity endpoint with ID: {}", id);
        masterDataService.deleteCity(id);
        logger.info("Deleted city with ID: {}", id);
        logger.info("Exiting deleteCity endpoint");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vehicles")
    public ResponseEntity<List<Vehicle>> getAllVehicles() {
        logger.info("Entering getAllVehicles endpoint");
        List<Vehicle> vehicles = masterDataService.getAllVehicles();
        logger.info("Returning {} vehicles", vehicles.size());
        logger.info("Exiting getAllVehicles endpoint");
        return ResponseEntity.ok(vehicles);
    }

    @PostMapping("/vehicles")
    public ResponseEntity<Vehicle> createVehicle(@RequestBody Vehicle vehicle) {
        logger.info("Entering createVehicle endpoint with Vehicle: {}", vehicle);
        Vehicle createdVehicle = masterDataService.createVehicle(vehicle);
        logger.info("Created vehicle with ID: {}", createdVehicle.getId());
        logger.info("Exiting createVehicle endpoint");
        return new ResponseEntity<>(createdVehicle, HttpStatus.CREATED);
    }

    @GetMapping("/vehicles/{id}")
    public ResponseEntity<Vehicle> getVehicleById(@PathVariable Long id) {
        logger.info("Entering getVehicleById endpoint with ID: {}", id);
        Vehicle vehicle = masterDataService.getVehicleById(id);
        logger.info("Returning vehicle with ID: {}", id);
        logger.info("Exiting getVehicleById endpoint");
        return ResponseEntity.ok(vehicle);
    }

    @PutMapping("/vehicles/{id}")
    public ResponseEntity<Vehicle> updateVehicle(@PathVariable Long id, @RequestBody Vehicle vehicle) {
        logger.info("Entering updateVehicle endpoint with ID: {} and Vehicle: {}", id, vehicle);
        Vehicle updatedVehicle = masterDataService.updateVehicle(id, vehicle);
        logger.info("Updated vehicle with ID: {}", updatedVehicle.getId());
        logger.info("Exiting updateVehicle endpoint");
        return ResponseEntity.ok(updatedVehicle);
    }

    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        logger.info("Entering deleteVehicle endpoint with ID: {}", id);
        masterDataService.deleteVehicle(id);
        logger.info("Deleted vehicle with ID: {}", id);
        logger.info("Exiting deleteVehicle endpoint");
        return ResponseEntity.noContent().build();
    }
 // Party endpoints

    @GetMapping("/parties")
    public ResponseEntity<List<Party>> getAllParties() {
        logger.info("Entering getAllParties endpoint");
        List<Party> parties = masterDataService.getAllParties();
        logger.info("Returning {} parties", parties.size());
        logger.info("Exiting getAllParties endpoint");
        return ResponseEntity.ok(parties);
    }

    @PostMapping("/parties")
    public ResponseEntity<Party> createParty(@RequestBody Party party) {
        logger.info("Entering createParty endpoint with Party: {}", party);
        Party createdParty = masterDataService.createParty(party);
        logger.info("Created party with ID: {}", createdParty.getId());
        logger.info("Exiting createParty endpoint");
        return new ResponseEntity<>(createdParty, HttpStatus.CREATED);
    }

    @GetMapping("/parties/{id}")
    public ResponseEntity<Party> getPartyById(@PathVariable Long id) {
        logger.info("Entering getPartyById endpoint with ID: {}", id);
        Party party = masterDataService.getPartyById(id);
        logger.info("Returning party with ID: {}", id);
        logger.info("Exiting getPartyById endpoint");
        return ResponseEntity.ok(party);
    }

    @PutMapping("/parties/{id}")
    public ResponseEntity<Party> updateParty(@PathVariable Long id, @RequestBody Party party) {
        logger.info("Entering updateParty endpoint with ID: {} and Party: {}", id, party);
        Party updatedParty = masterDataService.updateParty(id, party);
        logger.info("Updated party with ID: {}", updatedParty.getId());
        logger.info("Exiting updateParty endpoint");
        return ResponseEntity.ok(updatedParty);
    }

    @DeleteMapping("/parties/{id}")
    public ResponseEntity<Void> deleteParty(@PathVariable Long id) {
        logger.info("Entering deleteParty endpoint with ID: {}", id);
        masterDataService.deleteParty(id);
        logger.info("Deleted party with ID: {}", id);
        logger.info("Exiting deleteParty endpoint");
        return ResponseEntity.noContent().build();
    }

    // PartyVehicle endpoints

    @GetMapping("/partyVehicles")
    public ResponseEntity<List<PartyVehicle>> getAllPartyVehicles() {
        logger.info("Entering getAllPartyVehicles endpoint");
        List<PartyVehicle> partyVehicles = masterDataService.getAllPartyVehicles();
        logger.info("Returning {} party vehicles", partyVehicles.size());
        logger.info("Exiting getAllPartyVehicles endpoint");
        return ResponseEntity.ok(partyVehicles);
    }

    @PostMapping("/partyVehicles")
    public ResponseEntity<PartyVehicle> createPartyVehicle(@RequestBody PartyVehicle partyVehicle) {
        logger.info("Entering createPartyVehicle endpoint with PartyVehicle: {}", partyVehicle);
        PartyVehicle createdPartyVehicle = masterDataService.createPartyVehicle(partyVehicle);
        logger.info("Created party vehicle with ID: {}", createdPartyVehicle.getId());
        logger.info("Exiting createPartyVehicle endpoint");
        return new ResponseEntity<>(createdPartyVehicle, HttpStatus.CREATED);
    }

    @GetMapping("/partyVehicles/{id}")
    public ResponseEntity<PartyVehicle> getPartyVehicleById(@PathVariable Long id) {
        logger.info("Entering getPartyVehicleById endpoint with ID: {}", id);
        PartyVehicle partyVehicle = masterDataService.getPartyVehicleById(id);
        logger.info("Returning party vehicle with ID: {}", id);
        logger.info("Exiting getPartyVehicleById endpoint");
        return ResponseEntity.ok(partyVehicle);
    }

    @PutMapping("/partyVehicles/{id}")
    public ResponseEntity<PartyVehicle> updatePartyVehicle(@PathVariable Long id, @RequestBody PartyVehicle partyVehicle) {
        logger.info("Entering updatePartyVehicle endpoint with ID: {} and PartyVehicle: {}", id, partyVehicle);
        PartyVehicle updatedPartyVehicle = masterDataService.updatePartyVehicle(id, partyVehicle);
        logger.info("Updated party vehicle with ID: {}", updatedPartyVehicle.getId());
        logger.info("Exiting updatePartyVehicle endpoint");
        return ResponseEntity.ok(updatedPartyVehicle);
    }

    @DeleteMapping("/partyVehicles/{id}")
    public ResponseEntity<Void> deletePartyVehicle(@PathVariable Long id) {
        logger.info("Entering deletePartyVehicle endpoint with ID: {}", id);
        masterDataService.deletePartyVehicle(id);
        logger.info("Deleted party vehicle with ID: {}", id);
        logger.info("Exiting deletePartyVehicle endpoint");
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/partyVehicles/party/{id}")
    public ResponseEntity<List<PartyVehicle>> getPartyVehicleByPartyId(@PathVariable Long id) {
        logger.info("Entering getPartyVehicleByPartyId endpoint with ID: {}", id);
        List<PartyVehicle> partyVehicle = masterDataService.getPartyVehicleByPartyId(id);
        logger.info("Returning party vehicle with Party ID: {}", id);
        logger.info("Exiting getPartyVehicleByPartyId endpoint");
        return ResponseEntity.ok(partyVehicle);
    }
}
