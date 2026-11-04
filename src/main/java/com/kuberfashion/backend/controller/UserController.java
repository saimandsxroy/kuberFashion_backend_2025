package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.dto.UserResponseDto;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @Autowired
    private UserService userService;
    
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponseDto>> getUserProfile(@AuthenticationPrincipal User user) {
        UserResponseDto userResponse = new UserResponseDto(user);
        return ResponseEntity.ok(ApiResponse.success("User profile retrieved successfully", userResponse));
    }
    
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponseDto>> updateUserProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateProfileRequest request) {
        User updatedUser = userService.updateUser(user.getId(), request.getFirstName(), 
                request.getLastName(), request.getPhone());
        UserResponseDto userResponse = new UserResponseDto(updatedUser);
        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", userResponse));
    }
    
    @PutMapping("/profile/password")
    public ResponseEntity<ApiResponse<String>> changePassword(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user.getId(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully"));
    }
    
    @PutMapping("/change-password")
    public ResponseEntity<ApiResponse<String>> changePasswordAlternate(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user.getId(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully"));
    }
    
    @DeleteMapping("/profile")
    public ResponseEntity<ApiResponse<String>> deleteAccount(@AuthenticationPrincipal User user) {
        userService.deleteUser(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Account deleted successfully"));
    }
    
    // Admin endpoints
    @GetMapping("/admin/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponseDto>> getUserById(@PathVariable Long userId) {
        User user = userService.getUserById(userId);
        UserResponseDto userResponse = new UserResponseDto(user);
        return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", userResponse));
    }
    
    @PutMapping("/admin/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> updateUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, Boolean> request) {
        boolean enabled = request.get("enabled");
        userService.updateUserStatus(userId, enabled);
        String message = enabled ? "User enabled successfully" : "User disabled successfully";
        return ResponseEntity.ok(ApiResponse.success(message));
    }
    
    // DTOs for requests
    public static class UpdateProfileRequest {
        private String firstName;
        private String lastName;
        private String phone;
        
        // Getters and setters
        public String getFirstName() {
            return firstName;
        }
        
        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }
        
        public String getLastName() {
            return lastName;
        }
        
        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
        
        public String getPhone() {
            return phone;
        }
        
        public void setPhone(String phone) {
            this.phone = phone;
        }
    }
    
    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;
        
        // Getters and setters
        public String getCurrentPassword() {
            return currentPassword;
        }
        
        public void setCurrentPassword(String currentPassword) {
            this.currentPassword = currentPassword;
        }
        
        public String getNewPassword() {
            return newPassword;
        }
        
        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }
}
