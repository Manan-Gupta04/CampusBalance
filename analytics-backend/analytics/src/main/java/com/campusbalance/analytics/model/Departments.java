package com.campusbalance.analytics.model;

import java.util.List;

/** Canonical spellings for the known department codes, so "cse" and "CSE" count as one department. */
public final class Departments {

    public static final List<String> KNOWN = List.of("CSE", "IT", "ECE", "EEE", "ME", "Civil");

    private Departments() {
    }

    // Known codes are matched ignoring case; anything else is kept as typed (just trimmed)
    public static String normalize(String department) {
        if (department == null) return null;
        String trimmed = department.trim();
        return KNOWN.stream().filter(d -> d.equalsIgnoreCase(trimmed)).findFirst().orElse(trimmed);
    }
}
