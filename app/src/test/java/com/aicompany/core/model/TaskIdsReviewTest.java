package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskIdsReviewTest {

    @Test
    void reviewTaskIdsAreDistinctFromTheAgentsWorkTask() {
        assertEquals("M-1-QA-REVIEW", TaskIds.reviewTask("M-1", "qa", 0));
        assertEquals("M-1-QA-REVIEW-R2", TaskIds.reviewTask("M-1", "qa", 2));
        assertEquals(2, TaskIds.roundOf("M-1-QA-REVIEW-R2"));
    }
}
