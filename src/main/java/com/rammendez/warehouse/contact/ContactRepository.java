package com.rammendez.warehouse.contact;

import com.rammendez.warehouse.common.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class ContactRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ContactDtos.Response> ROW =
            (r, n) ->
                    new ContactDtos.Response(
                            r.getObject("id", UUID.class),
                            r.getString("name"),
                            r.getString("email"),
                            r.getString("subject"),
                            r.getString("message"),
                            ContactDtos.Status.valueOf(r.getString("status")),
                            Sql.nullableLong(r, "assigned_to"),
                            Sql.readNullableInstant(r, "created_at"),
                            Sql.readNullableInstant(r, "resolved_at"));

    public ContactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(ContactDtos.Input input) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into contact_message(id,name,email,subject,message) values (?,?,?,?,?)",
                id,
                input.name().trim(),
                input.email(),
                input.subject().trim(),
                input.message());
        return id;
    }

    public ContactDtos.Response getContactMessage(UUID id) {
        return Sql.firstRowOrThrowNotFound(
                jdbc.query("select * from contact_message where id=?", ROW, id), "Contact message");
    }

    public PageResponse<ContactDtos.Response> list(int page, int size) {
        int offset = PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        return new PageResponse<>(
                jdbc.query(
                        "select * from contact_message order by created_at desc,id limit ? offset"
                                + " ?",
                        ROW,
                        size,
                        offset),
                jdbc.queryForObject("select count(*) from contact_message", Long.class),
                page,
                size);
    }

    public void updateContactMessageStatusAndAssignActor(UUID id, ContactDtos.Status status, long actor) {
        jdbc.update(
                "update contact_message set status=?,assigned_to=?,resolved_at=case when"
                        + " ?='RESOLVED' then now() else null end where id=?",
                status.name(),
                actor,
                status.name(),
                id);
    }
}
