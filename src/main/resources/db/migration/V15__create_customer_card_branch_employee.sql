-- Create customer table with encrypted PII fields
CREATE TABLE IF NOT EXISTS customer (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(500) NOT NULL, -- Encrypted, so larger size
    phone VARCHAR(500) NOT NULL, -- Encrypted
    address VARCHAR(1000), -- Encrypted
    ssn VARCHAR(500), -- Encrypted
    customer_type VARCHAR(20) NOT NULL CHECK (customer_type IN ('INDIVIDUAL', 'CORPORATE', 'JOINT')),
    kyc_status VARCHAR(20) NOT NULL CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Indexes
CREATE INDEX idx_customer_kyc ON customer(kyc_status) WHERE deleted = FALSE;
CREATE INDEX idx_customer_type ON customer(customer_type);

-- Create card table
CREATE TABLE IF NOT EXISTS card (
    id BIGSERIAL PRIMARY KEY,
    card_number VARCHAR(500) NOT NULL, -- Encrypted
    card_type VARCHAR(20) NOT NULL CHECK (card_type IN ('DEBIT', 'CREDIT', 'PREPAID', 'VIRTUAL')),
    expiration_date DATE NOT NULL,
    cvv VARCHAR(500) NOT NULL, -- Encrypted
    cardholder_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'BLOCKED', 'EXPIRED', 'CANCELLED', 'LOST', 'STOLEN')),
    account_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    activation_date DATE,
    last_used_date DATE,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    blocked_date DATE,
    block_reason VARCHAR(500),
    network VARCHAR(20) CHECK (network IN ('VISA', 'MASTERCARD', 'AMEX', 'DISCOVER', 'UNIONPAY')),
    issuer_bank VARCHAR(50),
    contactless BOOLEAN NOT NULL DEFAULT FALSE,
    international BOOLEAN NOT NULL DEFAULT FALSE,
    online_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    
    CONSTRAINT fk_card_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_card_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
);

-- Indexes for card
CREATE INDEX idx_card_account ON card(account_id);
CREATE INDEX idx_card_customer ON card(customer_id);
CREATE INDEX idx_card_status ON card(status);
CREATE INDEX idx_card_expiry ON card(expiration_date) WHERE status = 'ACTIVE';

-- Create branch table
CREATE TABLE IF NOT EXISTS branch (
    id BIGSERIAL PRIMARY KEY,
    branch_code VARCHAR(20) NOT NULL UNIQUE,
    branch_name VARCHAR(200) NOT NULL,
    branch_type VARCHAR(20) NOT NULL CHECK (branch_type IN ('PHYSICAL', 'VIRTUAL', 'ATM_ONLY', 'MOBILE')),
    address VARCHAR(500),
    city VARCHAR(100),
    state VARCHAR(100),
    zip_code VARCHAR(20),
    country VARCHAR(100),
    phone_number VARCHAR(20),
    email VARCHAR(100),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'UNDER_MAINTENANCE', 'CLOSED')),
    manager_id BIGINT,
    latitude DECIMAL(10, 6),
    longitude DECIMAL(10, 6),
    operating_hours VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Indexes for branch
CREATE UNIQUE INDEX idx_branch_code ON branch(branch_code);
CREATE INDEX idx_branch_type ON branch(branch_type);
CREATE INDEX idx_branch_status ON branch(status);

-- Create employee table
CREATE TABLE IF NOT EXISTS employee (
    id BIGSERIAL PRIMARY KEY,
    employee_number VARCHAR(20) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone_number VARCHAR(20),
    role VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'ON_LEAVE', 'TERMINATED', 'SUSPENDED')),
    branch_id BIGINT,
    hire_date DATE NOT NULL,
    termination_date DATE,
    supervisor_id BIGINT,
    department VARCHAR(100),
    job_title VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_at TIMESTAMP,
    updated_by VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    
    CONSTRAINT fk_employee_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
    CONSTRAINT fk_employee_supervisor FOREIGN KEY (supervisor_id) REFERENCES employee(id)
);

-- Indexes for employee
CREATE UNIQUE INDEX idx_employee_number ON employee(employee_number);
CREATE UNIQUE INDEX idx_employee_email ON employee(email);
CREATE INDEX idx_employee_branch ON employee(branch_id);
CREATE INDEX idx_employee_status ON employee(status);

-- Create employee_permissions table
CREATE TABLE IF NOT EXISTS employee_permissions (
    employee_id BIGINT NOT NULL,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (employee_id, permission),
    CONSTRAINT fk_emp_perm_employee FOREIGN KEY (employee_id) REFERENCES employee(id) ON DELETE CASCADE
);

-- Add foreign key for branch manager (after employee table exists)
ALTER TABLE branch ADD CONSTRAINT fk_branch_manager FOREIGN KEY (manager_id) REFERENCES employee(id);

-- Comments
COMMENT ON TABLE customer IS 'Customer table with encrypted PII fields (email, phone, ssn, address)';
COMMENT ON TABLE card IS 'Card table with encrypted card_number and cvv';
COMMENT ON TABLE branch IS 'Branch locations (physical, virtual, ATM-only, mobile)';
COMMENT ON TABLE employee IS 'Bank employees with role-based access control';
