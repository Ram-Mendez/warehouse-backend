package com.rammendez.warehouse.security;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface SecurityRepository extends JpaRepository<SecurityUser, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from SecurityUser u where u.username = :username")
    Optional<SecurityUser> findAndLockByUsername(String username);

    default Optional<UserAccount> findAndLockUserByUsername(String username) {
        return findAndLockByUsername(username).map(SecurityUser::toAccount);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from SecurityUser u where u.id = :id")
    Optional<SecurityUser> findAndLockById(long id);

    @Query("""
            select new com.rammendez.warehouse.security.UserAccount(
                u.id, u.username, u.email, u.passwordHash, u.enabled, u.locked, u.authVersion)
            from SecurityUser u where u.id = :id
            """)
    Optional<UserAccount> findUserById(long id);

    @Query("select r.code from SecurityUser u join u.roles r where u.id = :userId order by r.code")
    List<String> findUserRoleCodes(long userId);

    @Query("""
            select distinct p.code from SecurityUser u join u.roles r join r.permissions p
            where u.id = :userId order by p.code
            """)
    List<String> findUserPermissionCodes(long userId);

    @Query("""
            select count(s) > 0 from WarehouseScope s
            where s.userId = :userId and s.warehouseId = :warehouseId
              and (:write = false or s.scopeRole in (OPERATOR, APPROVER, MANAGER))
            """)
    boolean hasWarehouseScopeForRequestedAccess(long userId, long warehouseId, boolean write);
}
