-- =====================================================================
-- V1: Tenancy and identity
-- Every tenant-owned table carries organization_id and exposes a
-- UNIQUE (organization_id, id) key so child tables can use composite
-- foreign keys. That makes a cross-tenant reference impossible at the
-- database level, as a second line of defence behind the application's
-- authorization checks (see ADR-0004).
-- =====================================================================

CREATE TABLE organizations (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        varchar(64)  NOT NULL UNIQUE
                CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'),
    name        varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    currency    char(3)      NOT NULL DEFAULT 'INR' CHECK (currency ~ '^[A-Z]{3}$'),
    status      varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);

CREATE TABLE users (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email               varchar(320) NOT NULL CHECK (position('@' IN email) > 1),
    display_name        varchar(200) NOT NULL,
    -- Only a one-way hash (BCrypt/Argon2) is ever stored here.
    password_hash       varchar(255) NOT NULL,
    platform_admin      boolean      NOT NULL DEFAULT false,
    status              varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_login_count  integer      NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until        timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email_ci ON users (lower(email));

-- Reference data: roles and permissions are part of the product's
-- authorization model, not demo data.
CREATE TABLE roles (
    code        varchar(32) PRIMARY KEY,
    description text        NOT NULL
);

CREATE TABLE permissions (
    code        varchar(64) PRIMARY KEY,
    description text        NOT NULL
);

CREATE TABLE role_permissions (
    role_code       varchar(32) NOT NULL REFERENCES roles (code),
    permission_code varchar(64) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE organization_members (
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    user_id         uuid        NOT NULL REFERENCES users (id),
    status          varchar(16) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('INVITED', 'ACTIVE', 'REMOVED')),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX ix_org_members_user ON organization_members (user_id);

-- A member may hold several roles within one organization.
CREATE TABLE member_roles (
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    role_code       varchar(32) NOT NULL REFERENCES roles (code),
    granted_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, user_id, role_code),
    FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_members (organization_id, user_id)
);

INSERT INTO roles (code, description) VALUES
    ('ORGANIZATION_ADMIN', 'Manages members, roles and organization settings'),
    ('OPERATIONS_MANAGER', 'Manages catalogue, prices, orders and invoices'),
    ('SALES_STAFF',        'Creates customers and orders'),
    ('AUDITOR',            'Read-only access to audit and provenance history'),
    ('RECOVERY_APPROVER',  'Approves or rejects recovery plans'),
    ('EMPLOYEE',           'Read-only access to business records');

INSERT INTO permissions (code, description) VALUES
    ('org.manage',           'Manage organization settings'),
    ('member.manage',        'Invite, remove and assign roles to members'),
    ('catalogue.read',       'View products'),
    ('catalogue.write',      'Create and edit products and prices'),
    ('customer.read',        'View customers'),
    ('customer.write',       'Create and edit customers'),
    ('order.read',           'View orders'),
    ('order.write',          'Create and edit orders'),
    ('invoice.read',         'View invoices'),
    ('invoice.write',        'Create, issue and void invoices'),
    ('audit.read',           'View audit history and provenance'),
    ('recovery.create',      'Open recovery incidents and generate plans'),
    ('recovery.approve',     'Approve or reject recovery plans'),
    ('recovery.execute',     'Execute approved recovery plans');

INSERT INTO role_permissions (role_code, permission_code)
SELECT 'ORGANIZATION_ADMIN', code FROM permissions
 WHERE code NOT IN ('recovery.approve');   -- separation of duties

INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('OPERATIONS_MANAGER', 'catalogue.read'), ('OPERATIONS_MANAGER', 'catalogue.write'),
    ('OPERATIONS_MANAGER', 'customer.read'),  ('OPERATIONS_MANAGER', 'customer.write'),
    ('OPERATIONS_MANAGER', 'order.read'),     ('OPERATIONS_MANAGER', 'order.write'),
    ('OPERATIONS_MANAGER', 'invoice.read'),   ('OPERATIONS_MANAGER', 'invoice.write'),
    ('OPERATIONS_MANAGER', 'recovery.create'),
    ('SALES_STAFF', 'catalogue.read'), ('SALES_STAFF', 'customer.read'),
    ('SALES_STAFF', 'customer.write'), ('SALES_STAFF', 'order.read'),
    ('SALES_STAFF', 'order.write'),
    ('AUDITOR', 'catalogue.read'), ('AUDITOR', 'customer.read'), ('AUDITOR', 'order.read'),
    ('AUDITOR', 'invoice.read'),   ('AUDITOR', 'audit.read'),
    ('RECOVERY_APPROVER', 'audit.read'), ('RECOVERY_APPROVER', 'recovery.approve'),
    ('RECOVERY_APPROVER', 'catalogue.read'), ('RECOVERY_APPROVER', 'order.read'),
    ('RECOVERY_APPROVER', 'invoice.read'),
    ('EMPLOYEE', 'catalogue.read'), ('EMPLOYEE', 'customer.read'),
    ('EMPLOYEE', 'order.read'), ('EMPLOYEE', 'invoice.read');
