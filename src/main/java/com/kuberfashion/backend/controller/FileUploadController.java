package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.service.FileStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(name = "cloudflare.r2.enabled", havingValue = "true", matchIfMissing = false)
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileStorageService fileStorageService;

    // Allowed file types
    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");

    // Max file size: 5MB
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /**
     * Upload product image (Admin only)
     */
    @PostMapping("/upload/product")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadProductImage(
            @RequestParam("file") MultipartFile file) {

        return uploadFile(file, "products");
    }

    /**
     * Upload category image (Admin only)
     */
    @PostMapping("/upload/category")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadCategoryImage(
            @RequestParam("file") MultipartFile file) {

        return uploadFile(file, "categories");
    }

    /**
     * Upload user avatar (Authenticated users)
     */
    @PostMapping("/upload/avatar")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadUserAvatar(
            @RequestParam("file") MultipartFile file) {

        return uploadFile(file, "users");
    }

    /**
     * Upload general image (Admin only)
     */
    @PostMapping("/upload/general")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadGeneralImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "general") String folder) {

        return uploadFile(file, folder);
    }

    /**
     * Delete file (Admin only)
     */
    @DeleteMapping("/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> deleteFile(@RequestParam("url") String fileUrl) {
        try {
            fileStorageService.deleteFile(fileUrl);
            return ResponseEntity.ok(ApiResponse.success("File deleted successfully", "File removed from storage"));
        } catch (Exception e) {
            logger.error("Error deleting file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to delete file: " + e.getMessage()));
        }
    }

    /**
     * Check if file exists
     */
    @GetMapping("/exists")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkFileExists(@RequestParam("url") String fileUrl) {
        try {
            boolean exists = fileStorageService.fileExists(fileUrl);
            Map<String, Boolean> result = new HashMap<>();
            result.put("exists", exists);
            return ResponseEntity.ok(ApiResponse.success("File existence checked", result));
        } catch (Exception e) {
            logger.error("Error checking file existence: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to check file existence: " + e.getMessage()));
        }
    }

    /**
     * Get file metadata
     */
    @GetMapping("/metadata")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FileStorageService.FileMetadata>> getFileMetadata(
            @RequestParam("url") String fileUrl) {
        try {
            FileStorageService.FileMetadata metadata = fileStorageService.getFileMetadata(fileUrl);
            if (metadata != null) {
                return ResponseEntity.ok(ApiResponse.success("File metadata retrieved", metadata));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("File not found"));
            }
        } catch (Exception e) {
            logger.error("Error getting file metadata: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get file metadata: " + e.getMessage()));
        }
    }

    private ResponseEntity<ApiResponse<Map<String, String>>> uploadFile(MultipartFile file, String folder) {
        try {
            // Validate file
            String validationError = validateFile(file);
            if (validationError != null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(validationError));
            }

            // Upload file
            String fileUrl = fileStorageService.uploadFile(file, folder);

            // Return response
            Map<String, String> result = new HashMap<>();
            result.put("url", fileUrl);
            result.put("filename", file.getOriginalFilename());
            result.put("folder", folder);
            result.put("size", String.valueOf(file.getSize()));

            return ResponseEntity.ok(ApiResponse.success("File uploaded successfully", result));

        } catch (Exception e) {
            logger.error("Error uploading file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("File upload failed: " + e.getMessage()));
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "File is required";
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return "File size must be less than 5MB";
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            return "Only image files (JPEG, PNG, WebP, GIF) are allowed";
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.trim().isEmpty()) {
            return "Filename is required";
        }

        return null; // No validation errors
    }
}
