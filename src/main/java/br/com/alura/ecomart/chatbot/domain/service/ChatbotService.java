package br.com.alura.ecomart.chatbot.domain.service;

import br.com.alura.ecomart.chatbot.infra.openai.client.AssistantAsyncClient;
import br.com.alura.ecomart.chatbot.infra.openai.client.AssistantClient;
import br.com.alura.ecomart.chatbot.infra.openai.client.AssistantSyncClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Service
public class ChatbotService {

  private AssistantClient client;

  public ChatbotService(AssistantSyncClient syncClient,
                        AssistantAsyncClient asyncClient,
                        @Value("${app.openai.client.type}") String clientType) {
    if("sync".equalsIgnoreCase(clientType)) {
      this.client = syncClient;
    } else if("async".equalsIgnoreCase(clientType)) {
      this.client = asyncClient;
    }
  }

  public void answerQuestion(String question, SseEmitter emitter) {
    client.getAssistantResponse(question, emitter);
  }

  public List<String> loadChatHistory() {
    return client.loadChatHistory();
  }


  public void wipeChatHistory() {
    client.deleteThread();
  }
}
