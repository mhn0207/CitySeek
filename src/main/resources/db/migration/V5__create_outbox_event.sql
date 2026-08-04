CREATE TABLE tb_outbox_event (
    id bigint NOT NULL,
    aggregate_type varchar(32) NOT NULL,
    aggregate_id bigint NOT NULL,
    event_type varchar(64) NOT NULL,
    payload varchar(2048) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    retry_count int NOT NULL DEFAULT 0,
    next_retry_time timestamp NULL DEFAULT NULL,
    error_message varchar(512) NULL,
    claimed_by varchar(128) NULL,
    claim_token varchar(64) NULL,
    processing_deadline timestamp NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_outbox_pending (status, next_retry_time, create_time),
    KEY idx_outbox_processing (status, processing_deadline, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Rollback (destructive): stop the Outbox Worker, verify no PENDING/PROCESSING events remain,
-- deploy the synchronous-feed version, then DROP TABLE tb_outbox_event.
