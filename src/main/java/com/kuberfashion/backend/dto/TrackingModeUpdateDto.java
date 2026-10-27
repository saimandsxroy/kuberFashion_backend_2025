package com.kuberfashion.backend.dto;

import com.kuberfashion.backend.entity.Product;
import jakarta.validation.constraints.NotNull;

public class TrackingModeUpdateDto {

    @NotNull(message = "Tracking mode is required")
    private Product.InventoryTrackingMode trackingMode;

    public TrackingModeUpdateDto() {
    }

    public TrackingModeUpdateDto(Product.InventoryTrackingMode trackingMode) {
        this.trackingMode = trackingMode;
    }

    public Product.InventoryTrackingMode getTrackingMode() {
        return trackingMode;
    }

    public void setTrackingMode(Product.InventoryTrackingMode trackingMode) {
        this.trackingMode = trackingMode;
    }
}
