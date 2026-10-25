-- Reference data: the six markets (escalation threshold is in local currency) and the claim number counters.
-- Demo users, policies and claims are not here; the DataInitializer creates them so they can be switched off.

INSERT INTO markets (code, country_name, currency_code, escalation_threshold, active) VALUES ('IN', 'India', 'INR', 500000.00, TRUE);
INSERT INTO markets (code, country_name, currency_code, escalation_threshold, active) VALUES ('SG', 'Singapore', 'SGD', 10000.00, TRUE);
INSERT INTO markets (code, country_name, currency_code, escalation_threshold, active) VALUES ('AU', 'Australia', 'AUD', 10000.00, TRUE);
INSERT INTO markets (code, country_name, currency_code, escalation_threshold, active) VALUES ('JP', 'Japan', 'JPY', 1000000.00, TRUE);
INSERT INTO markets (code, country_name, currency_code, escalation_threshold, active) VALUES ('MY', 'Malaysia', 'MYR', 25000.00, TRUE);
INSERT INTO markets (code, country_name, currency_code, escalation_threshold, active) VALUES ('TH', 'Thailand', 'THB', 200000.00, TRUE);

INSERT INTO claim_counters (claim_type, next_value) VALUES ('MOTOR', 10001);
INSERT INTO claim_counters (claim_type, next_value) VALUES ('PROPERTY', 10001);
