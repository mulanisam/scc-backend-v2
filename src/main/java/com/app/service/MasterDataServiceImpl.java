package com.app.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CityDTO;
import com.app.dto.CustomerDTO;
import com.app.utility.MobileNumberRules;
import com.app.utility.MoneyRules;
import com.app.entity.City;
import com.app.entity.Customer;
import com.app.entity.Driver;
import com.app.entity.Party;
import com.app.entity.Route;
import com.app.entity.Supplier;
import com.app.entity.Vehicle;
import com.app.repository.CityRepository;
import com.app.repository.CustomerRepository;
import com.app.repository.DriverRepository;
import com.app.repository.PartyRepository;
import com.app.repository.RouteRepository;
import com.app.repository.SupplierRepository;
import com.app.repository.VehicleRepository;

import com.app.exception.ResourceNotFoundException;


@Service
@Transactional
public class MasterDataServiceImpl implements MasterDataService {

    private static final Logger logger = LoggerFactory.getLogger(MasterDataServiceImpl.class);

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private DriverRepository driverRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private CityRepository cityRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private PartyRepository partyRepository;

    @Override
    public List<Customer> getAllCustomers() {
        List<Customer> customers = customerRepository.findAll();
        logger.info("Retrieved {} customers", customers.size());
        return customers;
    }

    @Override
    public Customer createCustomer(CustomerDTO customerDto) {
        Customer customer = new Customer();
        customer.setName(customerDto.getName());
        customer.setAddress(customerDto.getAddress());
        customer.setMobileNo(normaliseNumber(customerDto.getMobileNo()));
        customer.setAlternateMobileNo(normaliseNumber(customerDto.getAlternateMobileNo()));
        customer.setShopName(customerDto.getShopName());
        customer.setBalanceAmount(MoneyRules.money(new BigDecimal(customerDto.getBalanceAmount())));
        customer.setObsolete(customerDto.isObsolete());
        City city = cityRepository.findById(customerDto.getCity())
                .orElseThrow(() -> new RuntimeException("City not found"));
        customer.setCity(city);

        Customer savedCustomer = customerRepository.save(customer);
        logger.info("Created customer with ID: {}", savedCustomer.getId());
        return savedCustomer;
    }

