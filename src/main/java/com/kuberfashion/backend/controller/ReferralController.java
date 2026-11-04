package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.ReferralService;
import com.kuberfashion.backend.service.ReferralService.ReferralStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/referral")
public class ReferralController {

    @Autowired
    private ReferralService referralService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<ReferralStats>> getStats(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        ReferralStats stats = referralService.getReferralStats(user);
        return ResponseEntity.ok(ApiResponse.success("Referral stats retrieved", stats));
    }

    @GetMapping("/code")
    public ResponseEntity<ApiResponse<String>> getCode(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        String code = user.getPhone(); // use phone as referral code
        return ResponseEntity.ok(ApiResponse.success("Referral code retrieved", code));
    }

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<com.kuberfashion.backend.dto.ReferralTreeDto>> getTree(
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        try {
            com.kuberfashion.backend.dto.ReferralTreeDto tree = referralService.getReferralTree(user.getId());
            return ResponseEntity.ok(ApiResponse.success("Referral tree retrieved", tree));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/membership")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getMembership(
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Unauthorized"));
        }
        boolean isMember = referralService.isMember(user.getId());
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("isMember", isMember);
        return ResponseEntity.ok(ApiResponse.success("Membership status retrieved", result));
    }

    @GetMapping("/validate")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> validateCode(
            @RequestParam String code,
            @AuthenticationPrincipal User user) {

        // Self-referral prevention: user cannot use their own phone
        if (user != null && user.getPhone() != null && user.getPhone().equals(code.trim())) {
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("valid", false);
            result.put("error", "You cannot use your own phone number as a referral code");
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("You cannot use your own phone number as a referral code"));
        }

        java.util.Map<String, Object> result = referralService.getReferrerDetails(code);
        return ResponseEntity.ok(ApiResponse.success("Validation result", result));
    }
}
