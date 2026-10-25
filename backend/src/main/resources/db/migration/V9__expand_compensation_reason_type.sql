ALTER TABLE refund
 DROP INDEX functional_index,
 MODIFY COLUMN reason_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
 ADD UNIQUE INDEX uq_refund_late_compensation (
  payment_id,
  (CAST(CASE WHEN reason_type='LATE_PAYMENT_COMPENSATION' THEN reason_type ELSE NULL END AS CHAR(32)))
 );
