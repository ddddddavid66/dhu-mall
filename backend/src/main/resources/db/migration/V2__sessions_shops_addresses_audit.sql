CREATE TABLE shop (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  name VARCHAR(80) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  contact_name VARCHAR(40) NOT NULL,
  contact_phone VARCHAR(32) NOT NULL,
  CONSTRAINT ck_shop_status CHECK(status IN ('ACTIVE','SUSPENDED')),
  INDEX ix_shop_name(name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shop_member (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  shop_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  duty VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uk_shop_member UNIQUE(shop_id,user_id,duty),
  CONSTRAINT fk_shop_member_shop FOREIGN KEY(shop_id) REFERENCES shop(id) ON DELETE RESTRICT,
  CONSTRAINT fk_shop_member_user FOREIGN KEY(user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
  CONSTRAINT ck_member_duty CHECK(duty IN ('MERCHANT','WAREHOUSE')),
  CONSTRAINT ck_member_status CHECK(status IN ('ACTIVE','DISABLED')),
  INDEX ix_member_user_status(user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id BIGINT UNSIGNED NOT NULL,
  family_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  token_hash VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  replaced_by_id BIGINT UNSIGNED NULL,
  csrf_hash VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  auth_version INT UNSIGNED NOT NULL,
  CONSTRAINT uk_refresh_hash UNIQUE(token_hash),
  CONSTRAINT fk_refresh_user FOREIGN KEY(user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_refresh_replacement FOREIGN KEY(replaced_by_id) REFERENCES refresh_session(id) ON DELETE RESTRICT,
  INDEX ix_refresh_family(family_no),
  INDEX ix_refresh_user_expiry(user_id,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_address (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  user_id BIGINT UNSIGNED NOT NULL,
  receiver_name VARCHAR(40) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  province VARCHAR(40) NOT NULL,
  city VARCHAR(40) NOT NULL,
  district VARCHAR(40) NOT NULL,
  detail VARCHAR(200) NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  default_user_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN status='ACTIVE' AND is_default=1 THEN user_id ELSE NULL END) STORED,
  CONSTRAINT uk_address_active_default UNIQUE(default_user_id),
  CONSTRAINT fk_address_user FOREIGN KEY(user_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
  CONSTRAINT ck_address_default CHECK(is_default IN (0,1)),
  CONSTRAINT ck_address_status CHECK(status IN ('ACTIVE','DELETED')),
  INDEX ix_address_user_status(user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  actor_id BIGINT UNSIGNED NULL,
  action VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  resource_type VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  resource_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
  shop_id BIGINT UNSIGNED NULL,
  result VARCHAR(16) NOT NULL,
  summary JSON NOT NULL,
  trace_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  ip_hash VARCHAR(64) COLLATE utf8mb4_bin NULL,
  CONSTRAINT fk_audit_actor FOREIGN KEY(actor_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_audit_shop FOREIGN KEY(shop_id) REFERENCES shop(id) ON DELETE RESTRICT,
  CONSTRAINT ck_audit_result CHECK(result IN ('SUCCESS','DENIED','FAILED')),
  INDEX ix_audit_actor_created(actor_id,created_at),
  INDEX ix_audit_shop_created(shop_id,created_at),
  INDEX ix_audit_trace(trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
