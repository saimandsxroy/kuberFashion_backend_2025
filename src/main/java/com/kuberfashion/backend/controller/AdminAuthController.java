package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.*;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.security.JwtTokenProvider;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<JwtAuthenticationResponse>> adminLogin(@Valid @RequestBody UserLoginDto loginDto) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginDto.getEmail(),
                            loginDto.getPassword()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            User principal = (User) authentication.getPrincipal();
            
            // Check role properly
            if (principal.getRole() != User.Role.ADMIN) {
                return new ResponseEntity<>(
                    ApiResponse.error("Access denied. Admin privileges required."), 
                    HttpStatus.FORBIDDEN
                );
            }

            // Generate token
            String jwt = tokenProvider.generateToken(authentication);
            UserResponseDto userDto = new UserResponseDto(principal);
            JwtAuthenticationResponse response = new JwtAuthenticationResponse(jwt, userDto);
            
            return ResponseEntity.ok(ApiResponse.success("Admin login successful", response));
        } catch (Exception e) {
            return new ResponseEntity<>(
                ApiResponse.error("Invalid credentials"), 
                HttpStatus.UNAUTHORIZED
            );
        }
    }
}
