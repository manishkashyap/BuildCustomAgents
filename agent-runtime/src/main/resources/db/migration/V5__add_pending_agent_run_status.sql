ALTER TABLE agent_runs
    DROP CHECK chk_agent_runs_status,
    ADD CONSTRAINT chk_agent_runs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_FOR_HUMAN', 'WAITING_FOR_CHILD',
                          'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'));
