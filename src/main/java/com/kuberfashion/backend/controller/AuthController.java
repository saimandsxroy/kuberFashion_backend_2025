package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.*;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.security.JwtTokenProvider;
import com.kuberfashion.backend.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    
    @Autowired
    private AuthenticationManager authenticationManager;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private JwtTokenProvider tokenProvider;
    
    
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<JwtAuthenticationResponse>> authenticateUser(@Valid @RequestBody UserLoginDto loginDto) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginDto.getEmail(),
                        loginDto.getPassword()
                )
        );
        
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = tokenProvider.generateToken(authentication);
        
        UserResponseDto userDto = userService.getUserByEmail(loginDto.getEmail());
        JwtAuthenticationResponse response = new JwtAuthenticationResponse(jwt, userDto);
        
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }
    
    // Phone + Password Login
    @PostMapping("/phone/login")
    public ResponseEntity<ApiResponse<JwtAuthenticationResponse>> phoneLogin(@Valid @RequestBody PhoneLoginDto loginDto) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginDto.getPhone(),
                            loginDto.getPassword()
                    )
            );
            
            SecurityContextHolder.getContext().setAuthentication(authentication);
            String jwt = tokenProvider.generateToken(authentication);
            
            UserResponseDto userDto = new UserResponseDto((User) authentication.getPrincipal());
            JwtAuthenticationResponse response = new JwtAuthenticationResponse(jwt, userDto);
            
            return ResponseEntity.ok(ApiResponse.success("Login successful", response));
        } catch (Exception e) {
            return new ResponseEntity<>(ApiResponse.error("Invalid phone or password"), HttpStatus.UNAUTHORIZED);
        }
    }
    
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<JwtAuthenticationResponse>> registerUser(@Valid @RequestBody UserRegistrationDto registrationDto) {
        try {
            // Register user
            UserResponseDto userDto = userService.registerUser(registrationDto);
            
            // Authenticate and generate JWT token
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    registrationDto.getEmail(),
                    registrationDto.getPassword()
                )
            );
            
            SecurityContextHolder.getContext().setAuthentication(authentication);
            
            // Generate JWT token
            String jwt = tokenProvider.generateToken(authentication);
            
            // Create response with token
            JwtAuthenticationResponse response = new JwtAuthenticationResponse(jwt, userDto);
            
            return new ResponseEntity<>(
                ApiResponse.success("Registration successful! You can now login.", response), 
                HttpStatus.CREATED
            );
        } catch (Exception e) {
            logger.error("Registration failed: {}", e.getMessage(), e);
            return new ResponseEntity<>(
                ApiResponse.error("Registration failed: " + e.getMessage()), 
                HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }
    
    
    
    @PostMapping("/create-admin")
    public ResponseEntity<ApiResponse<String>> createAdminUser(@RequestParam String email) {
        logger.info("👑 POST /api/auth/create-admin - Promoting user to admin: {}", email);
        try {
            boolean updated = userService.updateUserRoleToAdmin(email);
            if (updated) {
                logger.info("✅ User role updated to ADMIN: {}", email);
                return ResponseEntity.ok(ApiResponse.success("User role updated to ADMIN", email));
            } else {
                logger.warn("⚠️ User not found with email: {}", email);
                return new ResponseEntity<>(ApiResponse.error("User not found with email: " + email), HttpStatus.NOT_FOUND);
            }
        } catch (Exception e) {
            logger.error("❌ Failed to update role: {}", e.getMessage(), e);
            return new ResponseEntity<>(ApiResponse.error("Failed to update role: " + e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    @PostMapping("/check-email")
    public ResponseEntity<ApiResponse<Boolean>> checkEmailAvailability(@RequestParam String email) {
        boolean isAvailable = !userService.existsByEmail(email);
        return ResponseEntity.ok(ApiResponse.success("Email availability checked", isAvailable));
    }
    
    @PostMapping("/check-phone")
    public ResponseEntity<ApiResponse<Boolean>> checkPhoneAvailability(@RequestParam String phone) {
        boolean isAvailable = !userService.existsByPhone(phone);
        return ResponseEntity.ok(ApiResponse.success("Phone availability checked", isAvailable));
    }
    
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponseDto>> getCurrentUser(@AuthenticationPrincipal User user) {
        // Check if user is authenticated
        if (user == null) {
            logger.warn("⚠️ GET /api/auth/me - User not authenticated or token expired");
            return new ResponseEntity<>(
                ApiResponse.error("User not authenticated or token expired"), 
                HttpStatus.UNAUTHORIZED
            );
        }
        
        logger.info("✅ GET /api/auth/me - User: {} (ID: {})", user.getEmail(), user.getId());
        UserResponseDto userResponse = new UserResponseDto(user);
        return ResponseEntity.ok(ApiResponse.success("Current user retrieved successfully", userResponse));
    }
}
