package com.atlas.common.tenancy;

/** Permission codes; must match the rows seeded in V1__tenancy_and_identity.sql. */
public final class Permissions {

    public static final String ORG_MANAGE = "org.manage";
    public static final String MEMBER_MANAGE = "member.manage";
    public static final String CATALOGUE_READ = "catalogue.read";
    public static final String CATALOGUE_WRITE = "catalogue.write";
    public static final String CUSTOMER_READ = "customer.read";
    public static final String CUSTOMER_WRITE = "customer.write";
    public static final String ORDER_READ = "order.read";
    public static final String ORDER_WRITE = "order.write";
    public static final String INVOICE_READ = "invoice.read";
    public static final String INVOICE_WRITE = "invoice.write";
    public static final String AUDIT_READ = "audit.read";
    public static final String RECOVERY_CREATE = "recovery.create";
    public static final String RECOVERY_APPROVE = "recovery.approve";
    public static final String RECOVERY_EXECUTE = "recovery.execute";
    public static final String INTEGRITY_READ = "integrity.read";
    public static final String INTEGRITY_MANAGE = "integrity.manage";

    public static final String ROLE_ORGANIZATION_ADMIN = "ORGANIZATION_ADMIN";

    private Permissions() {
    }
}
