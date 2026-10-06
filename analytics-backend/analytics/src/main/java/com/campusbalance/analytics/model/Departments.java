package com.campusbalance.analytics.model;

import java.util.List;

/**
 * The fixed list of departments. Students and faculty pick from it (a dropdown, checked again on
 * the server), so "cse" and "CSE" can never become two different departments.
 */
public final class Departments {

    public record Department(String code, String name) {}

    public static final List<Department> ALL = List.of(
            new Department("CSE", "Computer Science & Engineering"),
            new Department("IT", "Information Technology"),
            new Department("ECE", "Electronics & Communication Engineering"),
            new Department("EEE", "Electrical & Electronics Engineering"),
            new Department("ME", "Mechanical Engineering"),
            new Department("Civil", "Civil Engineering"));

    public static final List<String> KNOWN = ALL.stream().map(Department::code).toList();

    private Departments() {
    }

    // Known codes are matched ignoring case; anything else is kept as typed (just trimmed)
    public static String normalize(String department) {
        if (department == null) return null;
        String trimmed = department.trim();
        return KNOWN.stream().filter(d -> d.equalsIgnoreCase(trimmed)).findFirst().orElse(trimmed);
    }

    public static boolean isKnown(String department) {
        return department != null && KNOWN.contains(normalize(department));
    }
}
