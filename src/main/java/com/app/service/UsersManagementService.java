package com.app.service;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;

import io.jsonwebtoken.JwtException;
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

    /** Roles this application recognises. Anything else is rejected. */
    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "USER", "DRIVER");

    @Transactional
    public ReqRes register(ReqRes registrationRequest) {
        ReqRes resp = new ReqRes();
        try {
            // The role arrives from the client, so it is validated against a
            // fixed set rather than stored as given. Combined with the ADMIN
            // requirement on POST /auth/register, this closes the path by which
            // an unauthenticated caller could create an ADMIN account.
            String role = registrationRequest.getRole();
            if (role == null || !ALLOWED_ROLES.contains(role)) {
                resp.setStatusCode(400);
                resp.setError("Role must be one of " + ALLOWED_ROLES);
                logger.warn("Rejected registration for {}: invalid role {}",
                        registrationRequest.getUsername(), role);
                return resp;
            }

            /*
             * Refuse a username that already exists.
             *
             * There is no unique index on user.username, so nothing in the database stopped
             * this - and production already carries the consequence: mulanisam55@gmail.com
             * exists twice, once as USER and once as ADMIN. findByUsername returns whichever
             * row the database hands back first, so which role that person gets on sign-in is
             * decided by row order rather than by intent.
             *
             * This closes the door on new duplicates. It does not fix the existing one, and a
             * unique index cannot be added until that row is resolved - which of the two to
             * keep is not a decision to guess at.
             */
            String username = registrationRequest.getUsername();
            if (username == null || username.isBlank()) {
                resp.setStatusCode(400);
                resp.setError("A username is required.");
                return resp;
            }
            if (usersRepo.findByUsername(username).isPresent()) {
                logger.warn("Registration refused: {} already exists", username);
                resp.setStatusCode(409);
                resp.setError("That username is already registered.");
                return resp;
            }

            User ourUser = new User();
            ourUser.setUsername(registrationRequest.getUsername());
            ourUser.setRole(role);
            ourUser.setName(registrationRequest.getName());
            ourUser.setPassword(passwordEncoder.encode(registrationRequest.getPassword()));
            User ourUsersResult = usersRepo.save(ourUser);
            if (ourUsersResult.getId() > 0) {
                // The saved entity is deliberately not returned: it carries the
                // bcrypt password hash, which was previously echoed straight
                // back to the caller in the response body.
                resp.setMessage("User Saved Successfully");
                resp.setStatusCode(200);
                logger.info("User registered successfully: {}", ourUser.getUsername());
            }
        } catch (DataIntegrityViolationException e) {
            // A username already taken. 409 rather than 500, and named as such: the
            // driver's own message here is "Duplicate entry '...' for key 'user.UK_...'",
            // which was being returned to the caller verbatim.
            logger.warn("Registration refused: {} is already taken", registrationRequest.getUsername());
            resp.setStatusCode(409);
            resp.setError("That username is already registered.");
        } catch (Exception e) {
            logger.error("Error registering user {}", registrationRequest.getUsername(), e);
            resp.setStatusCode(500);
            resp.setError("The account could not be created. Please try again.");
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
        } catch (AuthenticationException | NoSuchElementException e) {
            /*
             * 401, not 500. A wrong password is not a server fault, and reporting it as one
             * meant every failed sign-in looked like an outage in the logs and to any
             * monitoring watching status codes.
             *
             * The message is deliberately the same whether the username does not exist or
             * the password is wrong: telling them apart confirms which usernames are real.
             */
            logger.warn("Failed login attempt for username: {}", loginRequest.getUsername());
            response.setStatusCode(401);
            response.setMessage("Those credentials were not accepted.");
        } catch (Exception e) {
            // Full stack trace, and nothing of it returned: this used to put e.getMessage()
            // in the body, which is how a JWT library's internals reached the login screen.
            logger.error("Unexpected failure logging in {}", loginRequest.getUsername(), e);
            response.setStatusCode(500);
            response.setMessage("Sign-in could not be completed. Please try again.");
        }
        return response;
    }

    public ReqRes refreshToken(ReqRes refreshTokenRequest) {
        ReqRes response = new ReqRes();

        if (refreshTokenRequest.getToken() == null || refreshTokenRequest.getToken().isBlank()) {
            response.setStatusCode(400);
            response.setMessage("A refresh token is required.");
            return response;
        }

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
            } else {
                /*
                 * This branch did not exist, and its absence was a silent failure: an
                 * expired-but-well-formed token fell straight through the if, nothing was
                 * set on the response, and the caller received {"statusCode":0} with no
                 * message and an HTTP 200. The client had no way to tell that from success
                 * except that no token came back.
                 */
                logger.warn("Refresh refused for {}: token is no longer valid", ourEmail);
                response.setStatusCode(401);
                response.setMessage("Your session has expired. Please sign in again.");
            }
        } catch (NoSuchElementException e) {
            logger.warn("Refresh refused: the token names a user who no longer exists");
            response.setStatusCode(401);
            response.setMessage("Your session has expired. Please sign in again.");
        } catch (JwtException | IllegalArgumentException e) {
            // A malformed or tampered token. The library's own message names its internals
            // - "Compact JWSs must contain exactly 2 period characters" reached the client
            // before this - and says nothing a caller can act on.
            logger.warn("Refresh refused: {}", e.getMessage());
            response.setStatusCode(401);
            response.setMessage("Your session has expired. Please sign in again.");
        } catch (Exception e) {
            logger.error("Unexpected failure refreshing a token", e);
            response.setStatusCode(500);
            response.setMessage("The session could not be renewed. Please sign in again.");
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
