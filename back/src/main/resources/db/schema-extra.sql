CREATE TABLE IF NOT EXISTS `linkwork_security_policy` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `description` TEXT NULL,
  `type` VARCHAR(32) NOT NULL DEFAULT 'custom' COMMENT 'system or custom',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `rules_json` JSON NULL,
  `creator_id` VARCHAR(128) NULL,
  `creator_name` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `linkwork_user_soul` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` VARCHAR(128) NOT NULL,
  `content` TEXT NULL,
  `preset_id` VARCHAR(64) NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `creator_id` VARCHAR(128) NULL,
  `creator_name` VARCHAR(255) NULL,
  `updater_id` VARCHAR(128) NULL,
  `updater_name` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `linkwork_user_auth_gitlab` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` VARCHAR(128) NOT NULL,
  `gitlab_id` BIGINT NULL,
  `username` VARCHAR(255) NULL,
  `name` VARCHAR(255) NULL,
  `avatar_url` VARCHAR(1024) NULL,
  `access_token` VARCHAR(1024) NULL,
  `refresh_token` VARCHAR(1024) NULL,
  `token_alias` VARCHAR(255) NULL,
  `expires_at` DATETIME NULL,
  `scope` VARCHAR(512) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
