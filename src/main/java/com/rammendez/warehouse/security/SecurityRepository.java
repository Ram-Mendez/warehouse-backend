package com.rammendez.warehouse.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SecurityRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<UserAccount> USER =
            (r, n) ->
                    new UserAccount(
                            r.getLong("id"),
                            r.getString("username"),
                            r.getString("email"),
                            r.getString("password_hash"),
                            r.getBoolean("enabled"),
                            r.getBoolean("locked"),
                            r.getLong("auth_version"));

    public SecurityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findAndLockUserByUsername(String username) {
        return jdbc
                .query("select * from security_user where username=? for update", USER, username)
                .stream()
                .findFirst();
    }

    public Optional<UserAccount> findUserById(long id) {
        return jdbc.query("select * from security_user where id=?", USER, id).stream().findFirst();
    }

    public List<String> findUserRoleCodes(long userId) {
        return jdbc.queryForList(
                "select r.code from security_role r join security_user_role ur on ur.role_id=r.id"
                        + " where ur.user_id=? order by r.code",
                String.class,
                userId);
    }

    public List<String> findUserPermissionCodes(long userId) {
        return jdbc.queryForList(
                "select distinct p.code from security_permission p join security_role_permission rp"
                        + " on rp.permission_id=p.id join security_user_role ur on"
                        + " ur.role_id=rp.role_id where ur.user_id=? order by p.code",
                String.class,
                userId);
    }

    public boolean hasWarehouseScopeForRequestedAccess(long userId, long warehouseId, boolean write) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from security_user_warehouse_scope where user_id=?"
                                + " and warehouse_id=? and (?=false or scope_role in"
                                + " ('OPERATOR','APPROVER','MANAGER')))",
                        Boolean.class,
                        userId,
                        warehouseId,
                        write));
    }
}
