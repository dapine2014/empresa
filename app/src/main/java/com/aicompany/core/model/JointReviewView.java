package com.aicompany.core.model;

import com.aicompany.core.agent.model.JointReview;

import java.util.List;

/** Revisión conjunta (spec 2026-10-15): una ronda de Neo + Aria tal como la ve el fundador. */
public record JointReviewView(int number, int round, int specVersion, int designVersion, String status,
                              List<JointReview.Contradiction> contradictions, List<JointReview.Decision> decisions,
                              String detail, String at) {
}
