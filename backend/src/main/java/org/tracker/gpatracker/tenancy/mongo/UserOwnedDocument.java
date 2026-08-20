package org.tracker.gpatracker.tenancy.mongo;

import org.springframework.data.annotation.Transient;

/**
 * Base class for Mongo documents that belong to exactly one tenant.
 *
 * <p>The field is called {@code studentId}, not {@code student_id}, and carries no {@code @Field}
 * override. That is deliberate: four of the five owned collections already store the key under
 * exactly that name. Declaring {@code @Field("student_id")} here would silently stop matching every
 * existing document in those four — each would deserialize with a null owner and then trip the
 * load-time assert — turning a no-op change into a four-collection rename migration.
 *
 * <p>{@code getOwnerId}/{@code setOwnerId} are the tenancy-facing names for the same value and are
 * {@code @Transient} so Spring Data does not also persist them as an {@code ownerId} field.
 */
public abstract class UserOwnedDocument extends BaseDocument implements OwnedDocument {

    private Long studentId;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    @Transient
    @Override
    public Long getOwnerId() {
        return studentId;
    }

    @Transient
    @Override
    public void setOwnerId(Long ownerId) {
        this.studentId = ownerId;
    }
}
