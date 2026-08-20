package org.tracker.gpatracker.tenancy;

/** Names for the Hibernate filter declared in {@code package-info.java}. */
public final class OwnerFilter {

    /** Filter name. Every user-owned {@code @Entity} must carry {@code @Filter(name = NAME)}. */
    public static final String NAME = "ownerFilter";

    /** Parameter name, supplied by {@link CurrentOwnerIdResolver} rather than by callers. */
    public static final String PARAM = "ownerId";

    /** The owner column. Physically the student id — see {@link UserOwnedEntity}. */
    public static final String CONDITION = "student_id = :" + PARAM;

    private OwnerFilter() {
    }
}
