UPDATE custom_agents
SET definition_json = JSON_REMOVE(definition_json, '$.inputSchema')
WHERE JSON_VALID(definition_json)
  AND JSON_CONTAINS_PATH(definition_json, 'one', '$.inputSchema') = 1;
