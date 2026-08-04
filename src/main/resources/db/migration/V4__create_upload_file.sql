CREATE TABLE tb_upload_file (
    id bigint NOT NULL AUTO_INCREMENT,
    path varchar(512) NOT NULL,
    uploader_id bigint UNSIGNED NOT NULL,
    business_type varchar(32) NOT NULL DEFAULT 'BLOG',
    status tinyint NOT NULL DEFAULT 0 COMMENT '0临时 1已绑定 2已删除',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_path (path),
    KEY idx_upload_owner (uploader_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Rollback (destructive): DROP TABLE tb_upload_file; back up ownership metadata first.
