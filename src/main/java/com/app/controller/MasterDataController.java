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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.CityDTO;
import com.app.dto.CustomerDTO;
import com.app.dto.contact.ContactQualityResponse;
import com.app.dto.contact.MobileNumberUpdateRequest;
import com.app.entity.City;
import com.app.entity.Customer;
import com.app.entity.Driver;
import com.app.entity.Party;
import com.app.entity.Route;
import com.app.entity.Supplier;
import com.app.entity.Vehicle;
import com.app.service.ContactQualityService;
import com.app.service.MasterDataService;

@RestController
@RequestMapping("/user")
public class MasterDataController {

    private static final Logger logger = LoggerFactory.getLogger(MasterDataController.class);

    @Autowired
    private MasterDataService masterDataService;

    @Autowired
    private ContactQualityService contactQualityService;

    @GetMapping("/customers")
    public ResponseEntity<List<Customer>> getAllCustomers() {
        List<Customer> customers = masterDataService.getAllCustomers();
        logger.info("Returning {} customers", customers.size());
        return ResponseEntity.ok(customers);
    }

    @PostMapping("/customers")
    public ResponseEntity<Customer> createCustomer(@RequestBody CustomerDTO customerDto) {
        Customer customer = masterDataService.createCustomer(customerDto);
        logger.info("Created customer with ID: {}", customer.getId());
        return new ResponseEntity<>(customer, HttpStatus.CREATED);
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<Customer> getCustomerById(@PathVariable Long id) {
        Customer customer = masterDataService.getCustomerById(id);
        logger.info("Returning customer with ID: {}", id);
        return ResponseEntity.ok(customer);
    }

    @PutMapping("/customers/{id}")
    public ResponseEntity<Customer> updateCustomer(@PathVariable Long id, @RequestBody CustomerDTO customerDto) {
        logger.info("Entering updateCustomer endpoint with ID: {} and DTO: {}", id, customerDto);
        Customer customer = masterDataService.updateCustomer(id, customerDto);
        logger.info("Updated customer with ID: {}", id);
        return ResponseEntity.ok(customer);
    }

    @DeleteMapping("/customers/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable Long id) {
        masterDataService.deleteCustomer(id);
        logger.info("Deleted customer with ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The state of the customer contact book: who cannot be messaged and why, and
     * which numbers are shared by more than one customer.
     *
     * Needed before statements can be sent over WhatsApp, because a statement
     * carries a balance and can only go to a number belonging to one customer.
     */
    @GetMapping("/customers/contact-quality")
    public ResponseEntity<ContactQualityResponse> getContactQuality() {
        return ResponseEntity.ok(contactQualityService.getContactQuality());
    }

    /**
     * Corrects one of a customer's two mobile numbers and touches nothing else.
     *
     * Separate from PUT /customers/{id}, which takes a whole CustomerDTO - fixing a
     * phone number through that means resending every other field, and whatever the
     * caller omits is written back as null.
     *
     * Set {@code alternate} to edit the second number for the shop. A blank value
     * clears it; the main number cannot be cleared, only replaced.
     */
    @PatchMapping("/customers/{id}/mobile")
    public ResponseEntity<Customer> updateCustomerMobile(
            @PathVariable Long id,
            @RequestBody MobileNumberUpdateRequest request) {

        logger.info("Entering updateCustomerMobile endpoint for customer {} ({} number)",
                id, request.isAlternate() ? "second" : "main");
        return ResponseEntity.ok(contactQualityService.updateMobileNumber(
                id, request.getMobileNo(), request.isAlternate()));
    }

    @GetMapping("/customers/byRoute/{routeId}")
    public ResponseEntity<List<Customer>> getCustomersByRoute(@PathVariable Long routeId) {
        List<Customer> customers = masterDataService.getCustomersByRoute(routeId);
        logger.info("Returning {} customers for Route ID: {}",customers.get(0).isObsolete(), routeId);
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/suppliers")
    public ResponseEntity<List<Supplier>> getAllSupplier() {
        List<Supplier> suppliers = masterDataService.getAllSuppliers();
        logger.info("Returning {} suppliers", suppliers.size());
        return ResponseEntity.ok(suppliers);
    }
    
    @PostMapping("/suppliers")
    public ResponseEntity<Supplier> createSupplier(@RequestBody Supplier supplier) {
        Supplier createdSupplier = masterDataService.createSupplier(supplier);
        logger.info("Created supplier with ID: {}", createdSupplier.getId());
        return new ResponseEntity<>(createdSupplier, HttpStatus.CREATED);
    }

    @GetMapping("/suppliers/{id}")
    public ResponseEntity<Supplier> getSupplierById(@PathVariable Long id) {
        Supplier supplier = masterDataService.getSupplierById(id);
        logger.info("Returning supplier with ID: {}", id);
        return ResponseEntity.ok(supplier);
    }

    @PutMapping("/suppliers/{id}")
    public ResponseEntity<Supplier> updateSupplier(@PathVariable Long id, @RequestBody Supplier supplier) {
        logger.info("Entering updateSupplier endpoint with ID: {} and Supplier: {}", id, supplier);
        Supplier updatedSupplier = masterDataService.updateSupplier(id, supplier);
        logger.info("Updated supplier with ID: {}", updatedSupplier.getId());
        return ResponseEntity.ok(updatedSupplier);
    }

    @DeleteMapping("/suppliers/{id}")
    public ResponseEntity<Void> deleteSupplier(@PathVariable Long id) {
        masterDataService.deleteSupplier(id);
        logger.info("Deleted supplier with ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/drivers")
    public ResponseEntity<List<Driver>> getAllDrivers() {
        List<Driver> drivers = masterDataService.getAllDrivers();
        logger.info("Returning {} drivers", drivers.size());
        return ResponseEntity.ok(drivers);
    }

    @PostMapping("/drivers")
    public ResponseEntity<Driver> createDriver(@RequestBody Driver driver) {
        Driver createdDriver = masterDataService.createDriver(driver);
        logger.info("Created driver with ID: {}", createdDriver.getId());
        return new ResponseEntity<>(createdDriver, HttpStatus.CREATED);
    }

    @GetMapping("/drivers/{id}")
    public ResponseEntity<Driver> getDriverById(@PathVariable Long id) {
        Driver driver = masterDataService.getDriverById(id);
        logger.info("Returning driver with ID: {}", id);
        return ResponseEntity.ok(driver);
    }

    @PutMapping("/drivers/{id}")
    public ResponseEntity<Driver> updateDriver(@PathVariable Long id, @RequestBody Driver driver) {
        logger.info("Entering updateDriver endpoint with ID: {} and Driver: {}", id, driver);
        Driver updatedDriver = masterDataService.updateDriver(id, driver);
        logger.info("Updated driver with ID: {}", updatedDriver.getId());
        return ResponseEntity.ok(updatedDriver);
    }

    @DeleteMapping("/drivers/{id}")
    public ResponseEntity<Void> deleteDriver(@PathVariable Long id) {
        masterDataService.deleteDriver(id);
        logger.info("Deleted driver with ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/routes")
    public ResponseEntity<List<Route>> getAllRoutes() {
        List<Route> routes = masterDataService.getAllRoutes();
        logger.info("Returning {} routes", routes.size());
        return ResponseEntity.ok(routes);
    }

    @PostMapping("/routes")
    public ResponseEntity<Route> createRoute(@RequestBody Route route) {
        Route createdRoute = masterDataService.createRoute(route);
        logger.info("Created route with ID: {}", createdRoute.getId());
        return new ResponseEntity<>(createdRoute, HttpStatus.CREATED);
    }

    @GetMapping("/routes/{id}")
    public ResponseEntity<Route> getRouteById(@PathVariable Long id) {
        Route route = masterDataService.getRouteById(id);
        logger.info("Returning route with ID: {}", id);
        return ResponseEntity.ok(route);
    }

    @PutMapping("/routes/{id}")
    public ResponseEntity<Route> updateRoute(@PathVariable Long id, @RequestBody Route route) {
        logger.info("Entering updateRoute endpoint with ID: {} and Route: {}", id, route);
        Route updatedRoute = masterDataService.updateRoute(id, route);
        logger.info("Updated route with ID: {}", updatedRoute.getId());
        return ResponseEntity.ok(updatedRoute);
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Void> deleteRoute(@PathVariable Long id) {
        masterDataService.deleteRoute(id);
        logger.info("Deleted route with ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cities")
    public ResponseEntity<List<City>> getAllCities() {
        List<City> cities = masterDataService.getAllCities();
        logger.info("Returning {} cities", cities.size());
        return ResponseEntity.ok(cities);
    }

    @PostMapping("/cities")
    public ResponseEntity<City> createCity(@RequestBody CityDTO cityDto) {
        City createdCity = masterDataService.createCity(cityDto);
        logger.info("Created city with ID: {}", createdCity.getId());
        return new ResponseEntity<>(createdCity, HttpStatus.CREATED);
    }

    @GetMapping("/cities/{id}")
    public ResponseEntity<City> getCityById(@PathVariable Long id) {
        City city = masterDataService.getCityById(id);
        logger.info("Returning city with ID: {}", id);
        return ResponseEntity.ok(city);
    }

    @PutMapping("/cities/{id}")
    public ResponseEntity<City> updateCity(@PathVariable Long id, @RequestBody CityDTO cityDto) {
        logger.info("Entering updateCity endpoint with ID: {} and DTO: {}", id, cityDto);
        City updatedCity = masterDataService.updateCity(id, cityDto);
        logger.info("Updated city with ID: {}", updatedCity.getId());
        return ResponseEntity.ok(updatedCity);
    }

    @DeleteMapping("/cities/{id}")
    public ResponseEntity<Void> deleteCity(@PathVariable Long id) {
        masterDataService.deleteCity(id);
        logger.info("Deleted city with ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vehicles")
    public ResponseEntity<List<Vehicle>> getAllVehicles() {
        List<Vehicle> vehicles = masterDataService.getAllVehicles();
        logger.info("Returning {} vehicles", vehicles.size());
        return ResponseEntity.ok(vehicles);
    }

    @PostMapping("/vehicles")
    public ResponseEntity<Vehicle> createVehicle(@RequestBody Vehicle vehicle) {
        Vehicle createdVehicle = masterDataService.createVehicle(vehicle);
        logger.info("Created vehicle with ID: {}", createdVehicle.getId());
        return new ResponseEntity<>(createdVehicle, HttpStatus.CREATED);
    }

    @GetMapping("/vehicles/{id}")
    public ResponseEntity<Vehicle> getVehicleById(@PathVariable Long id) {
        Vehicle vehicle = masterDataService.getVehicleById(id);
        logger.info("Returning vehicle with ID: {}", id);
        return ResponseEntity.ok(vehicle);
    }

    @PutMapping("/vehicles/{id}")
    public ResponseEntity<Vehicle> updateVehicle(@PathVariable Long id, @RequestBody Vehicle vehicle) {
        logger.info("Entering updateVehicle endpoint with ID: {} and Vehicle: {}", id, vehicle);
        Vehicle updatedVehicle = masterDataService.updateVehicle(id, vehicle);
        logger.info("Updated vehicle with ID: {}", updatedVehicle.getId());
        return ResponseEntity.ok(updatedVehicle);
    }

    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        masterDataService.deleteVehicle(id);
        logger.info("Deleted vehicle with ID: {}", id);
        return ResponseEntity.noContent().build();
    }
 // Party endpoints

    @GetMapping("/parties")
    public ResponseEntity<List<Party>> getAllParties() {
        List<Party> parties = masterDataService.getAllParties();
        logger.info("Returning {} parties", parties.size());
        return ResponseEntity.ok(parties);
    }

    @PostMapping("/parties")
    public ResponseEntity<Party> createParty(@RequestBody Party party) {
        Party createdParty = masterDataService.createParty(party);
        logger.info("Created party with ID: {}", createdParty.getId());
        return new ResponseEntity<>(createdParty, HttpStatus.CREATED);
    }

    @GetMapping("/parties/{id}")
    public ResponseEntity<Party> getPartyById(@PathVariable Long id) {
        Party party = masterDataService.getPartyById(id);
        logger.info("Returning party with ID: {}", id);
        return ResponseEntity.ok(party);
    }

    @PutMapping("/parties/{id}")
    public ResponseEntity<Party> updateParty(@PathVariable Long id, @RequestBody Party party) {
        logger.info("Entering updateParty endpoint with ID: {} and Party: {}", id, party);
        Party updatedParty = masterDataService.updateParty(id, party);
        logger.info("Updated party with ID: {}", updatedParty.getId());
        return ResponseEntity.ok(updatedParty);
    }

    @DeleteMapping("/parties/{id}")
    public ResponseEntity<Void> deleteParty(@PathVariable Long id) {
        masterDataService.deleteParty(id);
        logger.info("Deleted party with ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    /*
     * The PartyVehicle endpoints are gone.
     *
     * A party's vehicles are now a list on the party itself, saved with it through
     * POST/PUT /user/parties. They were never separate things: a party vehicle has one
     * attribute and nothing refers to one, so six endpoints and a Master Data tab existed
     * to hold a registration number. The create endpoint could not be used at all - it
     * bound the entity, so the screen's bare party id came back as a 400 every time.
     */
}
