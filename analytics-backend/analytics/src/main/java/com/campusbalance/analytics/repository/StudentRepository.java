package com.campusbalance.analytics.repository;

import com.campusbalance.analytics.model.Student;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends MongoRepository<Student, String> {
    Student findByName(String name);
    // This MUST match the variable name in your Student class!
    Student findByUsername(String username);
    List<Student> findByRole(String role);
    List<Student> findByRoleAndDepartment(String role, String department);
}
