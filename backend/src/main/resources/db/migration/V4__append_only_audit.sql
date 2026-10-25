-- 审计只能追加；Flyway 测试 clean 删除整张测试表，不通过 UPDATE/DELETE 改写记录。
CREATE TRIGGER audit_log_reject_update BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit_log is append-only';

CREATE TRIGGER audit_log_reject_delete BEFORE DELETE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit_log is append-only';
