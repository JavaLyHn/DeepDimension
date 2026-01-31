package com.lyhn.deepdimension.repository;

import com.lyhn.deepdimension.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// Long指User实体类中主键字段id的数据类型
public interface UserRepository extends JpaRepository<User, Long> {
    // Spring Data JPA会根据方法名自动实现查询逻辑
    Optional<User> findByUsername(String username);
}