    /**
     * Stores a number in one shape, or null when there is nothing to store.
     *
     * Normalised on the way in so "+91 97750 80207" and "09775080207" become the same
     * ten digits. Without it the duplicate check compares raw strings and two customers
     * can hold the same phone in two spellings, which is how a number already recorded
     * elsewhere gets accepted - and a shared number means a statement carrying a balance
     * goes to somebody it does not belong to.
     *
     * Blank becomes null rather than "": the contact-quality screen distinguishes "no
     * number" from "recorded as empty", and an empty string would sit in the index as a
     * value that several customers share.
     */
    private static String normaliseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalised = MobileNumberRules.normalise(raw);
        return normalised.isBlank() ? null : normalised;
    }

    @Override
    public Customer getCustomerById(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id " + id));
        logger.info("Retrieved customer: {}", customer);
        return customer;
    }

    @Override
    public Customer updateCustomer(Long id, CustomerDTO customerDto) {
        logger.info("Entering updateCustomer method with ID: {} and DTO: {}", id, customerDto);
        Customer customer = getCustomerById(id);
        customer.setName(customerDto.getName());
        customer.setAddress(customerDto.getAddress());
        customer.setMobileNo(normaliseNumber(customerDto.getMobileNo()));
        customer.setAlternateMobileNo(normaliseNumber(customerDto.getAlternateMobileNo()));
        customer.setShopName(customerDto.getShopName());
        customer.setBalanceAmount(MoneyRules.money(new BigDecimal(customerDto.getBalanceAmount())));
        customer.setObsolete(customerDto.isObsolete());
        City city = cityRepository.findById(customerDto.getCity())
                .orElseThrow(() -> new RuntimeException("City not found"));
        customer.setCity(city);

        Customer updatedCustomer = customerRepository.save(customer);
        logger.info("Updated customer with ID: {}", updatedCustomer.getId());
        return updatedCustomer;
    }

    @Override
    public void deleteCustomer(Long id) {
        Customer customer = getCustomerById(id);
        customerRepository.delete(customer);
        logger.info("Deleted customer with ID: {}", id);
    }

    @Override
    public List<Customer> getCustomersByRoute(Long routeId) {
        List<Customer> customerList = customerRepository.findByRouteId(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Customers not found with Route id " + routeId));
        logger.info("Retrieved {} customers for Route ID: {}", customerList.size(), routeId);
        return customerList;
    }

    @Override
    public Supplier createSupplier(Supplier supplier) {
        Supplier savedSupplier = supplierRepository.save(supplier);
        logger.info("Created supplier with ID: {}", savedSupplier.getId());
        return savedSupplier;
    }

    @Override
    public Supplier getSupplierById(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with id " + id));
        logger.info("Retrieved supplier: {}", supplier);
        return supplier;
    }

    @Override
    public Supplier updateSupplier(Long id, Supplier supplierDetails) {
        logger.info("Entering updateSupplier method with ID: {} and Supplier details: {}", id, supplierDetails);
        Supplier supplier = getSupplierById(id);
        supplier.setName(supplierDetails.getName());
        supplier.setBranch(supplierDetails.getBranch());
        supplier.setObsolete(supplierDetails.isObsolete());
        supplier.setPendingPayment(supplierDetails.getPendingPayment());
        // Update other fields as necessary
        Supplier updatedSupplier = supplierRepository.save(supplier);
        logger.info("Updated supplier with ID: {}", updatedSupplier.getId());
        return updatedSupplier;
    }

    @Override
    public void deleteSupplier(Long id) {
        Supplier supplier = getSupplierById(id);
        supplierRepository.delete(supplier);
        logger.info("Deleted supplier with ID: {}", id);
    }

    @Override
    public List<Driver> getAllDrivers() {
        List<Driver> drivers = driverRepository.findAll();
        logger.info("Retrieved {} drivers", drivers.size());
        return drivers;
    }

    @Override
    public Driver createDriver(Driver driver) {
        Driver savedDriver = driverRepository.save(driver);
        logger.info("Created driver with ID: {}", savedDriver.getId());
        return savedDriver;
    }

    @Override
    public Driver getDriverById(Long id) {
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found with id " + id));
        logger.info("Retrieved driver: {}", driver);
        return driver;
    }

    @Override
    public Driver updateDriver(Long id, Driver driverDetails) {
        logger.info("Entering updateDriver method with ID: {} and Driver details: {}", id, driverDetails);
        Driver driver = getDriverById(id);
        driver.setName(driverDetails.getName());
        driver.setMobileNo(driverDetails.getMobileNo());
        driver.setAddress(driverDetails.getAddress());
        // Update other fields as necessary
        Driver updatedDriver = driverRepository.save(driver);
        logger.info("Updated driver with ID: {}", updatedDriver.getId());
        return updatedDriver;
    }

    @Override
    public void deleteDriver(Long id) {
        Driver driver = getDriverById(id);
        driverRepository.delete(driver);
        logger.info("Deleted driver with ID: {}", id);
    }

    @Override
    public List<Route> getAllRoutes() {
        List<Route> routes = routeRepository.findAll();
        logger.info("Retrieved {} routes", routes.size());
        return routes;
    }

    @Override
    public Route createRoute(Route route) {
        Route savedRoute = routeRepository.save(route);
        logger.info("Created route with ID: {}", savedRoute.getId());
        return savedRoute;
    }

    @Override
    public Route getRouteById(Long id) {
        Route route = routeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found with id " + id));
        logger.info("Retrieved route: {}", route);
        return route;
    }

    @Override
    public Route updateRoute(Long id, Route routeDetails) {
        logger.info("Entering updateRoute method with ID: {} and Route details: {}", id, routeDetails);
        Route route = getRouteById(id);
        route.setName(routeDetails.getName());
        // Update other fields as necessary
        Route updatedRoute = routeRepository.save(route);
        logger.info("Updated route with ID: {}", updatedRoute.getId());
        return updatedRoute;
    }

    @Override
    public void deleteRoute(Long id) {
        Route route = getRouteById(id);
        routeRepository.delete(route);
        logger.info("Deleted route with ID: {}", id);
    }

    @Override
    public List<City> getAllCities() {
        List<City> cities = cityRepository.findAll();
        logger.info("Retrieved {} cities", cities.size());
        return cities;
    }

    @Override
    public City createCity(CityDTO cityDto) {
        City city = new City();
        city.setName(cityDto.getName());
        city.setObsolete(cityDto.isObsolete());
        Route route = routeRepository.findById(cityDto.getRoute())
                .orElseThrow(() -> new RuntimeException("Route not found"));
        city.setRoute(route);
        City savedCity = cityRepository.save(city);
        logger.info("Created city with ID: {}", savedCity.getId());
        return savedCity;
    }

    @Override
    public City getCityById(Long id) {
        City city = cityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("City not found with id " + id));
        logger.info("Retrieved city: {}", city);
        return city;
    }

    @Override
    public City updateCity(Long id, CityDTO cityDetails) {
        logger.info("Entering updateCity method with ID: {} and DTO: {}", id, cityDetails);
        City city = getCityById(id);
        city.setName(cityDetails.getName());
        Route route = routeRepository.findById(cityDetails.getRoute())
                .orElseThrow(() -> new RuntimeException("Route not found"));
        city.setRoute(route);
        // Update other fields as necessary
        City updatedCity = cityRepository.save(city);
        logger.info("Updated city with ID: {}", updatedCity.getId());
        return updatedCity;
    }

    @Override
    public void deleteCity(Long id) {
        City city = getCityById(id);
        cityRepository.delete(city);
        logger.info("Deleted city with ID: {}", id);
    }

    @Override
    public List<Vehicle> getAllVehicles() {
        List<Vehicle> vehicles = vehicleRepository.findAll();
        logger.info("Retrieved {} vehicles", vehicles.size());
        return vehicles;
    }

    @Override
    public Vehicle createVehicle(Vehicle vehicle) {
        Vehicle savedVehicle = vehicleRepository.save(vehicle);
        logger.info("Created vehicle with ID: {}", savedVehicle.getId());
        return savedVehicle;
    }

    @Override
    public Vehicle getVehicleById(Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found with id " + id));
        logger.info("Retrieved vehicle: {}", vehicle);
        return vehicle;
    }

    @Override
    public Vehicle updateVehicle(Long id, Vehicle vehicleDetails) {
        logger.info("Entering updateVehicle method with ID: {} and Vehicle details: {}", id, vehicleDetails);
        Vehicle vehicle = getVehicleById(id);
        vehicle.setVehicleNo(vehicleDetails.getVehicleNo());
        vehicle.setModel(vehicleDetails.getModel());
        vehicle.setPassingDate(vehicleDetails.getPassingDate());
        vehicle.setFitnessDate(vehicleDetails.getFitnessDate());
        vehicle.setInsuranceDate(vehicleDetails.getInsuranceDate());
        vehicle.setObsolete(vehicleDetails.isObsolete());
        // Update other fields as necessary
        Vehicle updatedVehicle = vehicleRepository.save(vehicle);
        logger.info("Updated vehicle with ID: {}", updatedVehicle.getId());
        return updatedVehicle;
    }

    @Override
    public void deleteVehicle(Long id) {
        Vehicle vehicle = getVehicleById(id);
        vehicleRepository.delete(vehicle);
        logger.info("Deleted vehicle with ID: {}", id);
    }

	@Override
	public List<Supplier> getAllSuppliers() {
        List<Supplier> suppliers = supplierRepository.findAll();
        logger.info("Retrieved {} customers", suppliers.size());
        return suppliers;
	}

	// Party methods

	@Override
	public List<Party> getAllParties() {
	    List<Party> parties = partyRepository.findAll();
	    logger.info("Retrieved {} parties", parties.size());
	    return parties;
	}

	@Override
	public Party createParty(Party party) {
	    party.setVehicleNumbers(cleanVehicleNumbers(party.getVehicleNumbers()));
	    Party savedParty = partyRepository.save(party);
	    logger.info("Created party with ID: {}", savedParty.getId());
	    return savedParty;
	}

	/**
	 * Registration numbers, tidied and de-duplicated.
	 *
	 * Upper-cased and stripped of spaces and hyphens, because "mh13 cu 4916",
	 * "MH13-CU-4916" and "MH13CU4916" are one vehicle and a list holding all three
	 * reads as a party with three lorries. The collection table keys on (party, number),
	 * so leaving the variants in would also make the same vehicle insertable twice.
	 */
	private static List<String> cleanVehicleNumbers(List<String> raw) {
	    if (raw == null) {
	        return new ArrayList<>();
	    }
	    LinkedHashSet<String> unique = new LinkedHashSet<>();
	    for (String number : raw) {
	        if (number == null) {
	            continue;
	        }
	        String tidy = number.replaceAll("[\\s-]+", "").toUpperCase();
	        if (!tidy.isEmpty()) {
	            unique.add(tidy);
	        }
	    }
	    return new ArrayList<>(unique);
	}

	@Override
	public Party getPartyById(Long id) {
	    Party party = partyRepository.findById(id)
	        .orElseThrow(() -> new ResourceNotFoundException("Party not found with id " + id));
	    logger.info("Retrieved party: {}", party);
	    return party;
	}

	@Override
	public Party updateParty(Long id, Party partyDetails) {
	    logger.info("Entering updateParty method with ID: {} and Party details: {}", id, partyDetails);
	    Party party = getPartyById(id);
	    party.setName(partyDetails.getName());
	    party.setAddress(partyDetails.getAddress());
	    party.setIsObsolete(partyDetails.getIsObsolete());
	    /*
	     * Owner, city and balance are set here too.
	     *
	     * They were not, and "update other fields as necessary" was standing in for them
	     * - so editing a party's address silently blanked its owner and city on screen
	     * while leaving them in the database, and the balance could never be corrected.
	     */
	    party.setOwner(partyDetails.getOwner());
	    party.setCity(partyDetails.getCity());
	    if (partyDetails.getBalanceAmount() != null) {
	        party.setBalanceAmount(MoneyRules.money(partyDetails.getBalanceAmount()));
	    }

	    // Replaced in place rather than reassigned: Hibernate tracks this collection, and
	    // swapping the instance makes it orphan the whole table row set.
	    party.getVehicleNumbers().clear();
	    party.getVehicleNumbers().addAll(cleanVehicleNumbers(partyDetails.getVehicleNumbers()));

	    Party updatedParty = partyRepository.save(party);
	    logger.info("Updated party with ID: {}", updatedParty.getId());
	    return updatedParty;
	}

	@Override
	public void deleteParty(Long id) {
	    Party party = getPartyById(id);
	    partyRepository.delete(party);
	    logger.info("Deleted party with ID: {}", id);
	}
}
