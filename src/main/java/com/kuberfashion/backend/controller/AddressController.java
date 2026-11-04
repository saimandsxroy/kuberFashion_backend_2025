package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.entity.Address;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Address>>> getUserAddresses(@AuthenticationPrincipal User user) {
        List<Address> addresses = addressService.getUserAddresses(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Addresses retrieved successfully", addresses));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Address>> getAddress(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        Address address = addressService.getAddressById(id, user.getId());
        return ResponseEntity.ok(ApiResponse.success("Address retrieved successfully", address));
    }

    @GetMapping("/default")
    public ResponseEntity<ApiResponse<Address>> getDefaultAddress(@AuthenticationPrincipal User user) {
        Address address = addressService.getDefaultAddress(user.getId());
        return ResponseEntity.ok(ApiResponse.success("Default address retrieved", address));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Address>> createAddress(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody Address address) {
        Address created = addressService.createAddress(user.getId(), address);
        return ResponseEntity.ok(ApiResponse.success("Address created successfully", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Address>> updateAddress(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @Valid @RequestBody Address address) {
        Address updated = addressService.updateAddress(id, user.getId(), address);
        return ResponseEntity.ok(ApiResponse.success("Address updated successfully", updated));
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<ApiResponse<String>> setAsDefault(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        addressService.setAsDefault(id, user.getId());
        return ResponseEntity.ok(ApiResponse.success("Address set as default"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> deleteAddress(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        addressService.deleteAddress(id, user.getId());
        return ResponseEntity.ok(ApiResponse.success("Address deleted successfully"));
    }
}
