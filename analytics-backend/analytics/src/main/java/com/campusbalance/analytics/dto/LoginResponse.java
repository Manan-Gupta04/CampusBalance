package com.campusbalance.analytics.dto;

/** What the frontend gets after a successful login: the bearer token plus enough to route by role. */
public record LoginResponse(String token, String username, String name, String role) {}
