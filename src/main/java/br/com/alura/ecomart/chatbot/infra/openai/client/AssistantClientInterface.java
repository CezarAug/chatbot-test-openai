package br.com.alura.ecomart.chatbot.infra.openai.client;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface AssistantClientInterface {
  void getAssistantResponse(String question, SseEmitter emitter);
  List<String> loadChatHistory();
  void deleteThread();

}
