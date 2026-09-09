package com.app.service;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.ReqRes;
import com.app.entity.User;
import com.app.repository.UserRepository;

@Service
public class UsersManagementService {

    private static final Logger logger = LoggerFactory.getLogger(UsersManagementService.class);

    @Autowired
    private UserRepository usersRepo;
    @Autowired
    private JWTUtils jwtUtils;
    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Transactional
    public ReqRes register(ReqRes registrationRequest) {
        ReqRes resp = new ReqRes();
        try {
            User ourUser = new User();
            ourUser.setUsername(registrationRequest.getUsername());
            ourUser.setRole(registrationRequest.getRole());
            ourUser.setName(registrationRequest.getName());
            ourUser.setPassword(passwordEncoder.encode(registrationRequest.getPassword()));
            User ourUsersResult = usersRepo.save(ourUser);
            if (ourUsersResult.getId() > 0) {
                resp.setUser(ourUsersResult);
                resp.setMessage("User Saved Successfully");
                resp.setStatusCode(200);
                logger.info("User registered successfully: {}", ourUser.getUsername());
            }
        } catch (Exception e) {
            logger.error("Error registering user: {}", e.getMessage());
            resp.setStatusCode(500);
            resp.setError(e.getMessage());
        }
        return resp;
    }

    public ReqRes login(ReqRes loginRequest) {
        ReqRes response = new ReqRes();
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword()));
            var user = usersRepo.findByUsername(loginRequest.getUsername()).orElseThrow();
            var jwt = jwtUtils.generateToken(user);
            var refreshToken = jwtUtils.generateRefreshToken(new HashMap<>(), user);
            response.setStatusCode(200);
            response.setToken(jwt);
            response.setRole(user.getRole());
            response.setName(user.getName());
            response.setRefreshToken(refreshToken);
            response.setExpirationTime("24Hrs");
            response.setMessage("Successfully Logged In");
            logger.info("User logged in successfully: {}", user.getUsername());
        } catch (Exception e) {
            logger.error("Error logging in user: {}", e.getMessage());
            response.setStatusCode(500);
            response.setMessage(e.getMessage());
        }
        return response;
    }

    public ReqRes refreshToken(ReqRes refreshTokenRequest) {
        ReqRes response = new ReqRes();
        try {
            String ourEmail = jwtUtils.extractUsername(refreshTokenRequest.getToken());
            User users = usersRepo.findByUsername(ourEmail).orElseThrow();
            if (jwtUtils.isTokenValid(refreshTokenRequest.getToken(), users)) {
                var jwt = jwtUtils.generateToken(users);
                response.setStatusCode(200);
                response.setToken(jwt);
                response.setRefreshToken(refreshTokenRequest.getToken());
                response.setExpirationTime("24Hr");
                response.setMessage("Successfully Refreshed Token");
                logger.info("Token refreshed successfully for user: {}", ourEmail);
            }
        } catch (Exception e) {
            logger.error("Error refreshing token: {}", e.getMessage());
            response.setStatusCode(500);
            response.setMessage(e.getMessage());
        }
        return response;
    }

    public ReqRes getAllUsers() {
        ReqRes reqRes = new ReqRes();
        try {
            List<User> result = usersRepo.findAll();
            if (!result.isEmpty()) {
                reqRes.setUserList(result);
                reqRes.setStatusCode(200);
                reqRes.setMessage("Successful");
                logger.info("Retrieved all users successfully");
            } else {
                reqRes.setStatusCode(404);
                reqRes.setMessage("No users found");
                logger.warn("No users found");
            }
        } catch (Exception e) {
            logger.error("Error retrieving all users: {}", e.getMessage());
            reqRes.setStatusCode(500);
            reqRes.setMessage("Error occurred: " + e.getMessage());
        }
        return reqRes;
    }

    public ReqRes getUsersById(Long id) {
        ReqRes reqRes = new ReqRes();
        try {
            User usersById = usersRepo.findById(id).orElseThrow(() -> new RuntimeException("User Not found"));
            reqRes.setUser(usersById);
            reqRes.setStatusCode(200);
            reqRes.setMessage("Users with id '" + id + "' found successfully");
            logger.info("User with ID {} found successfully", id);
        } catch (Exception e) {
            logger.error("Error retrieving user by ID {}: {}", id, e.getMessage());
            reqRes.setStatusCode(500);
            reqRes.setMessage("Error occurred: " + e.getMessage());
        }
        return reqRes;
    }

    @Transactional
    public ReqRes deleteUser(Long userId) {
        ReqRes reqRes = new ReqRes();
        try {
            Optional<User> userOptional = usersRepo.findById(userId);
            if (userOptional.isPresent()) {
                usersRepo.deleteById(userId);
                reqRes.setStatusCode(200);
                reqRes.setMessage("User deleted successfully");
                logger.info("User with ID {} deleted successfully", userId);
            } else {
                reqRes.setStatusCode(404);
                reqRes.setMessage("User not found for deletion");
                logger.warn("User with ID {} not found for deletion", userId);
            }
        } catch (Exception e) {
            logger.error("Error deleting user with ID {}: {}", userId, e.getMessage());
            reqRes.setStatusCode(500);
            reqRes.setMessage("Error occurred while deleting user: " + e.getMessage());
        }
        return reqRes;
    }

    @Transactional
    public ReqRes updateUser(Long userId, User updatedUser) {
        ReqRes reqRes = new ReqRes();
        try {
            Optional<User> userOptional = usersRepo.findById(userId);
            if (userOptional.isPresent()) {
                User existingUser = userOptional.get();
                existingUser.setUsername(updatedUser.getUsername());
                existingUser.setRole(updatedUser.getRole());

                if (updatedUser.getPassword() != null && !updatedUser.getPassword().isEmpty()) {
                    existingUser.setPassword(passwordEncoder.encode(updatedUser.getPassword()));
                }

                User savedUser = usersRepo.save(existingUser);
                reqRes.setUser(savedUser);
                reqRes.setStatusCode(200);
                reqRes.setMessage("User updated successfully");
                logger.info("User with ID {} updated successfully", userId);
            } else {
                reqRes.setStatusCode(404);
                reqRes.setMessage("User not found for update");
                logger.warn("User with ID {} not found for update", userId);
            }
        } catch (Exception e) {
            logger.error("Error updating user with ID {}: {}", userId, e.getMessage());
            reqRes.setStatusCode(500);
            reqRes.setMessage("Error occurred while updating user: " + e.getMessage());
        }
        return reqRes;
    }

    public ReqRes getMyInfo(String username) {
        ReqRes reqRes = new ReqRes();
        try {
            Optional<User> userOptional = usersRepo.findByUsername(username);
            if (userOptional.isPresent()) {
                reqRes.setUser(userOptional.get());
                reqRes.setStatusCode(200);
                reqRes.setMessage("Successful");
                logger.info("Retrieved profile for username: {}", username);
            } else {
                reqRes.setStatusCode(404);
                reqRes.setMessage("User not found");
                logger.warn("User with username {} not found", username);
            }
        } catch (Exception e) {
            logger.error("Error retrieving profile for username {}: {}", username, e.getMessage());
            reqRes.setStatusCode(500);
            reqRes.setMessage("Error occurred while getting user info: " + e.getMessage());
        }
        return reqRes;
    }
}
