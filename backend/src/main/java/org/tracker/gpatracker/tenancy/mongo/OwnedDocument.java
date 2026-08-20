package org.tracker.gpatracker.tenancy.mongo;

import org.tracker.gpatracker.tenancy.UserOwned;

/**
 * A Mongo document whose owner can be stamped by {@link MongoTenantListener} on write.
 *
 * <p>Split from the read-only {@link UserOwned} because not every owned document can accept a
 * stamp: {@code SyllabusUploadQuota} carries its owner <em>as</em> its {@code @Id}, which is
 * immutable in Mongo. That one implements {@code UserOwned} only, so the load-time assert still
 * covers it while the write-time stamp skips it.
 */
public interface OwnedDocument extends UserOwned {

    void setOwnerId(Long ownerId);
}
