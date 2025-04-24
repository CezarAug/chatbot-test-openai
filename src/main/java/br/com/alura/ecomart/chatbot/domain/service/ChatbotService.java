package br.com.alura.ecomart.chatbot.domain.service;

import br.com.alura.ecomart.chatbot.infra.openai.AssistantAsyncClient;
import br.com.alura.ecomart.chatbot.infra.openai.AssistantSyncClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Service
public class ChatbotService {

  private AssistantSyncClient client;

  private AssistantAsyncClient asyncClient;

  public ChatbotService(AssistantSyncClient client, AssistantAsyncClient asyncClient) {
    this.client = client;
    this.asyncClient = asyncClient;
  }

  public void answerQuestion(String question, SseEmitter emitter) {
    client.streamAssistantResponse(question, emitter);
  }

  public void answerQuestionAsync(String question, SseEmitter emitter) {
    asyncClient.getAsyncAssistantResponse(question, emitter);
  }

  public List<String> loadChatHistory() {
    return client.loadChatHistory();
  }


  public void wipeChatHistory() {
    client.deleteThread();
  }
}
