package com.campusbalance.analytics.repository;

import com.campusbalance.analytics.model.Subject;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SubjectRepository extends MongoRepository<Subject, String> {
    Subject findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(String subjectName, String username, int semesterNumber);
    List<Subject> findByUsernameAndSemesterNumber(String username, int semesterNumber);
    List<Subject> findByDepartment(String department);
}