package com.campusbalance.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for every account-creating endpoint (student signup, first admin, faculty).
 * Only these fields are accepted, so a client can't smuggle in a role, XP, an existing id, etc.
 */
public record AccountRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 40, message = "Username must be 3-40 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "Username can only use letters, numbers, dots, dashes and underscores")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String password,

        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name is too long")
        String name,

        @Size(max = 40, message = "Department is too long")
        String department,

        @Size(max = 10, message = "Batch year is too long")
        String batchYear
) {}
