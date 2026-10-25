-- 阶段 F：在已存在的 after_sale/review/payment/reconciliation 结构后补齐履约与结算结构。
-- 字段与约束只映射 docs/03；不写入业务种子。
CREATE TABLE shipment (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 order_id BIGINT UNSIGNED NOT NULL,
 carrier VARCHAR(50) NOT NULL,
 tracking_no VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
 shipped_by BIGINT UNSIGNED NOT NULL,
 shipped_at DATETIME(3) NOT NULL,
 received_at DATETIME(3) NULL,
 FOREIGN KEY(order_id) REFERENCES trade_order(id) ON DELETE RESTRICT,
 FOREIGN KEY(shipped_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
 UNIQUE(order_id),
 INDEX ix_shipment_carrier_tracking(carrier,tracking_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipment_item (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 shipment_id BIGINT UNSIGNED NOT NULL,
 order_item_id BIGINT UNSIGNED NOT NULL,
 shipped_quantity INT UNSIGNED NOT NULL,
 FOREIGN KEY(shipment_id) REFERENCES shipment(id) ON DELETE RESTRICT,
 FOREIGN KEY(order_item_id) REFERENCES order_item(id) ON DELETE RESTRICT,
 UNIQUE(order_item_id),
 CHECK(shipped_quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipment_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 shipment_id BIGINT UNSIGNED NOT NULL,
 event_time DATETIME(3) NOT NULL,
 description VARCHAR(300) NOT NULL,
 source VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'MANUAL',
 operator_id BIGINT UNSIGNED NOT NULL,
 FOREIGN KEY(shipment_id) REFERENCES shipment(id) ON DELETE RESTRICT,
 FOREIGN KEY(operator_id) REFERENCES iam_user(id) ON DELETE RESTRICT,
 CHECK(source IN ('MANUAL','SYSTEM')),
 INDEX ix_shipment_event_time(shipment_id,event_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE settlement (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 settlement_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 shop_id BIGINT UNSIGNED NOT NULL,
 period_date DATE NOT NULL,
 status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
 gross_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
 refund_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
 fee_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
 adjustment_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
 payable_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
 created_by BIGINT UNSIGNED NOT NULL,
 approved_by BIGINT UNSIGNED NULL,
 approved_at DATETIME(3) NULL,
 paid_by BIGINT UNSIGNED NULL,
 paid_at DATETIME(3) NULL,
 mock_payout_no VARCHAR(80) COLLATE utf8mb4_bin NULL UNIQUE,
 version INT UNSIGNED NOT NULL DEFAULT 1,
 FOREIGN KEY(shop_id) REFERENCES shop(id) ON DELETE RESTRICT,
 FOREIGN KEY(created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
 FOREIGN KEY(approved_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
 FOREIGN KEY(paid_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
 UNIQUE(shop_id,period_date),
 CHECK(status IN ('DRAFT','APPROVED','PAID','REJECTED')),
 CHECK(gross_amount>=0 AND refund_amount>=0 AND fee_amount>=0),
 INDEX ix_settlement_shop_status(shop_id,status,period_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE settlement_item (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 settlement_id BIGINT UNSIGNED NOT NULL,
 order_id BIGINT UNSIGNED NOT NULL,
 goods_amount DECIMAL(12,2) NOT NULL,
 refund_amount DECIMAL(12,2) NOT NULL,
 net_amount DECIMAL(12,2) NOT NULL,
 fee_rate DECIMAL(6,4) NOT NULL DEFAULT 0.0300,
 fee_amount DECIMAL(12,2) NOT NULL,
 payable_amount DECIMAL(12,2) NOT NULL,
 FOREIGN KEY(settlement_id) REFERENCES settlement(id) ON DELETE RESTRICT,
 FOREIGN KEY(order_id) REFERENCES trade_order(id) ON DELETE RESTRICT,
 UNIQUE(order_id),
 CHECK(goods_amount>=0 AND refund_amount>=0 AND net_amount=goods_amount-refund_amount),
 CHECK(fee_rate=0.0300 AND fee_amount>=0 AND payable_amount=net_amount-fee_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE settlement_adjustment (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 shop_id BIGINT UNSIGNED NOT NULL,
 original_settlement_id BIGINT UNSIGNED NOT NULL,
 order_id BIGINT UNSIGNED NOT NULL,
 issue_id BIGINT UNSIGNED NOT NULL,
 target_settlement_id BIGINT UNSIGNED NULL,
 amount DECIMAL(12,2) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL,
 approved_by BIGINT UNSIGNED NULL,
 status VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
 FOREIGN KEY(shop_id) REFERENCES shop(id) ON DELETE RESTRICT,
 FOREIGN KEY(original_settlement_id) REFERENCES settlement(id) ON DELETE RESTRICT,
 FOREIGN KEY(order_id) REFERENCES trade_order(id) ON DELETE RESTRICT,
 FOREIGN KEY(issue_id) REFERENCES reconciliation_issue(id) ON DELETE RESTRICT,
 FOREIGN KEY(target_settlement_id) REFERENCES settlement(id) ON DELETE RESTRICT,
 FOREIGN KEY(created_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
 FOREIGN KEY(approved_by) REFERENCES iam_user(id) ON DELETE RESTRICT,
 UNIQUE(issue_id),
 CHECK(amount<>0 AND status IN ('PENDING','APPROVED','APPLIED')),
 INDEX ix_settlement_adjustment_shop_status(shop_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DELIMITER //
CREATE TRIGGER settlement_reject_paid_update BEFORE UPDATE ON settlement FOR EACH ROW
BEGIN
 IF OLD.status='PAID' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='paid settlement is immutable'; END IF;
END//
CREATE TRIGGER settlement_reject_paid_delete BEFORE DELETE ON settlement FOR EACH ROW
BEGIN
 IF OLD.status='PAID' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='paid settlement is immutable'; END IF;
END//
CREATE TRIGGER settlement_item_reject_paid_update BEFORE UPDATE ON settlement_item FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM settlement WHERE id=OLD.settlement_id AND status='PAID') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='paid settlement item is immutable'; END IF;
END//
CREATE TRIGGER settlement_item_reject_paid_delete BEFORE DELETE ON settlement_item FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM settlement WHERE id=OLD.settlement_id AND status='PAID') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='paid settlement item is immutable'; END IF;
END//
CREATE TRIGGER settlement_adjustment_reject_applied_update BEFORE UPDATE ON settlement_adjustment FOR EACH ROW
BEGIN
 IF OLD.status='APPLIED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='applied settlement adjustment is immutable'; END IF;
END//
CREATE TRIGGER settlement_adjustment_reject_applied_delete BEFORE DELETE ON settlement_adjustment FOR EACH ROW
BEGIN
 IF OLD.status='APPLIED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='applied settlement adjustment is immutable'; END IF;
END//
DELIMITER ;
