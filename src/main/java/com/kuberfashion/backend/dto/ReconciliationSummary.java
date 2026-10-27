package com.kuberfashion.backend.dto;

import java.util.ArrayList;
import java.util.List;

public class ReconciliationSummary {

    private int candidateCount = 0;
    private int confirmedCount = 0;
    private int expiredCount = 0;
    private int skippedCount = 0;
    private List<String> details = new ArrayList<>();

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public int getConfirmedCount() {
        return confirmedCount;
    }

    public int getExpiredCount() {
        return expiredCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public List<String> getDetails() {
        return details;
    }

    public void recordConfirmed(String orderId, String reason) {
        this.confirmedCount++;
        this.details.add("CONFIRMED [" + orderId + "]: " + reason);
    }

    public void recordExpired(String orderId, String reason) {
        this.expiredCount++;
        this.details.add("EXPIRED [" + orderId + "]: " + reason);
    }

    public void recordSkipped(String orderId, String reason) {
        this.skippedCount++;
        this.details.add("SKIPPED [" + orderId + "]: " + reason);
    }
}
