ALTER TABLE tb_user
    ADD COLUMN role varchar(16) NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN'
    AFTER icon;

-- 升级环境不会自动授予管理员权限。请由数据库管理员审计后执行：
-- UPDATE tb_user SET role = 'ADMIN' WHERE id = <trusted_user_id>;

-- Rollback (after deploying code that no longer reads role): ALTER TABLE tb_user DROP COLUMN role;
