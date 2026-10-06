package com.rammendez.warehouse.security;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AdminRepository {
    private final JdbcTemplate jdbc;
    private final SecurityRepository users;

    public AdminRepository(JdbcTemplate jdbc, SecurityRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public void lockUser(long id) {
        Sql.firstRowOrThrowNotFound(
                jdbc.query(
                        "select id from security_user where id=? for update",
                        (r, n) -> r.getLong(1),
                        id),
                "User");
    }

    public AdminDtos.UserResponse getUserWithRolesAndWarehouseScopes(long id) {
        var user = users.findUserById(id).orElseThrow(() -> BusinessException.missing("User"));
        var scopes =
                jdbc.query(
                        "select * from security_user_warehouse_scope where user_id=? order by"
                                + " warehouse_id",
                        (r, n) ->
                                new AdminDtos.Scope(
                                        r.getLong("warehouse_id"),
                                        AdminDtos.ScopeRole.valueOf(r.getString("scope_role"))),
                        id);
        return new AdminDtos.UserResponse(
                id,
                user.username(),
                user.email(),
                user.enabled(),
                user.locked(),
                users.findUserRoleCodes(id),
                scopes);
    }

    public PageResponse<AdminDtos.UserSummary> listUserSummaries(int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        return new PageResponse<>(
                jdbc.query(
                        "select id,username,email,enabled,locked from security_user order by id"
                                + " limit ? offset ?",
                        (r, n) ->
                                new AdminDtos.UserSummary(
                                        r.getLong("id"),
                                        r.getString("username"),
                                        r.getString("email"),
                                        r.getBoolean("enabled"),
                                        r.getBoolean("locked")),
                        size,
                        offset),
                jdbc.queryForObject("select count(*) from security_user", Long.class),
                page,
                size);
    }

    public long insertUserWithPasswordHash(AdminDtos.Create input, String hash) {
        return jdbc.queryForObject(
                "insert into security_user(username,email,password_hash) values (?,?,?) returning"
                        + " id",
                Long.class,
                input.username().trim(),
                input.email(),
                hash);
    }

    public void updateUserAdvanceAuthVersionAndRevokeSessions(long id, AdminDtos.Update input, String hash) {
        jdbc.update(
                "update security_user set"
                    + " email=coalesce(?,email),enabled=coalesce(?,enabled),locked=coalesce(?,locked),password_hash=coalesce(?,password_hash),auth_version=auth_version+1,updated_at=now()"
                    + " where id=?",
                input.email(),
                input.enabled(),
                input.locked(),
                hash,
                id);
        revokeAllUserAuthSessionsAndRefreshTokens(id);
    }

    public void replaceUserRolesAdvanceAuthVersionAndRevokeSessions(long id, List<Long> roleIds) {
        jdbc.update("delete from security_user_role where user_id=?", id);
        for (long roleId : roleIds) {
            jdbc.update("insert into security_user_role(user_id,role_id) values (?,?)", id, roleId);
        }
        jdbc.update(
                "update security_user set auth_version=auth_version+1,updated_at=now() where id=?",
                id);
        revokeAllUserAuthSessionsAndRefreshTokens(id);
    }

    public void replaceUserWarehouseScopes(long id, List<AdminDtos.Scope> scopes) {
        jdbc.update("delete from security_user_warehouse_scope where user_id=?", id);
        for (var scope : scopes) {
            jdbc.update(
                    "insert into security_user_warehouse_scope(user_id,warehouse_id,scope_role)"
                            + " values (?,?,?)",
                    id,
                    scope.warehouseId(),
                    scope.scopeRole().name());
        }
    }

    public List<AdminDtos.Role> listAvailableRoles() {
        return jdbc.query(
                "select * from security_role order by code",
                (r, n) ->
                        new AdminDtos.Role(
                                r.getLong("id"), r.getString("code"), r.getString("description")));
    }

    private void revokeAllUserAuthSessionsAndRefreshTokens(long id) {
        jdbc.update(
                "update auth_session set revoked_at=coalesce(revoked_at,now()) where user_id=?",
                id);
        jdbc.update(
                "update auth_refresh_token set revoked_at=coalesce(revoked_at,now()) where"
                        + " session_id in (select id from auth_session where user_id=?)",
                id);
    }
}
