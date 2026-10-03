-- Integrated V28: independent AI settings after account metadata V27.
CREATE TABLE user_ai_settings (
    user_id BIGINT NOT NULL PRIMARY KEY,
    base_url VARCHAR(500) NOT NULL,
    model VARCHAR(120) NOT NULL,
    encrypted_key VARCHAR(3000) NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_user_ai_settings_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);
