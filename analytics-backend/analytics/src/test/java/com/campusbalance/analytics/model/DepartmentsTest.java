package com.campusbalance.analytics.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DepartmentsTest {

    @Test
    void knownCodesUseOneSpelling() {
        assertThat(Departments.normalize("cse")).isEqualTo("CSE");
        assertThat(Departments.normalize(" it ")).isEqualTo("IT");
        assertThat(Departments.normalize("CIVIL")).isEqualTo("Civil");
    }

    @Test
    void unknownDepartmentsAreKeptAsTyped() {
        assertThat(Departments.normalize(" Administration ")).isEqualTo("Administration");
        assertThat(Departments.normalize(null)).isNull();
    }
}
