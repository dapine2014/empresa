package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {}
