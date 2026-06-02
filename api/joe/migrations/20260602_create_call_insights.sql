-- Migration: create call_insights table
CREATE TABLE IF NOT EXISTS `call_insights` (
  `id` INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `upload_id` INT UNSIGNED DEFAULT NULL,
  `file_path` VARCHAR(1024) DEFAULT NULL,
  `transcript` LONGTEXT DEFAULT NULL,
  `insights_json` JSON DEFAULT NULL,
  `numbers_json` JSON DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX (`upload_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
