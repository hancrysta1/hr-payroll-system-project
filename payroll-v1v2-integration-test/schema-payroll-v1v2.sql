-- V2 급여 통합테스트용 H2 스키마.

CREATE TABLE IF NOT EXISTS employee_payroll_extension (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    is_employee BOOLEAN NOT NULL DEFAULT FALSE,
    is_freelancer BOOLEAN NOT NULL DEFAULT FALSE,
    is_no_deduction BOOLEAN NOT NULL DEFAULT FALSE,
    is_auto_tax BOOLEAN NOT NULL DEFAULT FALSE,
    national_pension_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_insurance_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    employment_insurance_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    industrial_accident_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_probation BOOLEAN NOT NULL DEFAULT FALSE,
    probation_rate DECIMAL(3,2) NOT NULL DEFAULT 1.00,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE (user_id, branch_id)
);

CREATE TABLE IF NOT EXISTS employee_payroll_auto_tax (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    family_count INT NOT NULL DEFAULT 1,
    child_count INT NOT NULL DEFAULT 0,
    tax_rate_option INT NOT NULL DEFAULT 100,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE (user_id, branch_id)
);

CREATE TABLE IF NOT EXISTS user_insurance_amount (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    national_pension_amount DECIMAL(19, 4),
    health_insurance_amount DECIMAL(19, 4),
    employment_insurance_amount DECIMAL(19, 4),
    industrial_accident_insurance_amount DECIMAL(19, 4),
    income_tax_amount DECIMAL(19, 4),
    local_tax_amount DECIMAL(19, 4),
    UNIQUE (user_id, branch_id)
);

CREATE TABLE IF NOT EXISTS pay_item_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    category VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    tax_free_type VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS branch_pay_item_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    category VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    tax_free_type VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS manual_deduction_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_salary_deductions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    tax_rate BOOLEAN,
    national_pension_enabled BOOLEAN,
    health_insurance_enabled BOOLEAN,
    employment_insurance_enabled BOOLEAN,
    industrial_accident_enabled BOOLEAN,
    UNIQUE (user_id, branch_id)
);

CREATE TABLE IF NOT EXISTS salary_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    user_id BIGINT NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    is_bulk_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    reason_snapshot TEXT,
    UNIQUE (branch_id, work_date, user_id)
);
