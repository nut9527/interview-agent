-- 已执行原 V52 的实例在升级前于应用库运行一次，将 Flyway 校验值与随新版发布的种子脚本对齐。
-- 版本、脚本名和原校验值必须同时匹配，重复执行不再更新记录。
UPDATE flyway_schema_history
SET checksum = 1976613928
WHERE version = '52' AND type = 'SQL' AND success = 1
  AND script = 'V52__reference_learning_questions.sql' AND checksum = 1176925259;
