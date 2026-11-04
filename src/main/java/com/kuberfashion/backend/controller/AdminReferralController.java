package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.repository.ReferralRelationRepository;
import com.kuberfashion.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/referrals")
public class AdminReferralController {

    @Autowired
    private ReferralRelationRepository referralRepo;
    @Autowired
    private UserRepository userRepo;

    public static class TreeNode {
        public Long id;
        public String firstName;
        public String lastName;
        public String fullName;
        public String email;
        public String phone;
        public String role;
        public String signupDate;
        public String status;
        public Integer coupons;
        public Integer totalReferrals;
        public Integer level; // depth level in tree
        public List<TreeNode> children = new ArrayList<>();
    }

    @GetMapping("/tree")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TreeNode>> getTree(@RequestParam(required = false) String phone) {
        Optional<User> rootOpt;
        if (phone != null && !phone.isBlank()) {
            rootOpt = userRepo.findByPhone(phone);
        } else {
            // default: pick the oldest user as root to avoid empty
            rootOpt = userRepo.findAll().stream().min(Comparator.comparing(User::getId));
        }
        if (rootOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success("No root user found", null));
        }
        User root = rootOpt.get();
        TreeNode rootNode = buildTree(root, 0);
        return ResponseEntity.ok(ApiResponse.success("Referral tree fetched", rootNode));
    }

    // New endpoint: Get ALL root users and their complete trees
    // Roots are ONLY users who are self-referential (Genesis roots) - NOT all users
    @GetMapping("/full-tree")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<TreeNode>>> getFullTree() {
        // Get all referral relations
        var allRelations = referralRepo.findAll();

        if (allRelations.isEmpty()) {
            // No referral network yet
            return ResponseEntity.ok(ApiResponse.success("Referral tree fetched", new ArrayList<>()));
        }

        // Self-referential users (Genesis roots - user_id = parent_id) are the ONLY
        // roots
        // These are users who used the Genesis code (kuberfashion2025)
        Set<Long> genesisRootIds = allRelations.stream()
                .filter(rr -> rr.getUser().getId().equals(rr.getParent().getId()))
                .map(rr -> rr.getUser().getId())
                .collect(Collectors.toSet());

        // Only Genesis roots are root nodes in the tree
        List<User> rootUsers = userRepo.findAllById(genesisRootIds).stream()
                .sorted(Comparator.comparing(User::getId))
                .collect(Collectors.toList());

        List<TreeNode> trees = rootUsers.stream()
                .map(u -> buildTree(u, 0))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success("Full referral tree fetched", trees));
    }

    /**
     * Optimized tree building using level-by-level bulk fetching.
     * Uses exactly 6 queries regardless of tree size.
     */
    private TreeNode buildTree(User rootUser, int startDepth) {
        TreeNode root = createNode(rootUser, startDepth);

        // Level-by-level fetching
        Map<Long, TreeNode> nodeMap = new HashMap<>();
        nodeMap.put(rootUser.getId(), root);

        List<Long> currentLevelIds = List.of(rootUser.getId());
        final int MAX_DEPTH = 6;

        for (int level = startDepth + 1; level <= MAX_DEPTH; level++) {
            if (currentLevelIds.isEmpty())
                break;

            // Fetch all children of current level in ONE query
            var relations = referralRepo.findByParentIdIn(currentLevelIds);
            if (relations.isEmpty())
                break;

            List<Long> nextLevelIds = new ArrayList<>();

            for (var rr : relations) {
                User child = rr.getUser();
                Long parentId = rr.getParent().getId();

                // Skip self-referential entries (Genesis roots refer to themselves)
                if (child.getId().equals(parentId)) {
                    continue;
                }

                TreeNode childNode = createNode(child, level);

                // Attach to parent
                TreeNode parentNode = nodeMap.get(parentId);
                if (parentNode != null) {
                    parentNode.children.add(childNode);
                    parentNode.totalReferrals = parentNode.children.size();
                }

                // Add to map for next level
                nodeMap.put(child.getId(), childNode);
                nextLevelIds.add(child.getId());
            }

            currentLevelIds = nextLevelIds;
        }

        return root;
    }

    private TreeNode createNode(User user, int depth) {
        TreeNode node = new TreeNode();
        node.id = user.getId();
        node.firstName = user.getFirstName();
        node.lastName = user.getLastName();
        node.fullName = user.getFullName();
        node.email = user.getEmail();
        node.phone = user.getPhone();
        node.role = user.getRole().name();
        node.signupDate = user.getCreatedAt() != null
                ? user.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null;
        node.status = user.isEnabled() ? "active" : "inactive";
        node.coupons = user.getKuberCoupons();
        node.level = depth;
        node.totalReferrals = 0;
        return node;
    }
}
