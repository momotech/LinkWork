-- Skill 可见性控制：支持用户隔离 + 公开 Skill

SET @column_exists = (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'linkwork_skill'
      AND column_name = 'is_public'
);

SET @add_column_sql = IF(
    @column_exists = 0,
    'ALTER TABLE linkwork_skill ADD COLUMN is_public TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否公开'' AFTER status',
    'SELECT 1'
);
PREPARE add_column_stmt FROM @add_column_sql;
EXECUTE add_column_stmt;
DEALLOCATE PREPARE add_column_stmt;

-- 历史系统技能（无创建人）默认转公开，避免升级后不可见
UPDATE linkwork_skill
SET is_public = 1
WHERE (creator_id IS NULL OR creator_id = '')
  AND is_deleted = 0;

SET @index_exists = (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'linkwork_skill'
      AND index_name = 'idx_skill_visibility'
);

SET @add_index_sql = IF(
    @index_exists = 0,
    'ALTER TABLE linkwork_skill ADD INDEX idx_skill_visibility (is_deleted, status, is_public, creator_id)',
    'SELECT 1'
);
PREPARE add_index_stmt FROM @add_index_sql;
EXECUTE add_index_stmt;
DEALLOCATE PREPARE add_index_stmt;
