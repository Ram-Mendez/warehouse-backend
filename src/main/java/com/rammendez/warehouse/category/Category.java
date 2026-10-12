package com.rammendez.warehouse.category;

import jakarta.persistence.*;

@Entity
@Table(name = "category")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 60)
    String code;

    @Column(nullable = false, length = 160)
    String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    Category parent;

    @Column(nullable = false)
    boolean active;

    protected Category() {}

    public Long getId() { return id; }

    CategoryDtos.Response toResponse() {
        return new CategoryDtos.Response(id, code, name, parent == null ? null : parent.getId(), active);
    }
}
