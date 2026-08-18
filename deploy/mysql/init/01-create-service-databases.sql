CREATE DATABASE IF NOT EXISTS custom_agent_management;
CREATE DATABASE IF NOT EXISTS custom_agent_runtime;

CREATE USER IF NOT EXISTS 'agent_management'@'%' IDENTIFIED BY 'management_dev';
CREATE USER IF NOT EXISTS 'agent_runtime'@'%' IDENTIFIED BY 'runtime_dev';

GRANT ALL PRIVILEGES ON custom_agent_management.* TO 'agent_management'@'%';
GRANT ALL PRIVILEGES ON custom_agent_runtime.* TO 'agent_runtime'@'%';

FLUSH PRIVILEGES;
