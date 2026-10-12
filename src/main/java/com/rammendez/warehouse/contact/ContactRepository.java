package com.rammendez.warehouse.contact;

import com.rammendez.warehouse.common.BusinessException;
import com.rammendez.warehouse.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<ContactMessage, UUID> {
    default ContactDtos.Response getContactMessage(UUID id) {
        return findById(id).orElseThrow(() -> BusinessException.missing("Contact message")).toResponse();
    }

    default PageResponse<ContactDtos.Response> list(int page, int size) {
        PageResponse.validatePageBoundsAndCalculateOffset(page, size);
        var order = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));
        var result = findAll(PageRequest.of(page, size, order));
        return new PageResponse<>(result.map(ContactMessage::toResponse).getContent(), result.getTotalElements(), page, size);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ContactMessage m
            set m.status = :status, m.assignedTo = :actor,
                m.resolvedAt = case when :resolved = true then instant else null end
            where m.id = :id
            """)
    void updateStatus(UUID id, ContactDtos.Status status, long actor, boolean resolved);
}
