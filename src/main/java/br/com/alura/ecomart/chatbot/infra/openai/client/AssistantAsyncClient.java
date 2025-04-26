package br.com.alura.ecomart.chatbot.infra.openai.client;

import br.com.alura.ecomart.chatbot.domain.service.CalculadorDeFrete;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.beta.threads.messages.Message;
import com.openai.models.beta.threads.messages.MessageCreateParams;
import com.openai.models.beta.threads.messages.MessageListParams;
import com.openai.models.beta.threads.runs.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Calling an assistant with the Async OpenAIClient
 */
@Component
public class AssistantAsyncClient extends AssistantClient {

  private static final Logger log = LoggerFactory.getLogger(AssistantAsyncClient.class);

  private final OpenAIClientAsync client;

  private final String assistantId;

  //TODO: This shouldn't be handled here
  private String threadId = null;

  public AssistantAsyncClient(@Value("${app.openai.assistant.id}") String assistantId,
                              CalculadorDeFrete calculadorDeFrete) {

    super(calculadorDeFrete);
    this.client = OpenAIOkHttpClientAsync.fromEnv();
    this.assistantId = assistantId;
  }


  public void getAssistantResponse(String userPrompt, SseEmitter emitter) {
    // 1. Create a new message and threadId
    createInitialMessage(userPrompt).thenCompose(message -> {
      log.info("Message: {}", message);
      // 2. Start a run on the existing thread
      return client.beta().threads().runs().create(
          RunCreateParams.builder()
              .threadId(threadId)
              .assistantId(assistantId)
              .build()
      );
    }).thenCompose(run -> {
      // Polling
      log.info("Run started: {}", run.id());
      return pollRun(client, run);

    }).thenCompose(finalRun -> {
      // Run finished, get the answer
      log.info("Final Run status {}", finalRun.status());
      return getLastAssistantMessage();

    }).thenAccept(assistantMsg -> {
      // Return the answer to the front-end through SseEmitter
      if (assistantMsg != null) {
        try {
          emitter.send(assistantMsg.content().get(0).asText().text().value());
          emitter.complete();
        } catch (IOException e) {
          emitter.completeWithError(e);
        }
      } else {
        emitter.completeWithError(new IllegalStateException("No assistant reply found."));
      }
    }).exceptionally(ex -> {
      emitter.completeWithError(ex);
      return null;
    });
  }

  @Override
  public List<String> loadChatHistory() {
    return List.of();
  }

  @Override
  public void deleteThread() {

  }

  @NotNull
  private CompletableFuture<Message> createMessage(String userPrompt, String threadId) {
    return client.beta().threads().messages().create(
        MessageCreateParams.builder()
            .threadId(threadId)
            .role(MessageCreateParams.Role.USER)
            .content(userPrompt)
            .build());
  }

  @NotNull
  private CompletableFuture<Message> getLastAssistantMessage() {
    return client.beta()
        .threads()
        .messages()
        .list(MessageListParams.builder()
            .threadId(threadId)
            .build())
        .thenApply(page ->
            page.data().stream().filter(msg -> Message.Role.ASSISTANT.equals(msg.role()))
                .findFirst().orElse(null));
  }

  private CompletableFuture<Message> createInitialMessage(String userPrompt) {
    if (threadId == null || threadId.isBlank()) {
      // Creating thread
      return client.beta()
          .threads()
          .create()
          .thenComposeAsync(thread -> {
            threadId = thread.id();
            return createMessage(userPrompt, thread.id());
          });
    } else {
      return createMessage(userPrompt, threadId);
    }
  }

  private CompletableFuture<Run> pollRun(OpenAIClientAsync client, Run run) {
    if (!run.status().equals(RunStatus.QUEUED) && !run.status().equals(RunStatus.IN_PROGRESS) && !run.status().equals(RunStatus.REQUIRES_ACTION)) {
      log.info("Run completed with status: {}", run.status());
      return CompletableFuture.completedFuture(run);
    }

    if (run.status().equals(RunStatus.REQUIRES_ACTION)) {
      log.info("Action required!");
      return executeAction(run).thenCompose(newRun -> pollRun(client, newRun));
    }

    log.info("Polling run...");

    return CompletableFuture
        .supplyAsync(() -> null, CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS))
        .thenCompose(unused -> client.beta().threads().runs().retrieve(RunRetrieveParams.builder()
            .threadId(run.threadId())
            .runId(run.id())
            .build()))
        .thenComposeAsync(updatedRun -> pollRun(client, updatedRun));
  }

  private CompletableFuture<Run> executeAction(Run finalRun) {
    List<RunSubmitToolOutputsParams.ToolOutput> outputs = new ArrayList<>();

    for (var toolCall : finalRun.requiredAction().get().submitToolOutputs().toolCalls()) {
      String output = callFunction(toolCall.function());

      outputs.add(RunSubmitToolOutputsParams.ToolOutput.builder()
          .toolCallId(toolCall.id())
          .output(output)
          .build());
    }

    return client.beta().threads().runs().submitToolOutputs(RunSubmitToolOutputsParams.builder()
            .threadId(threadId)
            .runId(finalRun.id())
            .toolOutputs(outputs)
            .build());
  }
}
