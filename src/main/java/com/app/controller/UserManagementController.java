package com.app.controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.ReqRes;
import com.app.entity.User;
import com.app.service.UsersManagementService;

/**
 * Accounts, sign-in and the session.
 *
 * Every method answers with the status the service decided, not a blanket 200.
 *
 * It used to wrap all of them in ResponseEntity.ok(), so a wrong password, a user who does
 * not exist and a successful sign-in were all HTTP 200 with the real code buried in the
 * body as statusCode. Three consequences, all of them real: the browser's own 401 handling
 * never fired, monitoring watching status codes saw a healthy service through a wall of
 * failed logins, and any proxy or cache in front of it treated a failure as a good
 * response. getMyProfile was already doing it correctly, which is what the rest now match.
 */
@RestController
public class UserManagementController {

    private static final Logger logger = LoggerFactory.getLogger(UserManagementController.class);

    @Autowired
    private UsersManagementService usersManagementService;

    @PostMapping("/auth/register")
    public ResponseEntity<ReqRes> register(@RequestBody ReqRes reg) {
        logger.info("Register request received for username: {}", reg.getUsername());
        return reply(usersManagementService.register(reg));
    }

    @CrossOrigin(origins = "http://sohel-chicken-center.s3-website.eu-north-1.amazonaws.com")
    @PostMapping("/auth/login")
    public ResponseEntity<ReqRes> login(@RequestBody ReqRes req) {
        logger.info("Login request received for username: {}", req.getUsername());
        return reply(usersManagementService.login(req));
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<ReqRes> refreshToken(@RequestBody ReqRes req) {
        logger.info("Token refresh request received");
        return reply(usersManagementService.refreshToken(req));
    }

    @GetMapping("/admin/get-all-users")
    public ResponseEntity<ReqRes> getAllUsers() {
        return reply(usersManagementService.getAllUsers());
    }

    @GetMapping("/admin/get-users/{userId}")
    public ResponseEntity<ReqRes> getUserByID(@PathVariable Long userId) {
        return reply(usersManagementService.getUsersById(userId));
    }

    @PutMapping("/admin/update/{userId}")
    public ResponseEntity<ReqRes> updateUser(@PathVariable Long userId, @RequestBody User reqres) {
        logger.info("Request to update user with ID: {}", userId);
        return reply(usersManagementService.updateUser(userId, reqres));
    }

    @GetMapping("/adminuser/get-profile")
    public ResponseEntity<ReqRes> getMyProfile() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return reply(usersManagementService.getMyInfo(email));
    }

    @DeleteMapping("/admin/delete/{userId}")
    public ResponseEntity<ReqRes> deleteUser(@PathVariable Long userId) {
        logger.warn("Request to delete user with ID: {}", userId);
        return reply(usersManagementService.deleteUser(userId));
    }

    /**
     * Sends the response at the status it carries.
     *
     * Falls back to 500 for a zero or nonsensical code rather than letting
     * ResponseEntity.status(0) throw: a service that forgot to set one is a bug, and the
     * caller should see a server error, not a stack trace from inside Spring.
     */
    private static ResponseEntity<ReqRes> reply(ReqRes response) {
        int code = response.getStatusCode();
        if (code < 100 || code > 599) {
            logger.error("Service returned an unusable status code {}; reporting 500", code);
            response.setStatusCode(500);
            code = 500;
        }
        return ResponseEntity.status(code).body(response);
    }
}
