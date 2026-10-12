package com.rammendez.warehouse.contact;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "contact_message")
public class ContactMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false, length = 160)
    String name;

    @Column(nullable = false, length = 255)
    String email;

    @Column(nullable = false, length = 200)
    String subject;

    @Column(nullable = false, columnDefinition = "text")
    String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    ContactDtos.Status status = ContactDtos.Status.NEW;

    @Column(name = "assigned_to")
    Long assignedTo;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    Instant createdAt;

    @Column(name = "resolved_at")
    Instant resolvedAt;

    protected ContactMessage() {}

    ContactDtos.Response toResponse() {
        return new ContactDtos.Response(id, name, email, subject, message, status, assignedTo, createdAt, resolvedAt);
    }
}
