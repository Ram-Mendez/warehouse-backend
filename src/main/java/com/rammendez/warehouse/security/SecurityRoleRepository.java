package com.rammendez.warehouse.security;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SecurityRoleRepository extends JpaRepository<SecurityRole, Long> {
    List<SecurityRole> findAllByOrderByCode();
}
