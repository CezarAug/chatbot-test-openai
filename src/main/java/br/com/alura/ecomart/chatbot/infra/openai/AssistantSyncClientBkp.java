package br.com.alura.ecomart.chatbot.infra.openai;

import br.com.alura.ecomart.chatbot.domain.DadosCalculoFrete;
import br.com.alura.ecomart.chatbot.domain.UF;
import br.com.alura.ecomart.chatbot.domain.service.CalculadorDeFrete;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonObject;
import com.openai.core.JsonValue;
import com.openai.models.beta.threads.Thread;
import com.openai.models.beta.threads.ThreadDeleteParams;
import com.openai.models.beta.threads.messages.Message;
import com.openai.models.beta.threads.messages.MessageCreateParams;
import com.openai.models.beta.threads.messages.MessageListPage;
import com.openai.models.beta.threads.messages.MessageListParams;
import com.openai.models.beta.threads.runs.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.openai.core.ObjectMappers.jsonMapper;

/**
 * Calling an assistant with the Sync OpenAIClient
 */
@Component
public class AssistantSyncClientBkp {

  private static final Logger log = LoggerFactory.getLogger(AssistantSyncClientBkp.class);

  private final OpenAIClient client;

  private final String assistantId;

  private final CalculadorDeFrete calculadorDeFrete;

  //TODO: Better handle this for each session
  private String threadId = null;

  public AssistantSyncClientBkp(@Value("${app.openai.assistant.id}") String assistantId,
                                CalculadorDeFrete calculadorDeFrete) {
    this.client = OpenAIOkHttpClient.fromEnv();
    this.assistantId = assistantId;
    this.calculadorDeFrete = calculadorDeFrete;
  }


  //TODO: Break this into smaller functions
  public String getAssistantResponse(DadosRequisicaoChatCompletion prompts) {

    if (threadId == null || threadId.isBlank()) {
      Thread thread = client.beta().threads().create();
      threadId = thread.id();
    }


    client.beta().threads().messages()
        .create(MessageCreateParams.builder()
            .threadId(threadId)
            .role(MessageCreateParams.Role.USER)
            .content(prompts.promptUsuario())
            .build());

    Run run = client.beta()
        .threads()
        .runs()
        .create(RunCreateParams.builder()
            .threadId(threadId)
            .assistantId(assistantId)
            .build());


    while (run.status().equals(RunStatus.QUEUED) || run.status().equals(RunStatus.IN_PROGRESS) || run.status().equals(RunStatus.REQUIRES_ACTION)) {
      System.out.println("Polling run...");
      //TODO: Using the demo code, it needs to be fixed and use a proper non blocking method
      try {
        java.lang.Thread.sleep(500);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }


      // If the run is asking for a function/tool call
      if (run.status().equals(RunStatus.REQUIRES_ACTION) && run.requiredAction().isPresent()) {
        List<RequiredActionFunctionToolCall> toolCalls = run.requiredAction().get().submitToolOutputs().toolCalls();
        List<RunSubmitToolOutputsParams.ToolOutput> outputs = new ArrayList<>();

        for (RequiredActionFunctionToolCall toolCall : toolCalls) {
          RequiredActionFunctionToolCall.Function function = toolCall.function();
          String output = callFunction(function); // Your custom logic here
          outputs.add(RunSubmitToolOutputsParams.ToolOutput.builder()
              .toolCallId(toolCall.id())
              .output(output)
              .build());
        }

        // Submit the tool outputs
        run = client.beta().threads().runs().submitToolOutputs(RunSubmitToolOutputsParams.builder()
            .threadId(threadId)
            .runId(run.id())
            .toolOutputs(outputs)
            .build());
      } else {
        // Keep polling
        run = client.beta().threads().runs().retrieve(RunRetrieveParams.builder()
            .threadId(threadId)
            .runId(run.id())
            .build());
      }
    }


    log.info("Run completed with status: " + run.status());

    if (!run.status().equals(RunStatus.COMPLETED)) {
      //TODO: Improve this scenario handling
      System.err.println(run.status().asString());
      return null;
    }

    MessageListPage page = client.beta()
        .threads()
        .messages()
        .list(MessageListParams.builder()
            .threadId(threadId)
            .order(MessageListParams.Order.ASC)
            .build());


    var answer = page.autoPager().stream()

        .flatMap(currentMessage -> {
          log.info(currentMessage.role().toString().toUpperCase());
          return currentMessage.content().stream();
        })
        .flatMap(content -> content.text().stream())
        //TODO: The idea behind this replace is to remove the reference generated when knowledge retrieval is used
        .map(textBlock -> textBlock.text().value().replaceAll("\\\u3010.*?\\\u3011", ""))
        .reduce((first, second) -> second)
        .orElse("");

    log.info(answer);


    return answer;
  }

  private String callFunction(RequiredActionFunctionToolCall.Function function) {


    if (!function.name().equals("calcularFrete")) {
      throw new IllegalArgumentException("Unknown function: " + function.name());
    }

    JsonValue arguments;
    try {
      arguments = JsonValue.from(jsonMapper().readTree(function.arguments()));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Bad function arguments", e);
    }

    String uf = ((JsonObject) arguments).values().get("uf").asStringOrThrow();
    Integer quantidade = (Integer) ((JsonObject) arguments).values().get("quantidadeProdutos").asNumber().get();

    return calculadorDeFrete.calcular(new DadosCalculoFrete(Integer.valueOf(quantidade), UF.valueOf(uf))).toString();
  }

  public List<String> loadChatHistory() {
    List<String> messages = new ArrayList<>();

    //TODO: Again, threadId needs to be moved somewhere better handled
    if (this.threadId != null && !threadId.isBlank()) {

      messages = client.beta().threads().messages().list(
              MessageListParams.builder().threadId(threadId).build()
          ).data().stream()
          .sorted(Comparator.comparingLong(Message::createdAt))
          .flatMap(item -> item.content().stream())
          .flatMap(message -> message.text().stream())
          .map(textBlock -> textBlock.text().value())
          .toList();


    }

    return messages;
  }

  public void deleteThread() {
    if (threadId != null && !threadId.isBlank()) {
      client.beta().threads().delete(ThreadDeleteParams.builder().threadId(threadId).build());
      threadId = null;
    }
  }
}
