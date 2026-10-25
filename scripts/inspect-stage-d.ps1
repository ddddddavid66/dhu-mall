# 只读核验项目自己的开发库；不输出凭据或完整地址。
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'use-db.ps1')
$freeze=Get-Content (Join-Path $projectRoot 'infra/versions.stage-b.lock.json') -Raw | ConvertFrom-Json
$mysql=Join-Path $projectRoot ('.tools/mysql/mysql-'+$freeze.mysql.version+'-winx64/bin/mysql.exe')
$sql=@'
SELECT JSON_OBJECT(
 'databaseVersion',VERSION(),
 'capturedAtUtc',DATE_FORMAT(UTC_TIMESTAMP(3),'%Y-%m-%dT%H:%i:%s.%fZ'),
 'migrationCount',(SELECT COUNT(*) FROM flyway_schema_history WHERE success=1),
 'tableCount',(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'),
 'batches',(SELECT COUNT(*) FROM checkout_batch),
 'orders',(SELECT COUNT(*) FROM trade_order),
 'orderItems',(SELECT COUNT(*) FROM order_item),
 'pendingOrders',(SELECT COUNT(*) FROM trade_order WHERE status='PENDING_PAYMENT'),
 'closingOrders',(SELECT COUNT(*) FROM trade_order WHERE status='CLOSING'),
 'cancelledOrders',(SELECT COUNT(*) FROM trade_order WHERE status='CANCELLED'),
 'reserved',(SELECT SUM(reserved) FROM inventory),
 'reservations',(SELECT COUNT(*) FROM inventory_reservation WHERE status='RESERVED'),
 'onHand',(SELECT SUM(on_hand) FROM inventory),
 'stockConstraintViolations',(SELECT COUNT(*) FROM inventory WHERE reserved>on_hand),
 'reservationMismatches',(SELECT COUNT(*) FROM inventory i WHERE i.reserved<>(SELECT COALESCE(SUM(r.quantity),0) FROM inventory_reservation r WHERE r.sku_id=i.sku_id AND r.status='RESERVED')),
 'ledgerMismatches',(SELECT COUNT(*) FROM inventory i WHERE i.on_hand<>(SELECT COALESCE(SUM(m.on_hand_delta),0) FROM inventory_movement m WHERE m.sku_id=i.sku_id) OR i.reserved<>(SELECT COALESCE(SUM(m.reserved_delta),0) FROM inventory_movement m WHERE m.sku_id=i.sku_id)),
 'batchAmountMismatches',(SELECT COUNT(*) FROM checkout_batch b WHERE b.total_amount<>(SELECT COALESCE(SUM(o.payable_amount),0) FROM trade_order o WHERE o.batch_id=b.id)),
 'orderAmountMismatches',(SELECT COUNT(*) FROM trade_order o WHERE o.goods_amount<>(SELECT COALESCE(SUM(i.line_amount),0) FROM order_item i WHERE i.order_id=o.id) OR o.payable_amount<>o.goods_amount+o.freight_amount OR o.freight_amount<>0),
 'missingReservations',(SELECT COUNT(*) FROM order_item i LEFT JOIN inventory_reservation r ON r.order_item_id=i.id WHERE r.id IS NULL OR r.quantity<>i.quantity OR r.order_id<>i.order_id OR r.sku_id<>i.sku_id),
 'movementMismatches',(SELECT COUNT(*) FROM inventory_reservation r LEFT JOIN inventory_movement m ON m.business_key=CONCAT('RESERVE:',r.order_item_id) WHERE m.id IS NULL OR m.reserved_delta<>r.quantity OR m.on_hand_delta<>0),
 'outboxMismatches',(SELECT COUNT(*) FROM trade_order o WHERE (SELECT COUNT(*) FROM outbox_event e WHERE e.aggregate_id=CAST(o.id AS CHAR) AND e.event_type='OrderCreated')<>1),
 'previewMismatches',(SELECT COUNT(*) FROM checkout_batch b JOIN checkout_preview p ON p.id=b.preview_id WHERE p.used_at IS NULL OR p.user_id<>b.user_id OR p.amount<>b.total_amount),
 'pendingOutbox',(SELECT COUNT(*) FROM outbox_event WHERE status='PENDING' AND published_at IS NULL AND attempts=0),
 'publishedOutbox',(SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED'),
 'statusLogs',(SELECT COUNT(*) FROM order_status_log),
 'creationLogs',(SELECT COUNT(*) FROM order_status_log WHERE action='CREATE'),
 'reserveMovements',(SELECT COUNT(*) FROM inventory_movement WHERE movement_type='RESERVE'),
 'successfulCreates',(SELECT COUNT(*) FROM idempotency_record WHERE operation_id='API-27' AND status='SUCCEEDED'),
 'missingIdempotencyResource',(SELECT COUNT(*) FROM idempotency_record WHERE operation_id='API-27' AND status='SUCCEEDED' AND resource_id IS NULL),
 'afterSales',(SELECT COUNT(*) FROM after_sale),
 'reviews',(SELECT COUNT(*) FROM review),
 'expiryLeaseRows',(SELECT COUNT(*) FROM job_lease WHERE job_name='order-expiry'),
 'triggers',(SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())
);
'@
try {
    $env:MYSQL_PWD=$env:DB_PASSWORD
    $raw=& $mysql --no-defaults --host=127.0.0.1 --port=13306 --user=yueji_dev --database=yueji_mall_dev --batch --skip-column-names --raw --execute=$sql
    if($LASTEXITCODE -ne 0){throw '只读数据库检查失败'}
    $report=$raw | ConvertFrom-Json
    foreach($field in @('stockConstraintViolations','reservationMismatches','ledgerMismatches','batchAmountMismatches','orderAmountMismatches','missingReservations','movementMismatches','outboxMismatches','previewMismatches','missingIdempotencyResource')) {
        if($report.$field -ne 0){throw "不变量失败：$field"}
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $projectRoot '.artifacts/D-database-evidence.json') -Encoding utf8
    $plans=[ordered]@{}
    $queries=[ordered]@{
        ownedOrderList="SELECT id,status,created_at FROM trade_order WHERE user_id=(SELECT id FROM iam_user WHERE username LIKE 'd_demo_%' ORDER BY id DESC LIMIT 1) ORDER BY created_at DESC,id DESC LIMIT 20";
        expiringOrders="SELECT id FROM trade_order WHERE status='PENDING_PAYMENT' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at,id LIMIT 100";
        pendingOutbox="SELECT id FROM outbox_event WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at LIMIT 100"
    }
    foreach($entry in $queries.GetEnumerator()) {
        $statement='EXPLAIN FORMAT=JSON '+$entry.Value
        $plan=& $mysql --no-defaults --host=127.0.0.1 --port=13306 --user=yueji_dev --database=yueji_mall_dev --batch --skip-column-names --raw "--execute=$statement"
        if($LASTEXITCODE -ne 0){throw '执行计划检查失败'}
        $plans[$entry.Key]=(($plan -join "`n") | ConvertFrom-Json)
    }
    $plans | ConvertTo-Json -Depth 30 | Set-Content (Join-Path $projectRoot '.artifacts/D-query-plans.json') -Encoding utf8
    Write-Output "开发库不变量通过；实际批次 $($report.batches)，订单 $($report.orders)，预占 $($report.reserved)。"
} finally {Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue}
