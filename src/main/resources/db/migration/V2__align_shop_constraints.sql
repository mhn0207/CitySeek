ALTER TABLE tb_shop
    MODIFY COLUMN x double NOT NULL COMMENT '经度，-180～180',
    MODIFY COLUMN y double NOT NULL COMMENT '纬度，-90～90',
    MODIFY COLUMN sold int UNSIGNED NOT NULL DEFAULT 0 COMMENT '销量',
    MODIFY COLUMN comments int UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量',
    MODIFY COLUMN score int UNSIGNED NOT NULL DEFAULT 0
        COMMENT '评分乘10保存；0表示暂无评分，正常范围10～50';

ALTER TABLE tb_blog
    MODIFY COLUMN liked int UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数量',
    MODIFY COLUMN comments int UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量';

-- Rollback: deploy the old application first, verify x/y contain no negative values, then restore
-- UNSIGNED and previous defaults. Negative coordinates cannot be represented by the old schema.
