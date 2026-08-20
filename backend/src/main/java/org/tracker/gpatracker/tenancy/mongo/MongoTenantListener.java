package org.tracker.gpatracker.tenancy.mongo;

import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;
import org.tracker.gpatracker.tenancy.UserContext;
import org.tracker.gpatracker.tenancy.UserOwned;

/**
 * Stamps the owner on write and asserts it on read, for every Mongo document that declares one.
 *
 * <p>This is <em>not</em> the Mongo equivalent of the Hibernate filter, and should not be described
 * as one. A Spring Data lifecycle listener cannot inject criteria into a query — by the time it
 * runs, the server has already chosen and returned the documents. The actual scoping has to be in
 * the query (see the {@code @Query} on {@code UserSyllabusRepository} and every
 * {@code findByStudentId…} derived finder). What this class adds is the backstop: if a query ever
 * does return a document belonging to someone else, the read fails loudly instead of quietly
 * handing it to the caller.
 *
 * <p>Both halves fail closed. An unbound thread cannot write an unowned document and cannot read an
 * owned one, so a forgotten scope surfaces as an exception rather than as an isolation hole.
 */
@Component
public class MongoTenantListener extends AbstractMongoEventListener<Object> {

    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        Object source = event.getSource();
        if (!(source instanceof OwnedDocument document)) {
            return;
        }
        if (document.getOwnerId() == null) {
            document.setOwnerId(UserContext.requireOwnerId());
            return;
        }
        // An owner that is already set is only trusted if it is the caller's own. System scope is
        // exempt: migrations and scheduled recomputes legitimately write across tenants.
        if (!UserContext.isSystem() && !document.getOwnerId().equals(UserContext.requireOwnerId())) {
            throw new IllegalStateException(
                    "Refusing to write " + source.getClass().getSimpleName() + " owned by "
                            + document.getOwnerId() + " while acting as " + UserContext.requireOwnerId());
        }
    }

    @Override
    public void onAfterConvert(AfterConvertEvent<Object> event) {
        Object source = event.getSource();
        if (!(source instanceof UserOwned document)) {
            return;
        }
        if (UserContext.isSystem()) {
            return;
        }
        Long current = UserContext.requireOwnerId();
        if (!current.equals(document.getOwnerId())) {
            throw new IllegalStateException(
                    "Loaded " + source.getClass().getSimpleName() + " owned by " + document.getOwnerId()
                            + " while acting as " + current
                            + " — the query that produced it is missing its owner criterion");
        }
    }
}
