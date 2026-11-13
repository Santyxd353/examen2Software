package com.classforge.assistant.llm;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/** Provider transport used by the validated conversation, UML and image pipelines. */
public interface AssistantChatClient {
    JsonNode chat(Map<String, Object> payload);
}
